import { describe, expect, it } from 'vitest'
import { markedVisual, mergeVisuals, parseVisual, withVisualMarks, visualDirection, visualKind, visualsApply, visualsAsMarkdown, type Visual } from './visuals'
import { parseMarkdown } from './markdown-blocks'

/** The same cases as android/.../VisualTest.kt, so both apps read a summary the same way. */
describe('visuals', () => {
	it('reads chart rows, colors and loose numbers', () => {
		const chart = parseVisual('chart', 'type: pie\ntitle: "גישות"\nלוגית | 20 | #007AFF\nמקודדת | 30%\nפרגמטית | 50\nשבור | אין מספר') as Extract<Visual, { kind: 'chart' }>
		expect(chart.pie).toBe(true)
		expect(chart.title).toBe('גישות')
		expect(chart.rows.map((row) => row.value)).toEqual([20, 30, 50])
		expect(chart.rows[0].color).toBe('#007AFF')
		expect(parseVisual('chart', 'title: ריק\nאין | כלום')).toBeNull()
	})

	it('unifies shortened flow names and layers branches', () => {
		const flow = parseVisual('flow', 'title: אבולוציה\nתגובה פיזיולוגית -> הבעת רגש (פחד/הפתעה)\nהבעת רגש -> ביטוי כנות\nהבעת רגש -> סמן העצמה')
		expect(flow).toMatchObject({ layers: [['תגובה פיזיולוגית'], ['הבעת רגש (פחד/הפתעה)'], ['ביטוי כנות', 'סמן העצמה']] })
		expect((parseVisual('flow', 'A -> B\nB -> A') as Extract<Visual, { kind: 'flow' }>).layers.flat()).toHaveLength(2)
	})

	it('reads trees, timelines and comparisons', () => {
		const tree = parseVisual('tree', 'start::מהו המוקד?\n- תיאוריה => t\n- שטח => e\n\nt::סקירה\nהשוואת מודלים.\n\ne::ראיונות') as Extract<Visual, { kind: 'tree' }>
		expect(tree.start).toBe('start')
		expect(tree.nodes.start.choices).toEqual([
			['תיאוריה', 't'],
			['שטח', 'e'],
		])
		expect(tree.nodes.t.body).toBe('השוואת מודלים.')
		expect(parseVisual('timeline', 'שבועיים | גיבוש | בחירת מאמרים\nסוף הסמסטר | הגשה')).toMatchObject({ events: [{}, { time: 'סוף הסמסטר', title: 'הגשה', detail: '' }] })
		const compare = parseVisual('compare', '| גישה | רעיון |\n|---|---|\n| מינימליסטית | לוגיקה |\n| פרגמטית |')
		expect(compare).toMatchObject({ header: ['גישה', 'רעיון'], rows: [['מינימליסטית', 'לוגיקה'], ['פרגמטית', '']] })
	})

	it('knows a visual fence and its direction', () => {
		expect(visualKind('language-dibe:chart')).toBe('chart')
		expect(visualKind('dibe-flow')).toBe('flow')
		expect(visualKind('python')).toBeNull()
		expect(visualDirection(parseVisual('chart', 'title: 2026\nעברית | 1')!)).toBe('rtl')
		expect(visualDirection(parseVisual('chart', 'English | 1')!)).toBe('ltr')
	})

	it('places each visual at the end of its section', () => {
		const summary = 'פתיחה.\n\n### גישות מרכזיות\n\n- אחת\n\n### **תובנות**\n\n- שתיים'
		const answer =
			'Sure!\n```dibe:chart\nafter: גישות מרכזיות\ntitle: ה\nא | 1\n```\n```dibe:flow\nafter: תובנות\nא -> ב\n```\n```dibe:timeline\nafter: אין כזו\nמחר | משהו\n```'
		const merged = mergeVisuals(summary, answer)
		const chart = merged.indexOf('```dibe:chart')
		expect(chart).toBeGreaterThan(merged.indexOf('- אחת'))
		expect(chart).toBeLessThan(merged.indexOf('### **תובנות**'))
		expect(merged.indexOf('```dibe:flow')).toBeGreaterThan(merged.indexOf('- שתיים'))
		expect(merged.endsWith('מחר | משהו\n```')).toBe(true)
		expect(merged).not.toContain('after:')
		expect(merged).not.toContain('Sure!')
		// A block still being written is left out until it closes.
		expect(mergeVisuals(summary, '```dibe:chart\nafter: תובנות\nא | 1')).toBe(summary)
	})

	it('writes visuals out for exports, and splits them for drawing', () => {
		const summary = 'לפני\n```dibe:chart\ntype: pie\ntitle: ח\nא | 1\nב | 3\n```\nאחרי'
		expect(visualsAsMarkdown(summary)).toBe('לפני\n**ח**\n- א: 1 (25%)\n- ב: 3 (75%)\nאחרי')
		expect(parseMarkdown(summary).map((block) => block.type)).toEqual(['paragraph', 'bullet', 'bullet'])
		const marked = withVisualMarks(summary)
		expect(marked.visuals).toHaveLength(1)
		const blocks = parseMarkdown(marked.text)
		expect(blocks.map((block) => block.type)).toEqual(['paragraph', 'paragraph', 'paragraph'])
		expect(blocks[1].type === 'paragraph' && markedVisual(blocks[1].runs.map((run) => run.text).join(''), marked.visuals)).toMatchObject({ kind: 'chart', pie: true })
	})

	it('skips the translate preset', () => {
		expect(visualsApply('Output only…\n\nTranslate this transcript into {language}')).toBe(false)
		expect(visualsApply('Write a concise summary of this transcript')).toBe(true)
	})
})

describe('visuals in exported files', () => {
	const summary = '### גישות\n\n```dibe:chart\ntype: pie\ntitle: התפלגות\nלוגית | 20\nפרגמטית | 80\n```\n\nסוף.'
	const options = { content: 'summary' as const, showTimestamps: false, showSpeakers: false, speakerLabel: 'S', title: 't', direction: 'rtl' as const }

	it('draws them as inline SVG in HTML, so the export preview shows them too', async () => {
		const { serializeTranscriptExport } = await import('./transcript-export')
		const html = serializeTranscriptExport('html', [], summary, options)
		expect(html).toContain('<figure class="visual"><svg')
		expect(html).toContain('התפלגות')
		expect(html).toContain('20%')
		expect(html).not.toContain('dibe-visual')
		expect(html).not.toContain('```')
		// Still the summary's own heading and closing line around it.
		expect(html.indexOf('גישות')).toBeLessThan(html.indexOf('<svg'))
		expect(html.indexOf('סוף.')).toBeGreaterThan(html.indexOf('</svg>'))
	})

	it('embeds them as pictures in Word', async () => {
		const { toDocx } = await import('./docx')
		const { unzipSync } = await import('fflate')
		const png = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])
		const blob = await toDocx('t', [], 'rtl', 'S', { content: 'summary', summary, drawVisual: async () => ({ png, width: 640, height: 300 }) })
		const files = unzipSync(new Uint8Array(await blob.arrayBuffer()))
		expect(Object.keys(files).some((name) => name.startsWith('word/media/') && name.endsWith('.png'))).toBe(true)
		const xml = new TextDecoder().decode(files['word/document.xml'])
		expect(xml).toContain('<w:drawing>')
		expect(xml).not.toContain('dibe-visual')
	})

	it('writes them out as lists in Word when nothing can draw them', async () => {
		const { toDocx } = await import('./docx')
		const { unzipSync } = await import('fflate')
		const blob = await toDocx('t', [], 'rtl', 'S', { content: 'summary', summary })
		const xml = new TextDecoder().decode(unzipSync(new Uint8Array(await blob.arrayBuffer()))['word/document.xml'])
		expect(xml).toContain('לוגית: 20 (20%)')
		expect(xml).not.toContain('<w:drawing>')
	})
})
