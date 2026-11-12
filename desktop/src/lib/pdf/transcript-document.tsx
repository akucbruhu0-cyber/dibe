import { Document, Page, StyleSheet, Text, View } from '@react-pdf/renderer'
import { parseMarkdown, type Run } from '~/lib/markdown-blocks'
import { markedVisual, withVisualMarks, type Visual } from '~/lib/visuals'
import { PdfVisual } from './pdf-visual'
import type { Segment } from '~/lib/transcript'
import { exportedThread, includesSummary, includesTranscript, segmentMetadata, type TranscriptExportOptions } from '~/lib/transcript-export'

/** Registered by `registerPdfFonts` in the app, and from disk in the layout tests. */
export const PDF_FONT = 'Rubik'

/** The same palettes the HTML export uses, so the file matches the preview beside it; the accent is the app's green. */
const PALETTES = {
	dark: { background: '#181818', ink: '#ececec', muted: '#a3a3a3', accent: '#8fd3a6', line: '#3a3a3a', tint: '#232823', bubble: '#a9ddb8' },
	light: { background: '#ffffff', ink: '#1a1c1f', muted: '#6e6e6e', accent: '#26794a', line: '#e3e6e3', tint: '#f1f7f2', bubble: '#bfe9cb' },
} as const

type Palette = (typeof PALETTES)[keyof typeof PALETTES]

/** Body text runs edge to edge, as in a printed book; a paragraph's last line keeps to its start side. */
const BODY = { fontSize: 11, lineHeight: 1.6 }

function sheet(palette: Palette, direction: 'rtl' | 'ltr') {
	const align = direction === 'rtl' ? ('right' as const) : ('left' as const)
	const start = direction === 'rtl' ? 'Right' : 'Left'
	return StyleSheet.create({
		page: { paddingTop: 56, paddingBottom: 60, paddingHorizontal: 60, backgroundColor: palette.background, fontFamily: PDF_FONT },
		title: { fontSize: 22, fontWeight: 700, color: palette.ink, textAlign: 'center', lineHeight: 1.3 },
		// A short green rule under the title, the document's one ornament.
		titleRule: { alignSelf: 'center', width: 48, borderBottomWidth: 2, borderBottomColor: palette.accent, marginTop: 10, marginBottom: 14 },
		// Transcript / Summary: the document's two parts, each opened by a hairline across the page.
		heading: {
			fontSize: 15,
			fontWeight: 700,
			color: palette.accent,
			textAlign: align,
			direction,
			marginTop: 26,
			paddingBottom: 5,
			borderBottomWidth: 1,
			borderBottomColor: palette.line,
		},
		block: { marginTop: 10 },
		// Timestamps read left to right whatever the transcript does — otherwise the bidi algorithm
		// takes "-->" for a neutral inside a right-to-left line and flips it. Only the alignment follows.
		metadata: { fontSize: 9, fontWeight: 700, color: palette.muted, textAlign: align, direction: 'ltr' as const, marginBottom: 3 },
		body: { ...BODY, color: palette.ink, textAlign: 'justify', direction },
		// The summary's own headings, largest first, all below the section heading above them.
		summaryHeading: { fontWeight: 700, color: palette.ink, textAlign: align, direction, marginTop: 18, marginBottom: 2, lineHeight: 1.3 },
		listItem: { flexDirection: direction === 'rtl' ? 'row-reverse' : 'row', marginTop: 5 },
		marker: { width: 18, ...BODY, fontWeight: 700, color: palette.accent, textAlign: align },
		box: { width: 9, height: 9, borderWidth: 1.2, borderColor: palette.accent, borderRadius: 2 },
		// A follow-up question in the app's light green, on the side a chat puts what you sent.
		bubble: {
			alignSelf: direction === 'rtl' ? 'flex-end' : 'flex-start',
			maxWidth: '85%',
			marginTop: 22,
			marginBottom: 2,
			paddingVertical: 8,
			paddingHorizontal: 14,
			borderRadius: 14,
			// The corner nearest the page edge stays tight, like a chat bubble's tail.
			[`borderTop${start}Radius`]: 4,
			backgroundColor: palette.bubble,
		},
		bubbleText: { fontSize: 11, lineHeight: 1.45, color: '#1a1c1f', textAlign: align, direction },
		quote: {
			...BODY,
			color: palette.muted,
			textAlign: 'justify',
			direction,
			marginTop: 12,
			[`padding${start}`]: 12,
			[`border${start}Width`]: 2,
			[`border${start}Color`]: palette.accent,
		},
		rule: { borderBottomWidth: 1, borderBottomColor: palette.line, marginVertical: 14 },
		// The footer would inherit the page's direction and print "31 / 7"; it stays left to right.
		footer: { position: 'absolute', bottom: 28, left: 60, right: 60, textAlign: 'center', fontSize: 8.5, color: palette.muted, direction: 'ltr' },
	})
}

/**
 * Bidi isolates and marks are invisible in a browser, but a PDF font has no glyph for them and
 * draws a tofu box instead. react-pdf resolves the direction of each run itself, so they can go.
 */
function plain(text: string) {
	return text.replace(/[\u061c\u200e\u200f\u202a-\u202e\u2066-\u2069]/g, '')
}

const SUMMARY_HEADING_SIZES = [14.5, 12.5, 11.5]

/** A task item's `[ ]` or `[x]`, which the Markdown reader leaves in the text. */
const TASK = /^\[([ xX])\]\s+/

/**
 * Bold runs in bold; italics and code read as plain text: the bundled font has no italic face. Each
 * run carries the direction too: without it react-pdf resolves a run on its own, and a sentence's
 * final period lands at the wrong end of a right-to-left line.
 */
function RunsText({ runs, direction }: { runs: Run[]; direction: 'rtl' | 'ltr' }) {
	return runs.map((run, index) => (
		// Only bold is set: a plain run inherits its block's weight, so a heading stays bold.
		<Text key={index} style={run.bold ? { fontWeight: 700, direction } : { direction }}>
			{plain(run.text)}
		</Text>
	))
}

/** An AI summary (Markdown) as headings, paragraphs, lists, quotes and rules, with its visuals drawn. */
function SummaryBlocks({ summary, styles, direction, ink }: { summary: string; styles: ReturnType<typeof sheet>; direction: 'rtl' | 'ltr'; ink: Palette }) {
	const indent = direction === 'rtl' ? 'paddingRight' : 'paddingLeft'
	const { text, visuals } = withVisualMarks(summary)
	return parseMarkdown(text).map((block, index) => {
		const visual: Visual | null = block.type === 'paragraph' ? markedVisual(block.runs.map((run) => run.text).join(''), visuals) : null
		if (visual) return <PdfVisual key={index} visual={visual} ink={ink} fallback={direction} />
		switch (block.type) {
			case 'heading':
				return (
					// A heading never ends a page alone: it moves to the next page with what it introduces.
					<Text
						key={index}
						minPresenceAhead={60}
						style={[
								styles.summaryHeading,
								{ fontSize: SUMMARY_HEADING_SIZES[Math.min(block.rank, SUMMARY_HEADING_SIZES.length - 1)] },
								block.rank === 0 ? { color: ink.accent } : {},
							]}>
						<RunsText runs={block.runs} direction={direction} />
					</Text>
				)
			case 'rule':
				return <View key={index} style={styles.rule} />
			case 'quote':
				return (
					<Text key={index} style={styles.quote}>
						<RunsText runs={block.runs} direction={direction} />
					</Text>
				)
			case 'bullet':
			case 'number': {
				// `- [ ] task` draws as a box, ticked when done: the font has no ballot-box glyph.
				const task = block.type === 'bullet' ? TASK.exec(block.runs[0]?.text ?? '') : null
				const runs = task ? [{ ...block.runs[0], text: block.runs[0].text.slice(task[0].length) }, ...block.runs.slice(1)] : block.runs
				return (
					<View key={index} style={[styles.listItem, { [indent]: block.depth * 14 }]} wrap={false}>
						{task ? (
							<View style={{ width: 18, paddingTop: 5, alignItems: direction === 'rtl' ? 'flex-end' : 'flex-start' }}>
								<View style={[styles.box, task[1] !== ' ' ? { backgroundColor: ink.accent } : {}]} />
							</View>
						) : (
							<Text style={styles.marker}>{block.type === 'bullet' ? '•' : block.marker}</Text>
						)}
						<Text style={[styles.body, { flex: 1 }]}>
							<RunsText runs={runs} direction={direction} />
						</Text>
					</View>
				)
			}
			default:
				return (
					<View key={index} style={styles.block}>
						<Text style={styles.body}>
							<RunsText runs={block.runs} direction={direction} />
						</Text>
					</View>
				)
		}
	})
}

export interface TranscriptDocumentProps {
	segments: Segment[]
	summary: string
	options: TranscriptExportOptions
	labels: { transcript: string; summary: string }
}

/**
 * The exported transcript.
 *
 * Bidi is react-pdf's job here: its layout engine resolves embedding levels with the Unicode
 * algorithm, breaks lines in logical order and reorders each finished line — which is why the
 * Hebrew reads right to left while the English words, numbers and brackets inside it do not flip.
 */
export function TranscriptDocument({ segments, summary, options, labels }: TranscriptDocumentProps) {
	const palette = PALETTES[options.theme === 'dark' ? 'dark' : 'light']
	const styles = sheet(palette, options.direction)
	const title = options.title.trim()
	const both = options.content === 'both'
	const showTranscript = includesTranscript(options.content)
	const showSummary = includesSummary(options.content) && Boolean(summary.trim())
	const thread = exportedThread(options)

	return (
		<Document title={title || 'Transcript'}>
			<Page size="A4" style={styles.page}>
				{title && <Text style={styles.title}>{plain(title)}</Text>}
				{title && <View style={styles.titleRule} />}

				{showTranscript && both && <Text style={styles.heading}>{plain(labels.transcript)}</Text>}
				{showTranscript &&
					segments.map((segment, index) => {
						const text = segment.text.trim()
						if (!text) return null
						const metadata = segmentMetadata(segment, options)
						return (
							<View key={index} style={styles.block} wrap>
								{metadata && <Text style={styles.metadata}>{plain(metadata)}</Text>}
								<Text style={styles.body}>{plain(text)}</Text>
							</View>
						)
					})}

				{showSummary && both && <Text style={styles.heading}>{plain(labels.summary)}</Text>}
				{showSummary && <SummaryBlocks summary={summary} styles={styles} direction={options.direction} ink={palette} />}

					{thread.length > 0 && <Text style={styles.heading}>{plain(options.threadLabel ?? 'Follow-up questions')}</Text>}
					{thread.map((entry, index) => (
						<View key={index}>
							{/* The question moves to the next page with the start of its answer. */}
							<View style={styles.bubble} wrap={false} minPresenceAhead={40}>
								<Text style={styles.bubbleText}>{plain(entry.question.trim())}</Text>
							</View>
							<SummaryBlocks summary={entry.answer} styles={styles} direction={options.direction} ink={palette} />
						</View>
					))}

				<Text style={styles.footer} fixed render={({ pageNumber, totalPages }) => `${pageNumber} / ${totalPages}`} />
			</Page>
		</Document>
	)
}
