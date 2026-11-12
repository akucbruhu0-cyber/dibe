import type { TextFormat } from '~/components/format-select'
import { meetingFrontmatter, type Meeting } from '~/lib/calendar'
import { markdownToHtml, markdownToText } from '~/lib/markdown-blocks'
import { DARK_INK, LIGHT_INK, visualToSvg } from '~/lib/visual-svg'
import { withVisualMarks } from '~/lib/visuals'
import { formatTimestamp, speakerName, type Segment, type SpeakerNames } from '~/lib/transcript'
import type { AiThreadEntry } from '~/lib/transcripts-store'

export type TranscriptExportContent = 'transcript' | 'summary' | 'both'

export interface TranscriptExportOptions {
	content: TranscriptExportContent
	showTimestamps: boolean
	showSpeakers: boolean
	speakerLabel: string
	/** Names the user gave the speakers; unnamed ones fall back to "<speakerLabel> N". */
	speakerNames?: SpeakerNames
	title: string
	/** Markdown gets it as frontmatter (date, attendees) for Obsidian and other notes apps. */
	meeting?: Meeting
	direction: 'rtl' | 'ltr'
	theme?: 'dark' | 'light'
	/** Follow-up questions and their answers, after the summary; only exported with the summary. */
	thread?: AiThreadEntry[]
	/** Their heading, in the app's language. */
	threadLabel?: string
}

export function includesTranscript(content: TranscriptExportContent) {
	return content !== 'summary'
}

export function includesSummary(content: TranscriptExportContent) {
	return content !== 'transcript'
}

/** The follow-up questions this export carries: none unless the summary goes too. */
export function exportedThread(options: TranscriptExportOptions): AiThreadEntry[] {
	return includesSummary(options.content) ? (options.thread ?? []) : []
}

/** Follow-up questions as Markdown after the summary, for every format that carries text rather than drawing it. */
function threadMarkdown(options: TranscriptExportOptions) {
	const thread = exportedThread(options)
	if (!thread.length) return ''
	return [`## ${options.threadLabel ?? 'Follow-up questions'}`, ...thread.map((entry) => `> **${entry.question.trim()}**\n\n${entry.answer.trim()}`)].join('\n\n')
}

function timestamp(start: number, stop: number, decimalMarker = '.', alwaysIncludeHours = false) {
	return `${formatTimestamp(start, alwaysIncludeHours, decimalMarker)} --> ${formatTimestamp(stop, alwaysIncludeHours, decimalMarker)}`
}

function speaker(segment: Segment, options: TranscriptExportOptions) {
	return options.showSpeakers && segment.speaker != null ? speakerName(segment.speaker, options.speakerLabel, options.speakerNames) : ''
}

/** The name a user chose for a speaker, when they chose one — machine formats carry it beside the index. */
function chosenSpeakerName(segment: Segment, options: TranscriptExportOptions) {
	const name = segment.speaker != null ? options.speakerNames?.[segment.speaker]?.trim() : undefined
	return name || undefined
}

export function segmentMetadata(segment: Segment, options: TranscriptExportOptions) {
	const time = options.showTimestamps ? timestamp(segment.start, segment.stop) : ''
	const directionalTime = time && options.direction === 'rtl' ? `\u2066${time}\u2069` : time
	return [directionalTime, speaker(segment, options)].filter(Boolean).join(' · ')
}

function sectioned(transcript: string, summary: string, options: TranscriptExportOptions, heading: (value: string) => string) {
	if (options.content === 'transcript') return transcript
	if (options.content === 'summary') return summary
	return `${heading('Transcript')}\n\n${transcript}\n\n${heading('Summary')}\n\n${summary}`
}

/** A summary in a subtitle file: plain, and without the blank lines that would end its note. */
function subtitleNote(summary: string) {
	return `NOTE Summary\n${markdownToText(summary).replace(/\n{2,}/g, '\n')}`
}

/** The AI writes summaries in Markdown; plain text gets its words, not its marks. */
function serializeText(segments: Segment[], summary: string, options: TranscriptExportOptions) {
	summary = markdownToText(summary)
	const transcript = segments
		.map((segment) => {
			const metadata = segmentMetadata(segment, options)
			return `${metadata ? `${metadata}\n` : ''}${segment.text.trim()}`
		})
		.join('\n\n')
	return sectioned(transcript, summary, options, (heading) => heading)
}

function serializeMarkdown(segments: Segment[], summary: string, options: TranscriptExportOptions) {
	const transcript = segments
		.map((segment) => {
			const metadata = segmentMetadata(segment, options)
			return `${metadata ? `**${metadata}**\n\n` : ''}${segment.text.trim()}`
		})
		.join('\n\n')
	const content = sectioned(transcript, summary, options, (heading) => `## ${heading}`)
	const body = options.title.trim() ? `# ${options.title.trim()}\n\n${content}` : content
	return options.meeting ? meetingFrontmatter(options.meeting) + body : body
}

function subtitleText(segment: Segment, options: TranscriptExportOptions) {
	const prefix = speaker(segment, options)
	return `${prefix ? `[${prefix}] ` : ''}${segment.text.trim().replace(/-->/g, '->')}`
}

function serializeSrt(segments: Segment[], summary: string, options: TranscriptExportOptions) {
	const cues = includesTranscript(options.content)
		? segments.map((segment, index) => `${index + 1}\n${timestamp(segment.start, segment.stop, ',', true)}\n${subtitleText(segment, options)}`).join('\n\n')
		: ''
	const summaryBlock = includesSummary(options.content) ? subtitleNote(summary) : ''
	return [cues, summaryBlock].filter(Boolean).join('\n\n')
}

function serializeVtt(segments: Segment[], summary: string, options: TranscriptExportOptions) {
	const cues = includesTranscript(options.content)
		? segments.map((segment) => `${timestamp(segment.start, segment.stop)}\n${subtitleText(segment, options)}`).join('\n\n')
		: ''
	const summaryBlock = includesSummary(options.content) ? subtitleNote(summary) : ''
	return `WEBVTT\n\n${[cues, summaryBlock].filter(Boolean).join('\n\n')}`
}

function escapeHtml(value: string) {
	return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;')
}

function htmlText(value: string) {
	return escapeHtml(value).replace(/\r?\n/g, '<br>')
}

/** Markdown as HTML, each visual drawn where its marker paragraph lands, as an inline SVG. */
function markdownSectionHtml(markdown: string, level: number, dark: boolean) {
	const { text, visuals } = withVisualMarks(markdown)
	return markdownToHtml(text, level).replace(
		/<p>@@dibe-visual-(\d+)@@<\/p>/g,
		(_, index: string) => `<figure class="visual">${visualToSvg(visuals[Number(index)], dark ? DARK_INK : LIGHT_INK).svg}</figure>`,
	)
}

function serializeHtml(segments: Segment[], summary: string, options: TranscriptExportOptions) {
	const dark = options.theme === 'dark'
	// `bubble` is the app's light green, behind each follow-up question as in a chat.
	const palette = dark
		? { background: '#181818', foreground: '#ececec', muted: '#a3a3a3', primary: '#8fd3a6', border: '#303030', bubble: '#a9ddb8' }
		: { background: '#ffffff', foreground: '#1a1c1f', muted: '#6e6e6e', primary: '#26794a', border: '#e6e6e6', bubble: '#bfe9cb' }
	const transcript = segments
		.map((segment) => {
			const metadata = segmentMetadata(segment, options)
			return `<article class="segment">${metadata ? `<div class="metadata">${escapeHtml(metadata)}</div>` : ''}<p>${htmlText(segment.text.trim())}</p></article>`
		})
		.join('\n')
	const sections: string[] = []
	if (includesTranscript(options.content)) {
		sections.push(`<section class="transcript">${options.content === 'both' ? '<h2>Transcript</h2>' : ''}${transcript}</section>`)
	}
	if (includesSummary(options.content)) {
		// Under the page's own "Summary" heading when there is one, so the levels stay in order.
		const both = options.content === 'both'
		const body = markdownSectionHtml(summary, both ? 3 : 2, dark)
		sections.push(`<section class="summary">${both ? '<h2>Summary</h2>' : ''}${body}</section>`)
	}
	const thread = exportedThread(options)
	if (thread.length) {
		const entries = thread.map(
			(entry) => `<div class="question">${htmlText(entry.question.trim())}</div><div class="answer summary">${markdownSectionHtml(entry.answer, 3, dark)}</div>`,
		)
		sections.push(`<section class="thread"><h2>${escapeHtml(options.threadLabel ?? 'Follow-up questions')}</h2>${entries.join('\n')}</section>`)
	}
	const title = options.title.trim()
	return [
		'<!doctype html>',
		`<html dir="${options.direction}">`,
		'<head>',
		'<meta charset="utf-8">',
		`<meta name="color-scheme" content="${dark ? 'dark' : 'light'}">`,
		`<title>${escapeHtml(title)}</title>`,
		`<style>:root{color-scheme:${dark ? 'dark' : 'light'}}html,body{min-height:100%;margin:0;background:${palette.background};color:${palette.foreground}}.html{box-sizing:border-box;min-height:100vh;font-family:Roboto,Arial,sans-serif;max-width:1000px;margin:auto;padding:22px;line-height:1.5;background:${palette.background};color:${palette.foreground}}.html h1{text-align:center;color:${palette.primary}}.html h2{border-bottom:1px solid ${palette.border};padding-bottom:.35rem}.html .segment{margin-top:18px}.html .metadata{font-size:.8rem;font-weight:600;color:${palette.muted}}.html .segment p,.html .summary p{white-space:normal}.html .summary hr{border:0;border-top:1px solid ${palette.border};margin:1.25rem 0}.html .summary blockquote{margin:0;padding-inline-start:1rem;border-inline-start:3px solid ${palette.border};color:${palette.muted}}.html .summary ul,.html .summary ol{padding-inline-start:1.5rem}.html .visual{margin:1.25rem 0}.html .thread .question{width:fit-content;max-width:85%;margin:1.75rem 0 .5rem;padding:.6rem 1rem;border-radius:18px;border-start-start-radius:4px;background:${palette.bubble};color:#1a1c1f}.html .visual svg{display:block;max-width:100%;height:auto}</style>`,
		'</head>',
		'<body>',
		`<main class="html printable" dir="${options.direction}">`,
		title ? `<h1>${escapeHtml(title)}</h1>` : '',
		...sections,
		'</main>',
		'</body>',
		'</html>',
	]
		.filter(Boolean)
		.join('\n')
}

function serializeJson(segments: Segment[], summary: string, options: TranscriptExportOptions) {
	const output: { title?: string; direction: 'rtl' | 'ltr'; transcript?: object[]; summary?: string } = {
		direction: options.direction,
	}
	if (options.title.trim()) output.title = options.title.trim()
	if (includesTranscript(options.content)) {
		output.transcript = segments.map((segment) => {
			const row: { start?: number; stop?: number; speaker?: number; speakerName?: string; text: string } = { text: segment.text.trim() }
			if (options.showTimestamps) {
				row.start = segment.start / 100
				row.stop = segment.stop / 100
			}
			if (options.showSpeakers && segment.speaker != null) {
				row.speaker = segment.speaker + 1
				const name = chosenSpeakerName(segment, options)
				if (name) row.speakerName = name
			}
			return row
		})
	}
	if (includesSummary(options.content)) output.summary = summary
	return JSON.stringify(output, null, 2)
}

function escapeCsv(value: string | number) {
	return `"${String(value).replace(/"/g, '""')}"`
}

function serializeCsv(segments: Segment[], summary: string, options: TranscriptExportOptions) {
	const header = ['content', ...(options.showTimestamps ? ['start', 'end'] : []), ...(options.showSpeakers ? ['speaker'] : []), 'text']
	const rows: Array<Array<string | number>> = []
	if (includesTranscript(options.content)) {
		for (const segment of segments) {
			rows.push([
				'transcript',
				...(options.showTimestamps ? [formatTimestamp(segment.start, true, '.'), formatTimestamp(segment.stop, true, '.')] : []),
				...(options.showSpeakers ? [segment.speaker != null ? (chosenSpeakerName(segment, options) ?? segment.speaker + 1) : ''] : []),
				segment.text.trim(),
			])
		}
	}
	if (includesSummary(options.content)) {
		rows.push(['summary', ...(options.showTimestamps ? ['', ''] : []), ...(options.showSpeakers ? [''] : []), summary])
	}
	return [header.join(','), ...rows.map((row) => row.map(escapeCsv).join(','))].join('\n')
}

function serializeDocumentPreview(segments: Segment[], summary: string, options: TranscriptExportOptions) {
	const content = serializeText(segments, summary, options)
	return [options.title.trim(), content].filter(Boolean).join('\n\n')
}

export function serializeTranscriptExport(format: TextFormat, segments: Segment[], summary: string | undefined, options: TranscriptExportOptions): string {
	// HTML draws the questions in bubbles; every other format carries them as text after the summary.
	const normalizedSummary = [summary?.trim() ?? '', format === 'html' ? '' : threadMarkdown(options)].filter(Boolean).join('\n\n')
	switch (format) {
		case 'md':
			return serializeMarkdown(segments, normalizedSummary, options)
		case 'srt':
			return serializeSrt(segments, normalizedSummary, options)
		case 'vtt':
			return serializeVtt(segments, normalizedSummary, options)
		case 'html':
			return serializeHtml(segments, normalizedSummary, options)
		case 'json':
			return serializeJson(segments, normalizedSummary, options)
		case 'csv':
			return serializeCsv(segments, normalizedSummary, options)
		case 'docx':
		case 'pdf':
			return serializeDocumentPreview(segments, normalizedSummary, options)
		case 'normal':
			return serializeText(segments, normalizedSummary, options)
	}
}
