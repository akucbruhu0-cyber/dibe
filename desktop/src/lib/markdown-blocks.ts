/**
 * The Markdown an AI summary is written in, read into blocks that every export draws in its own
 * terms: Word paragraphs, PDF views, HTML elements or plain lines. Without this the files showed
 * the marks themselves (`### title`, `**bold**`, `---`).
 *
 * The subset CommonMark summaries use: ATX headings, paragraphs, `-`/`*`/`+` and numbered lists
 * (nested by indent), `>` quotes, rules, and bold, italic, code and links inside the text. The app's
 * own summary view (react-markdown) shows the same subset, so the file matches the screen.
 */
// ponytail: no tables, fenced code or backslash escapes; react-markdown here renders none of them either.

export interface Run {
	text: string
	bold?: boolean
	italic?: boolean
	code?: boolean
}

export type Block =
	/** `rank` 0 is the largest heading the text uses, whatever its number of `#`. */
	| { type: 'heading'; rank: number; runs: Run[] }
	| { type: 'paragraph'; runs: Run[] }
	| { type: 'bullet'; depth: number; runs: Run[] }
	| { type: 'number'; depth: number; marker: string; runs: Run[] }
	| { type: 'quote'; runs: Run[] }
	| { type: 'rule' }

export type ListBlock = Extract<Block, { type: 'bullet' | 'number' }>

const HEADING = /^\s{0,3}(#{1,6})\s+(.*?)(?:\s+#+)?\s*$/
const RULE = /^\s{0,3}([-*_])(?:\s*\1){2,}\s*$/
const BULLET = /^(\s*)[-*+]\s+(.*)$/
const NUMBER = /^(\s*)(\d{1,9}[.)])\s+(.*)$/
const QUOTE = /^\s{0,3}>\s?(.*)$/

/** Bold, italic (an underscore only between non-letters, so snake_case survives), code, links. */
const INLINE = /\*\*(.+?)\*\*|__(.+?)__|\*(?=\S)(.+?)(?<=\S)\*|(?<![\p{L}\p{N}])_(?=\S)(.+?)(?<=\S)_(?![\p{L}\p{N}])|`([^`]+)`|\[([^\]]+)\]\([^)\s]*\)/gu

function depthOf(indent: string) {
	return Math.floor(indent.replace(/\t/g, '  ').length / 2)
}

/** A block before its text is read into runs, and before headings are ranked. */
type Raw =
	| { type: 'heading-raw'; level: number; text: string }
	| { type: 'paragraph' | 'quote'; text: string }
	| { type: 'bullet'; depth: number; text: string }
	| { type: 'number'; depth: number; marker: string; text: string }
	| { type: 'rule' }

export function parseMarkdown(markdown: string): Block[] {
	const raw: Raw[] = []
	/** The block a following line continues; a blank line or any other block closes it. */
	let open: Exclude<Raw, { type: 'rule' | 'heading-raw' }> | null = null

	for (const line of markdown.replace(/\r\n?/g, '\n').split('\n')) {
		if (!line.trim()) {
			open = null
			continue
		}
		let match: RegExpMatchArray | null
		let next: Raw
		if ((match = line.match(HEADING))) next = { type: 'heading-raw', level: match[1].length, text: match[2] }
		else if (RULE.test(line)) next = { type: 'rule' }
		else if ((match = line.match(BULLET))) next = { type: 'bullet', depth: depthOf(match[1]), text: match[2] }
		else if ((match = line.match(NUMBER))) next = { type: 'number', depth: depthOf(match[1]), marker: match[2], text: match[3] }
		else if ((match = line.match(QUOTE)) && open?.type === 'quote') {
			open.text += ` ${match[1].trim()}`
			continue
		} else if (match) next = { type: 'quote', text: match[1] }
		else if (open) {
			// A line under a paragraph, quote or list item belongs to it.
			open.text += ` ${line.trim()}`
			continue
		} else next = { type: 'paragraph', text: line.trim() }

		raw.push(next)
		open = next.type === 'rule' || next.type === 'heading-raw' ? null : next
	}

	const levels = raw.flatMap((block) => (block.type === 'heading-raw' ? [block.level] : []))
	const top = Math.min(...levels)
	return raw.map((block): Block => {
		if (block.type === 'rule') return block
		if (block.type === 'heading-raw') return { type: 'heading', rank: block.level - top, runs: parseInline(block.text) }
		const { text, ...rest } = block
		return { ...rest, runs: parseInline(text.trim()) } as Block
	})
}

function parseInline(text: string, marks: Omit<Run, 'text'> = {}): Run[] {
	const runs: Run[] = []
	let last = 0
	for (const match of text.matchAll(INLINE)) {
		const index = match.index ?? 0
		if (index > last) runs.push({ text: text.slice(last, index), ...marks })
		const [, bold1, bold2, italic1, italic2, code, link] = match
		if (bold1 ?? bold2) runs.push(...parseInline(bold1 ?? bold2, { ...marks, bold: true }))
		else if (italic1 ?? italic2) runs.push(...parseInline(italic1 ?? italic2, { ...marks, italic: true }))
		else if (code) runs.push({ text: code, ...marks, code: true })
		else if (link) runs.push(...parseInline(link, marks))
		last = index + match[0].length
	}
	if (last < text.length) runs.push({ text: text.slice(last), ...marks })
	// Neighbours with the same marks are one run: Word and PDF then break lines as in one word.
	return runs.reduce<Run[]>((merged, run) => {
		const previous = merged[merged.length - 1]
		if (previous && !!previous.bold === !!run.bold && !!previous.italic === !!run.italic && !!previous.code === !!run.code) previous.text += run.text
		else if (run.text) merged.push({ ...run })
		return merged
	}, [])
}

export function runsText(runs: Run[]) {
	return runs.map((run) => run.text).join('')
}

/** Plain text: the words, with lists as `•` and numbers, for the formats that have no styling. */
export function markdownToText(markdown: string): string {
	const out: string[] = []
	let previous: Block | null = null
	for (const block of parseMarkdown(markdown)) {
		if (block.type === 'rule') continue
		const list = block.type === 'bullet' || block.type === 'number'
		const line =
			block.type === 'bullet'
				? `${'  '.repeat(block.depth)}• ${runsText(block.runs)}`
				: block.type === 'number'
					? `${'  '.repeat(block.depth)}${block.marker} ${runsText(block.runs)}`
					: runsText(block.runs)
		// The items of one list stay together; everything else is a paragraph of its own.
		if (out.length) out.push(list && previous?.type === block.type ? '\n' : '\n\n')
		out.push(line)
		previous = block
	}
	return out.join('')
}

function escapeHtml(value: string) {
	return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;')
}

function runsHtml(runs: Run[]) {
	return runs
		.map((run) => {
			let html = escapeHtml(run.text)
			if (run.code) html = `<code>${html}</code>`
			if (run.italic) html = `<em>${html}</em>`
			if (run.bold) html = `<strong>${html}</strong>`
			return html
		})
		.join('')
}

/** Nested `<ul>`/`<ol>` for a run of consecutive list items. */
function listHtml(items: ListBlock[]) {
	let html = ''
	const stack: { tag: 'ul' | 'ol'; depth: number }[] = []
	const close = () => `</li></${stack.pop()!.tag}>`
	for (const item of items) {
		const tag = item.type === 'bullet' ? 'ul' : 'ol'
		while (stack.length && stack[stack.length - 1].depth > item.depth) html += close()
		const top = stack[stack.length - 1] as (typeof stack)[number] | undefined
		if (top && top.depth === item.depth && top.tag === tag) html += '</li>'
		else {
			if (top && top.depth === item.depth) html += close()
			html += `<${tag}>`
			stack.push({ tag, depth: item.depth })
		}
		html += `<li>${runsHtml(item.runs)}`
	}
	while (stack.length) html += close()
	return html
}

/** HTML elements; the largest heading becomes `<h{topLevel}>`, under the page's own headings. */
export function markdownToHtml(markdown: string, topLevel: number): string {
	const out: string[] = []
	const blocks = parseMarkdown(markdown)
	for (let index = 0; index < blocks.length; index++) {
		const block = blocks[index]
		if (block.type === 'bullet' || block.type === 'number') {
			const items: ListBlock[] = []
			while (index < blocks.length && (blocks[index].type === 'bullet' || blocks[index].type === 'number')) items.push(blocks[index++] as ListBlock)
			index--
			out.push(listHtml(items))
		} else if (block.type === 'heading') {
			const level = Math.min(topLevel + block.rank, 6)
			out.push(`<h${level}>${runsHtml(block.runs)}</h${level}>`)
		} else if (block.type === 'rule') out.push('<hr>')
		else if (block.type === 'quote') out.push(`<blockquote>${runsHtml(block.runs)}</blockquote>`)
		else out.push(`<p>${runsHtml(block.runs)}</p>`)
	}
	return out.join('\n')
}
