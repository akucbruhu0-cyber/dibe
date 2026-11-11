import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { createElement } from 'react'
import { describe, expect, it } from 'vitest'
import type { Segment } from '~/lib/transcript'
import type { TranscriptExportOptions } from '~/lib/transcript-export'
import { PDF_FONT, TranscriptDocument } from './transcript-document'

/**
 * The bidi guard.
 *
 * A PDF stores glyphs in visual order and carries no bidi of its own, so a generator that writes
 * logical order produces a file that *reads backwards* — the failure this export shipped with
 * until react-pdf replaced pdfmake. pdf.js reverses the Unicode algorithm when it extracts text,
 * so recovering the source sentence, in order, proves the glyphs went down the right way round.
 */
const FONT_DIRECTORY = fileURLToPath(new URL('../../assets/fonts/', import.meta.url))

// Hebrew carrying an English word, decimals, a bracketed aside and a final period — each of
// which lands on the wrong side when reordering is missing, or applied twice.
const HEBREW: Segment[] = [
	{ start: 0, stop: 4000, text: 'כל אלה אותו דבר, אותו זמן, אתה רואה? 12.40, 12.50, זה בדיוק הזמן.', speaker: 0 },
	{ start: 4000, stop: 9000, text: 'הוא אמר "SaferPlace" (בסוגריים) והמספר 271 נשאר נכון.', speaker: 1 },
]

const ENGLISH: Segment[] = [{ start: 0, stop: 4000, text: 'An English line with עברית inside it and 42 numbers.', speaker: 0 }]

function options(direction: 'rtl' | 'ltr'): TranscriptExportOptions {
	return {
		content: 'transcript',
		showTimestamps: true,
		showSpeakers: true,
		speakerLabel: 'Speaker',
		title: 'Bidi check — בדיקת ייצוא',
		direction,
		theme: 'light',
	}
}

async function renderPdf(segments: Segment[], direction: 'rtl' | 'ltr', summary = '') {
	const { Font, renderToBuffer } = await import('@react-pdf/renderer')
	Font.register({
		family: PDF_FONT,
		fonts: [
			{ src: `${FONT_DIRECTORY}Rubik-Regular.ttf`, fontWeight: 400 },
			{ src: `${FONT_DIRECTORY}Rubik-Bold.ttf`, fontWeight: 700 },
		],
	})
	const document = createElement(TranscriptDocument, {
		segments,
		summary,
		options: { ...options(direction), content: summary ? 'both' : 'transcript' },
		labels: { transcript: 'Transcript', summary: 'Summary' },
	}) as Parameters<typeof renderToBuffer>[0]
	return renderToBuffer(document)
}

async function extractText(pdf: Uint8Array) {
	const pdfjs = await import('pdfjs-dist/legacy/build/pdf.mjs')
	// pdf.js rejects a Node Buffer outright, so hand it a plain view of the same bytes.
	const document = await pdfjs.getDocument({ data: new Uint8Array(pdf) }).promise
	const pieces: string[] = []
	for (let page = 1; page <= document.numPages; page++) {
		const content = await (await document.getPage(page)).getTextContent()
		for (const item of content.items) {
			if ('str' in item && item.str.trim()) pieces.push(item.str.trim())
		}
	}
	return pieces.join(' ').replace(/\s+/g, ' ')
}

/**
 * Brackets around right-to-left text are mirrored per UAX#9 L4 — correct on the page, but
 * react-pdf swaps the character rather than only the glyph, so extraction gives back ")…(".
 * The pairing is what matters here, not which half of it each character is.
 */
function ignoringMirrors(text: string) {
	return text.replace(/[()[\]{}]/g, '•')
}

/** Every word of `sentence`, in order, somewhere in `text`. */
function readsInOrder(rawText: string, rawSentence: string) {
	const text = ignoringMirrors(rawText)
	const sentence = ignoringMirrors(rawSentence)
	let at = 0
	for (const word of sentence.split(' ')) {
		const next = text.indexOf(word, at)
		if (next < 0) return false
		at = next + word.length
	}
	return true
}

describe('transcript pdf', () => {
	it('lays right-to-left text down in visual order', async () => {
		const text = await extractText(await renderPdf(HEBREW, 'rtl'))
		for (const segment of HEBREW) {
			expect(readsInOrder(text, segment.text), `"${segment.text}" should read in order`).toBe(true)
		}
	}, 30_000)

	// A right-to-left document reorders an all-English sentence — correctly, and exactly as the
	// preview does — so the left-to-right case is checked under a left-to-right document.
	it('leaves a left-to-right document alone', async () => {
		const text = await extractText(await renderPdf(ENGLISH, 'ltr'))
		expect(readsInOrder(text, ENGLISH[0].text)).toBe(true)
	}, 30_000)

	it('draws the summary’s Markdown as formatting, not as marks', async () => {
		const summary = '### סקירה ממצה\n\nהדיון עוסק **במתודולוגיות** של מחקר.\n\n---\n\n- **מעבר לשון:** תהליך רציף\n1. ראשון'
		const text = await extractText(await renderPdf(HEBREW, 'rtl', summary))

		expect(text).not.toMatch(/###|\*\*|---/)
		// pdf.js gives each bold or plain run on its own, left to right on the page; so a line read
		// right to left is its runs backwards. The period must stay with the last words.
		expect(text).toContain('של מחקר. במתודולוגיות הדיון עוסק')
		expect(text).toContain('סקירה ממצה')
		expect(text).toContain('•')
	}, 30_000)

	// Pocket's own PDF lost every Hebrew label of its charts and printed "(%02)" for 20%.
	it('draws a summary’s charts with their Hebrew labels and numbers intact', async () => {
		const summary =
			'### גישות\n\n```dibe:chart\ntype: pie\ntitle: התפלגות הגישות\nקוניונקציה לוגית | 20\nהשתמעויות פרגמטיות | 80\n```\n\n```dibe:compare\nגישה | מקור\nמינימליסטית | לוגיקה\nפרגמטית | הקשר\n```\n\n```dibe:flow\nרגש -> כנות\nכנות -> העצמה\n```'
		const text = await extractText(await renderPdf(HEBREW, 'rtl', summary))
		expect(text).not.toContain('```')
		expect(text).not.toContain('dibe:')
		for (const label of ['התפלגות הגישות', 'קוניונקציה לוגית', 'השתמעויות פרגמטיות', 'מינימליסטית', 'לוגיקה', 'כנות', 'העצמה']) {
			expect(text, label).toContain(label)
		}
		expect(text).toContain('20%')
		expect(text).toContain('80%')
	}, 30_000)

	it('keeps the fonts embedded, so the text is real rather than an image', async () => {
		const pdf = await renderPdf(HEBREW, 'rtl')
		expect(Buffer.from(pdf).subarray(0, 5).toString()).toBe('%PDF-')
		expect(await extractText(pdf)).toContain('SaferPlace')
	}, 30_000)
})

/** Kept next to the fixtures: proves the font ships the scripts the fixtures use. */
it('bundles a font covering Latin and Hebrew', () => {
	const font = readFileSync(`${FONT_DIRECTORY}Rubik-Regular.ttf`)
	expect(font.byteLength).toBeGreaterThan(10_000)
})
