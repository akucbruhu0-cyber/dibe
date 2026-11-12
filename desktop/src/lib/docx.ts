import { AlignmentType, BorderStyle, Document, HeadingLevel, ImageRun, Packer, Paragraph, ShadingType, TextRun } from 'docx'
import { formatDuration } from '~/components/html-view'
import { parseMarkdown, type Run } from './markdown-blocks'
import { markedVisual, withVisualMarks, type Visual } from './visuals'
import { speakerName, type Segment, type SpeakerNames } from './transcript'
import type { AiThreadEntry } from './transcripts-store'

export interface DocxExportOptions {
	content?: 'transcript' | 'summary' | 'both'
	showTimestamps?: boolean
	showSpeakers?: boolean
	speakerNames?: SpeakerNames
	summary?: string
	transcriptLabel?: string
	summaryLabel?: string
	/** Follow-up questions and their answers, after the summary. */
	thread?: AiThreadEntry[]
	threadLabel?: string
	/** Draws a summary's visual as a PNG (the app rasterizes its SVG); without it visuals are written out as lists. */
	drawVisual?: (visual: Visual) => Promise<DrawnVisual>
}

export interface DrawnVisual {
	png: Uint8Array
	/** CSS pixels. */
	width: number
	height: number
}

/** The widest a picture goes on an A4/Letter page with Word's default margins, in pixels at 96 dpi. */
const PAGE_WIDTH_PX = 600

/**
 * Word documents get edited, printed and mailed on, so this one is set for paper: an ordinary
 * light page whatever the app's theme is, and a font that exists on every machine that will open
 * it — including for the Hebrew and Arabic a transcript may carry.
 */
const FONT = 'Arial'

/** Sizes are half-points, the unit Word stores. */
const TITLE_SIZE = 44
const HEADING_SIZE = 26
const BODY_SIZE = 22
const METADATA_SIZE = 18

const INK = '1A1C1F'
const MUTED = '6E6E6E'
const ACCENT = '1D4ED8'

/** The summary's own headings, largest first, all smaller than the section heading above them. */
const SUMMARY_HEADINGS = [
	{ level: HeadingLevel.HEADING_2, size: 26 },
	{ level: HeadingLevel.HEADING_3, size: 24 },
	{ level: HeadingLevel.HEADING_4, size: 22 },
]
/** Twips per list level, and the room its bullet or number hangs in. */
const LIST_INDENT = 360

/** Twips: 20 per point, so a line of 1.15 at 11pt is 276. */
const LINE = 276
const PARAGRAPH_GAP = 200
const SECTION_GAP = 360

/**
 * An AI summary (Markdown) as Word paragraphs: real headings, bold and italic runs, lists, quotes and
 * rules, instead of the `###`, `**` and `---` the text is written with.
 */
async function summaryParagraphs(summary: string, isRtl: boolean, drawVisual?: DocxExportOptions['drawVisual']) {
	const marked = drawVisual ? withVisualMarks(summary) : { text: summary, visuals: [] }
	const drawn = new Map<Visual, DrawnVisual>()
	for (const visual of marked.visuals) drawn.set(visual, await drawVisual!(visual))
	const alignment = isRtl ? AlignmentType.RIGHT : AlignmentType.LEFT
	const runs = (items: Run[], extra: { size?: number; bold?: boolean; italics?: boolean; color?: string } = {}) =>
		items.map(
			(run) =>
				new TextRun({
					text: run.text,
					bold: extra.bold || run.bold,
					italics: extra.italics || run.italic,
					size: extra.size ?? BODY_SIZE,
					color: extra.color ?? INK,
					rightToLeft: isRtl,
					...(run.code ? { font: 'Courier New' } : {}),
				}),
		)
	return parseMarkdown(marked.text).map((block) => {
		const base = { alignment, bidirectional: isRtl, spacing: { line: LINE, after: PARAGRAPH_GAP } }
		const visual = block.type === 'paragraph' ? markedVisual(block.runs.map((run) => run.text).join(''), marked.visuals) : null
		const picture = visual && drawn.get(visual)
		if (picture) {
			const scale = Math.min(1, PAGE_WIDTH_PX / picture.width)
			return new Paragraph({
				alignment: AlignmentType.CENTER,
				spacing: { before: 120, after: PARAGRAPH_GAP },
				keepLines: true,
				children: [new ImageRun({ type: 'png', data: picture.png, transformation: { width: Math.round(picture.width * scale), height: Math.round(picture.height * scale) } })],
			})
		}
		switch (block.type) {
			case 'heading': {
				const heading = SUMMARY_HEADINGS[Math.min(block.rank, SUMMARY_HEADINGS.length - 1)]
				return new Paragraph({
					...base,
					heading: heading.level,
					spacing: { before: 280, after: 100 },
					keepNext: true,
					children: runs(block.runs, { bold: true, size: heading.size }),
				})
			}
			case 'rule':
				return new Paragraph({
					...base,
					spacing: { after: PARAGRAPH_GAP },
					border: { bottom: { style: BorderStyle.SINGLE, size: 6, color: 'C8C8C8', space: 1 } },
					children: [],
				})
			case 'quote':
				return new Paragraph({ ...base, indent: { start: LIST_INDENT }, children: runs(block.runs, { italics: true, color: MUTED }) })
			case 'bullet':
			case 'number': {
				// The marker hangs in the indent, so wrapped lines line up under the text, not under it.
				const marker = block.type === 'bullet' ? '•' : block.marker
				return new Paragraph({
					...base,
					spacing: { line: LINE, after: 80 },
					indent: { start: LIST_INDENT * (block.depth + 1), hanging: LIST_INDENT },
					children: [new TextRun({ text: `${marker}\t`, size: BODY_SIZE, color: INK, rightToLeft: isRtl }), ...runs(block.runs)],
				})
			}
			default:
				return new Paragraph({ ...base, children: runs(block.runs) })
		}
	})
}

export async function toDocx(title: string, segments: Segment[], direction: 'rtl' | 'ltr', speakerLabel: string = 'Speaker', options: DocxExportOptions = {}) {
	const isRtl = direction === 'rtl'
	const alignment = isRtl ? AlignmentType.RIGHT : AlignmentType.LEFT
	const content = options.content ?? 'transcript'
	const showTimestamps = options.showTimestamps ?? true
	const showSpeakers = options.showSpeakers ?? true
	const includeTranscript = content !== 'summary'
	const includeSummary = content !== 'transcript' && Boolean(options.summary?.trim())

	const body = (text: string) =>
		new Paragraph({
			alignment,
			bidirectional: isRtl,
			spacing: { line: LINE, after: PARAGRAPH_GAP },
			children: [new TextRun({ text, size: BODY_SIZE, color: INK, rightToLeft: isRtl })],
		})

	const heading = (text: string) =>
		new Paragraph({
			heading: HeadingLevel.HEADING_1,
			alignment,
			bidirectional: isRtl,
			spacing: { before: SECTION_GAP, after: 120 },
			children: [new TextRun({ text, bold: true, size: HEADING_SIZE, color: INK, rightToLeft: isRtl })],
		})

	const paragraphs: Paragraph[] = []
	for (const segment of segments) {
		const text = segment.text.trim()
		if (!text) continue
		const metadata = [
			// Always the left-to-right form: the right-to-left embedding the view uses would turn
			// "00:00 --> 00:07" into "00:07 <-- 00:00" once Word resolves the line.
			showTimestamps ? formatDuration(segment.start, segment.stop, 'ltr') : '',
			showSpeakers && segment.speaker != null ? speakerName(segment.speaker, speakerLabel, options.speakerNames) : '',
		]
			.filter(Boolean)
			.join('   ')
		if (metadata) {
			paragraphs.push(
				new Paragraph({
					// Timestamps and speaker numbers read left to right whatever the transcript does;
					// only where the line sits on the page follows the text direction.
					alignment,
					bidirectional: false,
					// The label belongs to the line under it; Word keeps the pair on one page.
					keepNext: true,
					spacing: { before: PARAGRAPH_GAP, after: 40 },
					children: [new TextRun({ text: metadata, bold: true, size: METADATA_SIZE, color: MUTED })],
				}),
			)
		}
		paragraphs.push(body(text))
	}

	const summary = includeSummary ? await summaryParagraphs(options.summary ?? '', isRtl, options.drawVisual) : []
	// Each question on the app's light green, as in the chat it was asked in, then its answer.
	const thread: Paragraph[] = []
	for (const entry of content !== 'transcript' ? (options.thread ?? []) : []) {
		thread.push(
			new Paragraph({
				alignment,
				bidirectional: isRtl,
				keepNext: true,
				shading: { type: ShadingType.CLEAR, color: 'auto', fill: 'BFE9CB' },
				spacing: { before: 320, after: 120, line: LINE },
				children: [new TextRun({ text: entry.question.trim(), size: BODY_SIZE, color: INK, rightToLeft: isRtl })],
			}),
			...(await summaryParagraphs(entry.answer, isRtl, options.drawVisual)),
		)
	}
	const doc = new Document({
		title,
		description: 'Transcript exported from DIBE',
		styles: {
			// Set on the document's default run so every paragraph, and anything the reader types
			// afterwards, inherits the same face rather than Word's own default.
			default: {
				document: {
					run: { font: { ascii: FONT, hAnsi: FONT, cs: FONT }, size: BODY_SIZE, color: INK },
					paragraph: { spacing: { line: LINE } },
				},
			},
		},
		sections: [
			{
				children: [
					new Paragraph({
						alignment: AlignmentType.CENTER,
						bidirectional: isRtl,
						spacing: { after: SECTION_GAP },
						children: [new TextRun({ text: title, bold: true, size: TITLE_SIZE, color: ACCENT, rightToLeft: isRtl })],
					}),
					...(includeTranscript ? [...(content === 'both' ? [heading(options.transcriptLabel ?? 'Transcript')] : []), ...paragraphs] : []),
					...(includeSummary
						? [...(content === 'both' ? [heading(options.summaryLabel ?? 'Summary')] : []), ...summary]
						: []),
						...(thread.length ? [heading(options.threadLabel ?? 'Follow-up questions'), ...thread] : []),
				],
			},
		],
	})

	return Packer.toBlob(doc)
}
