import { visualDirection, type Visual } from './visuals'

/**
 * A summary's visual as one standalone SVG, labels included, for files: inline in HTML (and so in the
 * export preview) and rasterized into Word. The PDF draws its own (pdf/pdf-visual.tsx) so its labels
 * stay real text in the embedded font.
 */

const PALETTE = ['#007AFF', '#FF3B30', '#34C759', '#FF9500', '#AF52DE', '#5AC8FA', '#FF2D55', '#FFCC00']
const colorOf = (index: number, own?: string | null) => own ?? PALETTE[index % PALETTE.length]

export const SVG_WIDTH = 640
const PAD = 20
const INNER = SVG_WIDTH - PAD * 2
const FONT = "Rubik, 'Arial Hebrew', 'Segoe UI', Arial, sans-serif"

export interface SvgInk {
	ink: string
	muted: string
	card: string
	line: string
}
export const LIGHT_INK: SvgInk = { ink: '#1a1c1f', muted: '#6e6e6e', card: '#ffffff', line: '#e2e2e2' }
export const DARK_INK: SvgInk = { ink: '#ececec', muted: '#a3a3a3', card: '#181818', line: '#3a3a3a' }

const esc = (text: string) => text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')

// ponytail: widths are estimated (no font metrics in a file); 0.58em per letter wraps a little early, never late.
const widthOf = (text: string, size: number) => text.length * size * 0.58

function wrap(text: string, width: number, size: number): string[] {
	const lines: string[] = []
	let line = ''
	for (const word of text.split(/\s+/).filter(Boolean)) {
		const next = line ? `${line} ${word}` : word
		if (line && widthOf(next, size) > width) {
			lines.push(line)
			line = word
		} else line = next
	}
	return line ? [...lines, line] : ['']
}

/** Draws in "start/end" terms; `x` is measured from the start edge, so right to left mirrors it. */
class Canvas {
	parts: string[] = []
	constructor(
		readonly rtl: boolean,
		readonly ink: SvgInk,
	) {}
	x(fromStart: number) {
		return this.rtl ? SVG_WIDTH - fromStart : fromStart
	}
	/**
	 * One line of text from the start edge (or ending at the end edge, with `end`). Anchors are purely
	 * geometric (the root SVG pins `direction:ltr`, or an RTL page around an inline SVG would flip
	 * every anchor); a right-to-left line is wrapped in RLE…PDF so its punctuation and numbers order
	 * correctly all the same.
	 */
	text(fromStart: number, y: number, text: string, { size = 14, weight = 400, fill = this.ink.ink, end = false, center = false } = {}) {
		const x = center ? fromStart : this.x(fromStart)
		const anchor = center ? 'middle' : end !== this.rtl ? 'end' : 'start'
		const content = this.rtl ? `\u202b${text}\u202c` : text
		this.parts.push(`<text x="${x.toFixed(1)}" y="${y.toFixed(1)}" font-size="${size}" font-weight="${weight}" fill="${fill}" text-anchor="${anchor}">${esc(content)}</text>`)
	}
	/** Wrapped text block; returns the y below its last line. */
	block(fromStart: number, y: number, text: string, width: number, style: { size?: number; weight?: number; fill?: string } = {}) {
		const size = style.size ?? 14
		const lineHeight = size * 1.35
		for (const line of wrap(text, width, size)) {
			y += lineHeight
			this.text(fromStart, y - size * 0.3, line, style)
		}
		return y
	}
	/** A rectangle given from the start edge. */
	rect(fromStart: number, y: number, width: number, height: number, attrs: string) {
		const left = this.rtl ? SVG_WIDTH - fromStart - width : fromStart
		this.parts.push(`<rect x="${left.toFixed(1)}" y="${y.toFixed(1)}" width="${Math.max(width, 0).toFixed(1)}" height="${height.toFixed(1)}" ${attrs}/>`)
	}
	raw(markup: string) {
		this.parts.push(markup)
	}
}

type Of<K extends Visual['kind']> = Extract<Visual, { kind: K }>

function bars(c: Canvas, visual: Of<'chart'>, y: number) {
	const max = Math.max(...visual.rows.map((row) => row.value))
	visual.rows.forEach((row, i) => {
		const labelEnd = c.block(PAD, y, row.label, INNER - 60)
		c.text(PAD + INNER, y + 14 * 1.35 - 4, String(row.value), { weight: 700, end: true })
		y = labelEnd + 4
		c.rect(PAD, y, INNER, 9, `rx="4.5" fill="${c.ink.line}"`)
		c.rect(PAD, y, (row.value / max) * INNER, 9, `rx="4.5" fill="${colorOf(i, row.color)}"`)
		y += 9 + 14
	})
	return y
}

function donut(c: Canvas, visual: Of<'chart'>, y: number) {
	const total = visual.rows.reduce((sum, row) => sum + row.value, 0)
	const r = 58
	const stroke = 18
	const cx = c.x(PAD + r + stroke / 2)
	const cy = y + r + stroke / 2 + 4
	const circumference = 2 * Math.PI * r
	// Round ends reach half a stroke past each arc, so slices sit a stroke (plus a hairline) apart.
	const gap = visual.rows.length > 1 ? (stroke + 4) / circumference : 0
	c.raw(`<circle cx="${cx}" cy="${cy}" r="${r}" fill="none" stroke="${c.ink.line}" stroke-width="${stroke}"/>`)
	let start = 0
	visual.rows.forEach((row, i) => {
		const share = row.value / total
		const length = Math.max(share - gap, 0.001) * circumference
		const rotate = -90 + (start + gap / 2) * 360
		c.raw(
			`<circle cx="${cx}" cy="${cy}" r="${r}" fill="none" stroke="${colorOf(i, row.color)}" stroke-width="${stroke}" stroke-linecap="round" stroke-dasharray="${length.toFixed(2)} ${circumference.toFixed(2)}" transform="rotate(${rotate.toFixed(2)} ${cx} ${cy})"/>`,
		)
		start += share
	})
	const legendStart = PAD + (r + stroke / 2) * 2 + 28
	const legendWidth = SVG_WIDTH - PAD - legendStart - 50
	let ly = y + 6
	visual.rows.forEach((row, i) => {
		const top = ly
		ly = c.block(legendStart + 18, ly, row.label, legendWidth - 18)
		c.raw(`<circle cx="${c.x(legendStart + 5)}" cy="${top + 12}" r="6" fill="${colorOf(i, row.color)}"/>`)
		c.text(SVG_WIDTH - PAD, top + 14 * 1.35 - 4, `${Math.round((row.value / total) * 100)}%`, { weight: 700, end: true })
		ly += 8
	})
	return Math.max(cy + r + stroke / 2 + 8, ly)
}

function flow(c: Canvas, visual: Of<'flow'>, y: number) {
	const size = 14
	visual.layers.forEach((names, level) => {
		if (level > 0) {
			const mid = SVG_WIDTH / 2
			c.raw(`<path d="M${mid} ${y + 2} V${y + 18} M${mid - 5} ${y + 12} L${mid} ${y + 18} L${mid + 5} ${y + 12}" stroke="${c.ink.muted}" stroke-width="1.5" fill="none"/>`)
			y += 24
		}
		const color = colorOf(level)
		// Boxes of one layer side by side, wrapping onto more rows when they don't fit.
		const boxes = names.map((name) => ({ name, width: Math.min(widthOf(name, size) + 28, INNER) }))
		const rows: (typeof boxes)[] = [[]]
		for (const box of boxes) {
			const row = rows[rows.length - 1]
			const used = row.reduce((sum, b) => sum + b.width + 8, 0)
			if (row.length && used + box.width > INNER) rows.push([box])
			else row.push(box)
		}
		for (const row of rows) {
			const total = row.reduce((sum, b) => sum + b.width, 0) + (row.length - 1) * 8
			let from = (SVG_WIDTH - total) / 2
			for (const box of row) {
				c.rect(from, y, box.width, 36, `rx="12" fill="${color}1f" stroke="${color}" stroke-opacity="0.6"`)
				c.text(c.rtl ? SVG_WIDTH - from - box.width / 2 : from + box.width / 2, y + 23, box.name, { size, center: true })
				from += box.width + 8
			}
			y += 36 + 8
		}
	})
	return y
}

function timeline(c: Canvas, visual: Of<'timeline'>, y: number) {
	const top = y
	const under = c.parts.length
	const ends: number[] = []
	visual.events.forEach((event, i) => {
		c.raw(`<circle cx="${c.x(PAD + 7)}" cy="${y + 10}" r="6" fill="${colorOf(i)}"/>`)
		ends.push(y + 10)
		let next = c.block(PAD + 24, y, event.time, INNER - 24, { size: 12, fill: '#2563eb' })
		next = c.block(PAD + 24, next, event.title, INNER - 24, { weight: 700 })
		if (event.detail.trim()) next = c.block(PAD + 24, next, event.detail, INNER - 24, { size: 13, fill: c.ink.muted })
		y = next + 14
	})
	// The line goes under the dots.
	if (ends.length > 1) c.parts.splice(under, 0, `<line x1="${c.x(PAD + 7)}" y1="${top + 10}" x2="${c.x(PAD + 7)}" y2="${ends[ends.length - 1]}" stroke="${c.ink.line}" stroke-width="2"/>`)
	return y
}

/** Paper can't click: every node with where its choices lead. */
function tree(c: Canvas, visual: Of<'tree'>, y: number) {
	for (const node of Object.values(visual.nodes)) {
		y = c.block(PAD, y, node.title, INNER, { weight: 700 })
		if (node.body.trim()) y = c.block(PAD, y, node.body, INNER, { size: 13, fill: c.ink.muted })
		for (const [label, target] of node.choices) y = c.block(PAD + 14, y, `${label} – ${visual.nodes[target]?.title ?? target}`, INNER - 14, { size: 13 })
		y += 10
	}
	return y
}

function compare(c: Canvas, visual: Of<'compare'>, y: number) {
	visual.rows.forEach((row, i) => {
		const color = colorOf(i)
		// The text first, on its own layer, so the card under it can be sized to it.
		const text = new Canvas(c.rtl, c.ink)
		let bottom = text.block(PAD + 16, y + 6, row[0], INNER - 24, { weight: 700, fill: color })
		row.slice(1).forEach((cell, col) => {
			if (!cell.trim()) return
			bottom = text.block(PAD + 16, bottom + 2, visual.header[col + 1], INNER - 24, { size: 12, fill: c.ink.muted })
			bottom = text.block(PAD + 16, bottom, cell, INNER - 24)
		})
		bottom += 10
		c.rect(PAD, y, INNER, bottom - y, `rx="10" fill="${c.ink.line}" fill-opacity="0.35"`)
		c.rect(PAD, y, 4, bottom - y, `fill="${color}"`)
		c.parts.push(...text.parts)
		y = bottom + 10
	})
	return y
}

/** The visual as an SVG document, and its size in CSS pixels. */
export function visualToSvg(visual: Visual, ink: SvgInk = LIGHT_INK): { svg: string; width: number; height: number } {
	const c = new Canvas(visualDirection(visual) === 'rtl', ink)
	let y = PAD
	if (visual.title.trim()) y = c.block(PAD, y - 4, visual.title, INNER, { size: 16, weight: 700 }) + 12
	switch (visual.kind) {
		case 'chart':
			y = visual.pie ? donut(c, visual, y) : bars(c, visual, y)
			break
		case 'flow':
			y = flow(c, visual, y)
			break
		case 'timeline':
			y = timeline(c, visual, y)
			break
		case 'tree':
			y = tree(c, visual, y)
			break
		case 'compare':
			y = compare(c, visual, y)
			break
	}
	const height = Math.ceil(y + PAD - 8)
	const frame = `<rect x="0.5" y="0.5" width="${SVG_WIDTH - 1}" height="${height - 1}" rx="14" fill="${ink.card}" stroke="${ink.line}"/>`
	const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${SVG_WIDTH}" height="${height}" style="max-width:100%;height:auto;direction:ltr" viewBox="0 0 ${SVG_WIDTH} ${height}" font-family="${esc(FONT)}" role="img" aria-label="${esc(visual.title)}">${frame}${c.parts.join('')}</svg>`
	return { svg, width: SVG_WIDTH, height }
}
