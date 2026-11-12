/**
 * The visuals a summary carries as fenced blocks, ```` ```dibe:chart ```` and so on: a few `key: value`
 * lines (title, type), then one line per row. Line-based on purpose: a small local model writes it
 * reliably and a cut-off answer still parses. Anything that doesn't read as a visual returns null and
 * is not shown. Port of android/.../ui/transcript/Visual.kt and ai/VisualPass.kt; keep them in step.
 */

export interface ChartRow {
	label: string
	value: number
	/** `#rrggbb`, or null for the palette's next color. */
	color: string | null
}
export interface TimelineEvent {
	time: string
	title: string
	detail: string
}
export interface TreeNode {
	title: string
	body: string
	choices: [label: string, target: string][]
}

export type Visual =
	| { kind: 'chart'; title: string; pie: boolean; rows: ChartRow[] }
	/** Top to bottom: each node sits one layer below its deepest parent. */
	| { kind: 'flow'; title: string; layers: string[][] }
	| { kind: 'timeline'; title: string; events: TimelineEvent[] }
	/** Starts at `start`, else the first node. */
	| { kind: 'tree'; title: string; start: string; nodes: Record<string, TreeNode> }
	/** The first row names the columns; each later row is one item. */
	| { kind: 'compare'; title: string; header: string[]; rows: string[][] }

const MAX_ROWS = 8
/** A comparison is also every Markdown table (see `tablesAsVisuals`), which runs longer than a chart. */
const MAX_TABLE_ROWS = 30
const META = /^\s*(title|type|after)\s*:\s*(.*)$/i
const NUMBER = /-?\d+(?:[.,]\d+)?/
const HEX = /^#?([0-9a-fA-F]{6})$/

/** `dibe:chart` / `dibe-chart` (a fence's info string, or `language-dibe:chart`) → `chart`. */
export function visualKind(info: string): string | null {
	return /(?:^|language-)dibe[:-]([a-z]+)$/i.exec(info.trim())?.[1]?.toLowerCase() ?? null
}

const cells = (line: string) =>
	line
		.trim()
		.replace(/^\|+|\|+$/g, '')
		.split('|')
		.map((cell) => cell.trim())

export function parseVisual(kind: string, body: string): Visual | null {
	const meta: Record<string, string> = {}
	const rows: string[] = []
	for (const line of body.split('\n')) {
		if (!line.trim()) continue
		const match = META.exec(line)
		if (match && rows.length === 0) meta[match[1].toLowerCase()] = match[2].trim()
		else rows.push(line)
	}
	const title = (meta.title ?? '').replace(/^["\s]+|["\s]+$/g, '')
	switch (kind) {
		case 'chart':
			return chart(title, (meta.type ?? '').toLowerCase(), rows)
		case 'flow':
		case 'flowchart':
			return flow(title, rows)
		case 'timeline':
			return timeline(title, rows)
		case 'tree':
		case 'decision-tree':
			return tree(title, rows)
		case 'compare':
		case 'table':
			return compare(title, rows)
		default:
			return null
	}
}

function chart(title: string, type: string, lines: string[]): Visual | null {
	const rows: ChartRow[] = []
	for (const line of lines) {
		const parts = cells(line)
		if (parts.length < 2 || !parts[0]) continue
		const found = NUMBER.exec(parts[1])
		if (!found) continue
		const value = Number(found[0].replace(',', '.'))
		if (!(value >= 0)) continue
		const hex = parts[2] ? HEX.exec(parts[2]) : null
		rows.push({ label: parts[0], value, color: hex ? `#${hex[1]}` : null })
	}
	const shown = rows.slice(0, MAX_ROWS)
	if (!shown.length || shown.every((row) => row.value === 0)) return null
	return { kind: 'chart', title, pie: type === 'pie' || type === 'donut', rows: shown }
}

/** A node's identity without a trailing "(...)": models shorten "Fear (panic)" to "Fear" on its next line. */
const nodeKey = (name: string) => name.split(' (')[0].trim().toLowerCase()

function flow(title: string, lines: string[]): Visual | null {
	const names = new Map<string, string>() // key -> longest spelling seen
	const edges: [string, string][] = []
	for (const line of lines) {
		const parts = line
			.split(/->|→/)
			.map((part) => part.trim().replace(/^[-•\s]+/, ''))
			.filter(Boolean)
		for (const name of parts) {
			const seen = names.get(nodeKey(name))
			if (!seen || name.length > seen.length) names.set(nodeKey(name), name)
		}
		for (let i = 1; i < parts.length; i++) {
			if (nodeKey(parts[i - 1]) !== nodeKey(parts[i])) edges.push([nodeKey(parts[i - 1]), nodeKey(parts[i])])
		}
	}
	if (names.size < 2) return null
	// Longest-path layering; a cycle stops growing after names.size rounds.
	const layer = new Map([...names.keys()].map((key) => [key, 0]))
	for (let round = 0; round < names.size; round++) {
		for (const [a, b] of edges) {
			if (layer.get(b)! < layer.get(a)! + 1) layer.set(b, Math.min(layer.get(a)! + 1, names.size))
		}
	}
	const byLayer = new Map<number, string[]>()
	for (const [key, name] of names) byLayer.set(layer.get(key)!, [...(byLayer.get(layer.get(key)!) ?? []), name])
	return { kind: 'flow', title, layers: [...byLayer.entries()].sort(([a], [b]) => a - b).map(([, list]) => list) }
}

function timeline(title: string, lines: string[]): Visual | null {
	const events: TimelineEvent[] = []
	for (const line of lines) {
		const parts = cells(line)
		if (parts.length >= 3) events.push({ time: parts[0], title: parts[1], detail: parts.slice(2).join(' | ') })
		else if (parts.length === 2) events.push({ time: parts[0], title: parts[1], detail: '' })
	}
	return events.length ? { kind: 'timeline', title, events: events.slice(0, MAX_ROWS * 2) } : null
}

// Ids in any script: a Hebrew summary gets Hebrew ids, and an ASCII-only id dropped the whole tree.
const NODE = /^\s*([\p{L}\p{N}_-]+)\s*::\s*(.*)$/u
const CHOICE = /^\s*[-*]\s*(.+?)\s*=>\s*([\p{L}\p{N}_-]+)\s*$/u

function tree(title: string, lines: string[]): Visual | null {
	const nodes: Record<string, TreeNode> = {}
	let id: string | null = null
	let heading = ''
	let body: string[] = []
	let choices: [string, string][] = []
	const close = () => {
		if (id) nodes[id] = { title: heading, body: body.join(' ').trim(), choices }
		body = []
		choices = []
	}
	for (const line of lines) {
		const node = NODE.exec(line)
		const choice = CHOICE.exec(line)
		if (node) {
			close()
			id = node[1]
			heading = node[2].trim()
		} else if (choice && id) choices.push([choice[1], choice[2]])
		else if (line.trim() && id) body.push(line.trim())
	}
	close()
	const ids = Object.keys(nodes)
	if (!ids.length || ids.every((key) => nodes[key].choices.length === 0)) return null
	return { kind: 'tree', title, start: 'start' in nodes ? 'start' : ids[0], nodes }
}

function compare(title: string, lines: string[]): Visual | null {
	const rows = lines.filter((line) => !/^[\s|:-]+$/.test(line)).map(cells).filter((row) => row[0])
	if (rows.length < 2 || rows[0].length < 2) return null
	const header = rows[0]
	return { kind: 'compare', title, header, rows: rows.slice(1, MAX_TABLE_ROWS + 1).map((row) => header.map((_, i) => row[i] ?? '')) }
}

/** Every ```` ```dibe:… ```` block, closed. */
const FENCED = /^[ \t]*`{3,}[ \t]*dibe[:-]([a-zA-Z]+)[ \t]*\n([\s\S]*?)\n[ \t]*`{3,}[ \t]*$/gm

const plainNumber = (value: number) => String(value)

function asMarkdown(visual: Visual): string {
	const lines: string[] = []
	if (visual.title.trim()) lines.push(`**${visual.title}**`)
	switch (visual.kind) {
		case 'chart': {
			const total = visual.rows.reduce((sum, row) => sum + row.value, 0)
			for (const row of visual.rows) lines.push(`- ${row.label}: ${plainNumber(row.value)}${visual.pie ? ` (${Math.round((row.value / total) * 100)}%)` : ''}`)
			break
		}
		case 'flow':
			visual.layers.forEach((names, i) => lines.push(`${i + 1}. ${names.join(' · ')}`))
			break
		case 'timeline':
			for (const event of visual.events) lines.push(`- **${event.time}** ${event.title}${event.detail.trim() ? `: ${event.detail}` : ''}`)
			break
		case 'tree':
			for (const node of Object.values(visual.nodes)) {
				lines.push(`- **${node.title}**${node.body.trim() ? ` ${node.body}` : ''}`)
				for (const [label, target] of node.choices) lines.push(`  - ${label} → ${visual.nodes[target]?.title ?? target}`)
			}
			break
		case 'compare':
			for (const row of visual.rows) {
				lines.push(`- **${row[0]}**`)
				row.slice(1).forEach((cell, c) => cell.trim() && lines.push(`  - ${visual.header[c + 1]}: ${cell}`))
			}
			break
	}
	return lines.join('\n')
}

const TABLE_SEPARATOR = /^\s*\|?\s*:?-{2,}:?\s*(?:\|\s*:?-{2,}:?\s*)*\|?\s*$/

/**
 * Every Markdown table as a comparison visual. No renderer here draws tables (the screen, PDF, Word
 * and HTML all showed the pipes), and a table is exactly a comparison: a header row, then one row per item.
 */
export function tablesAsVisuals(markdown: string): string {
	const lines = markdown.split('\n')
	const out: string[] = []
	let fenced = false
	for (let i = 0; i < lines.length; i++) {
		const line = lines[i]
		if (/^\s*`{3,}/.test(line)) fenced = !fenced
		const separator = lines[i + 1] ?? ''
		if (!fenced && line.includes('|') && separator.includes('|') && TABLE_SEPARATOR.test(separator)) {
			let end = i + 2
			while (end < lines.length && lines[end].includes('|') && lines[end].trim()) end++
			const rows = [line, ...lines.slice(i + 2, end)].map((row) => row.replace(/\*\*|__/g, ''))
			out.push('```dibe:compare', ...rows, '```')
			i = end - 1
		} else out.push(line)
	}
	return out.join('\n')
}

/** [markdown] with every visual written out as plain Markdown, for files that can't draw it. */
export function visualsAsMarkdown(markdown: string): string {
	return tablesAsVisuals(markdown).replace(FENCED, (_, kind: string, body: string) => {
		const visual = parseVisual(kind.toLowerCase(), body)
		return visual ? asMarkdown(visual) : ''
	})
}

/**
 * The summary with each visual swapped for a marker paragraph, and the visuals in order. A file
 * renderer parses the whole text at once (heading sizes are ranked across all of it) and draws a
 * visual wherever a paragraph is exactly its marker. A block that doesn't parse is dropped.
 */
export function withVisualMarks(markdown: string): { text: string; visuals: Visual[] } {
	const visuals: Visual[] = []
	const text = tablesAsVisuals(markdown).replace(FENCED, (_, kind: string, body: string) => {
		const visual = parseVisual(kind.toLowerCase(), body)
		if (!visual) return ''
		visuals.push(visual)
		return `\n${visualMark(visuals.length - 1)}\n`
	})
	return { text, visuals }
}

export const visualMark = (index: number) => `@@dibe-visual-${index}@@`

/** The visual a paragraph stands for, when its whole text is a marker. */
export function markedVisual(paragraph: string, visuals: Visual[]): Visual | null {
	const match = /^@@dibe-visual-(\d+)@@$/.exec(paragraph.trim())
	return match ? (visuals[Number(match[1])] ?? null) : null
}

/** The visual's own direction, from its first strong letter: a Hebrew chart in an English UI still reads right to left. */
export function visualDirection(visual: Visual): 'rtl' | 'ltr' | null {
	const text =
		visual.title +
		(visual.kind === 'chart'
			? visual.rows.map((row) => row.label).join()
			: visual.kind === 'flow'
				? visual.layers.flat().join()
				: visual.kind === 'timeline'
					? visual.events.map((event) => event.title).join()
					: visual.kind === 'tree'
						? Object.values(visual.nodes).map((node) => node.title).join()
						: visual.header.join())
	const strong = /[\p{Script=Hebrew}\p{Script=Arabic}]|[\p{L}]/u.exec(text)?.[0]
	if (!strong) return null
	return /[\p{Script=Hebrew}\p{Script=Arabic}]/u.test(strong) ? 'rtl' : 'ltr'
}

/** Presets whose output is not a summary get no visuals. */
export const visualsApply = (prompt: string) => !/^[\s\S]{0,200}\bTranslate this transcript\b/.test(prompt)

/** The second pass after a summary; `{summary}` and `{transcript}` are filled by the caller. */
export const VISUAL_PROMPT = `Output only the requested content. No introductions, explanations, or commentary.

You may add visuals to the summary of a recording below: 0 to 3 of them, each only where it shows something the text alone conveys poorly. When nothing fits, output nothing at all. Write each as a fenced block in exactly one of these formats. All visible text is in {language}; labels are short (at most 6 words).

Choose the kind by what the content holds:
- numbers said in the recording (amounts, shares, counts, scores): chart
- a process, or causes and their effects: flow
- dates, deadlines, phases or an order of events: timeline
- a choice the listener faces, with its outcomes: tree
- two or more options, approaches or people side by side: compare

Put each visual under the heading whose section it explains.

A chart, for amounts, proportions, counts or scores. type is bar or pie. One row per item: label | number | optional #hex color. Use only numbers said in the recording; never estimate or invent them.
\`\`\`dibe:chart
after: <a heading of the summary, copied exactly>
type: pie
title: <title>
Label one | 50
Label two | 30
Label three | 20
\`\`\`

A flow, for a process, a cause and its effects, or how one thing led to another. One arrow per line; write each step with the same words every time.
\`\`\`dibe:flow
after: <heading>
title: <title>
Step one -> Step two
Step two -> Step three
\`\`\`

A timeline, for dates, deadlines, phases or the order things happen. One row per event: when | what | optional detail.
\`\`\`dibe:timeline
after: <heading>
title: <title>
Week 1 | Kickoff | Define the scope
\`\`\`

A decision tree, for a choice the listener faces. Nodes are \`id::Title\` (id is one short word), an optional line of explanation, then \`- Choice => id\`. The first node is start.
\`\`\`dibe:tree
after: <heading>
title: <title>
start::Question?
- Option A => a
- Option B => b

a::Outcome A
What it means.

b::Outcome B
What it means.
\`\`\`

A comparison, for two or more approaches, options, positions or people set side by side. The first row names the columns; each later row is one item.
\`\`\`dibe:compare
after: <heading>
title: <title>
Approach | Main idea | Weakness
First | ... | ...
Second | ... | ...
\`\`\`

Do not repeat a kind unless the content clearly calls for it. Only blocks, nothing between them.

<summary>
{summary}
</summary>

<transcript>
{transcript}
</transcript>`

/** A `#` heading, or a line that is only bold (`**Key points:**`), which models often write instead. */
/** For a question's answer: the same formats, without `after:`, since an answer has no sections. */
export const ASK_VISUALS = `When a visual would make the answer clearer (numbers said in the recording, a process, an order of events, a choice, options side by side), add it as a fenced block in one of these formats, with its text in {language}. Use a comparison block instead of a Markdown table.

${VISUAL_PROMPT.slice(VISUAL_PROMPT.indexOf('A chart,'), VISUAL_PROMPT.indexOf('Do not repeat'))
	.replace(/^after: .*\n/gm, '')
	.trim()}`

const HEADING = /^\s{0,3}(?:#{1,6}\s+(.*?)\s*#*|\*\*([^*]+?)\*\*\s*:?|__([^_]+?)__\s*:?)\s*$/
const ANSWER_BLOCK = /```[ \t]*dibe[:-]([a-z]+)[ \t]*\n([\s\S]*?)\n[ \t]*```/gi
const AFTER = /^\s*after\s*:\s*(.*)$/im
const plain = (text: string) => text.replace(/[*_`#]/g, '').trim().replace(/:$/, '').trim().toLowerCase()

/** The line index just before the heading after the one matching `after`, or null. */
function sectionEnd(lines: string[], after: string): number | null {
	if (!after) return null
	const start = lines.findIndex((line) => {
		const heading = HEADING.exec(line)
		if (!heading) return false
		const text = plain(heading[1] ?? heading[2] ?? heading[3] ?? '')
		return text !== '' && (text === after || text.includes(after) || after.includes(text))
	})
	if (start < 0) return null
	let end = start + 1
	while (end < lines.length && !HEADING.test(lines[end])) end++
	return end
}

/** `summary` with each closed block of `answer` at the end of the section its `after:` names; others go last. */
export function mergeVisuals(summary: string, answer: string): string {
	const lines = summary.trimEnd().split('\n')
	const tail: string[] = []
	for (const match of answer.matchAll(ANSWER_BLOCK)) {
		const kind = match[1].toLowerCase()
		const body = match[2]
		const after = plain(AFTER.exec(body)?.[1] ?? '')
		const clean = body
			.replace(new RegExp(AFTER.source, 'gim'), '')
			.split('\n')
			.filter((line) => line.trim() || kind === 'tree')
			.join('\n')
			.trim()
		const fenced = ['', `\`\`\`dibe:${kind}`, clean, '```', '']
		const at = sectionEnd(lines, after)
		if (at === null) tail.push(...fenced)
		else lines.splice(at, 0, ...fenced)
	}
	return [...lines, ...tail].join('\n').replace(/\n{3,}/g, '\n\n').trim()
}
