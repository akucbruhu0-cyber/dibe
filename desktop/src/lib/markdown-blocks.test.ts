import { describe, expect, it } from 'vitest'
import { markdownToHtml, markdownToText, parseMarkdown } from './markdown-blocks'

/** The shape of an AI summary, as the screenshot of the broken Word export had it. */
const SUMMARY = `### סקירה ממצה

הדיון עוסק **במתודולוגיות** של מחקר, ו*בעיקר* בשפה.
שורה שנייה של אותה פסקה.

---

### נקודות מפתח

- **מעבר לשון:** תהליך רציף
- נקודה שנייה
  - תת־נקודה

1. ראשון
2. שני

> ציטוט`

describe('parseMarkdown', () => {
	it('reads headings, paragraphs, a rule, lists and a quote as blocks', () => {
		const blocks = parseMarkdown(SUMMARY)

		expect(blocks.map((block) => block.type)).toEqual([
			'heading',
			'paragraph',
			'rule',
			'heading',
			'bullet',
			'bullet',
			'bullet',
			'number',
			'number',
			'quote',
		])
		expect(blocks[0]).toMatchObject({ type: 'heading', rank: 0 })
		expect(blocks[6]).toMatchObject({ type: 'bullet', depth: 1 })
		expect(blocks[8]).toMatchObject({ type: 'number', marker: '2.' })
	})

	it('ranks headings from the largest the text uses, so ### alone is a top heading', () => {
		const blocks = parseMarkdown('## Big\n\n#### Small')
		expect(blocks).toMatchObject([
			{ type: 'heading', rank: 0 },
			{ type: 'heading', rank: 2 },
		])
	})

	it('splits bold and italic into runs and joins a paragraph’s lines', () => {
		const [, paragraph] = parseMarkdown(SUMMARY)
		expect(paragraph.type === 'paragraph' && paragraph.runs).toEqual([
			{ text: 'הדיון עוסק ' },
			{ text: 'במתודולוגיות', bold: true },
			{ text: ' של מחקר, ו' },
			{ text: 'בעיקר', italic: true },
			{ text: ' בשפה. שורה שנייה של אותה פסקה.' },
		])
	})

	it('keeps a link’s words and a lone asterisk as text', () => {
		const [block] = parseMarkdown('See [the docs](https://example.com) at 5 * 3')
		expect(block.type === 'paragraph' && block.runs.map((run) => run.text).join('')).toBe('See the docs at 5 * 3')
	})
})

describe('markdownToText', () => {
	it('leaves no markdown marks, only words, bullets and numbers', () => {
		const text = markdownToText(SUMMARY)

		expect(text).not.toMatch(/[#*>]|---/)
		expect(text).toContain('סקירה ממצה\n\nהדיון עוסק במתודולוגיות')
		expect(text).toContain('• מעבר לשון: תהליך רציף\n• נקודה שנייה\n  • תת־נקודה')
		expect(text).toContain('1. ראשון\n2. שני')
	})
})

describe('markdownToHtml', () => {
	it('writes real elements, headings starting at the level given', () => {
		const html = markdownToHtml(SUMMARY, 3)

		expect(html).toContain('<h3>סקירה ממצה</h3>')
		expect(html).toContain('<strong>במתודולוגיות</strong>')
		expect(html).toContain('<em>בעיקר</em>')
		expect(html).toContain('<hr>')
		expect(html).toContain('<ul><li><strong>מעבר לשון:</strong> תהליך רציף</li><li>נקודה שנייה<ul><li>תת־נקודה</li></ul></li></ul>')
		expect(html).toContain('<ol><li>ראשון</li><li>שני</li></ol>')
		expect(html).toContain('<blockquote>ציטוט</blockquote>')
	})

	it('escapes what it does not understand', () => {
		expect(markdownToHtml('a <script>x</script> & b', 3)).toBe('<p>a &lt;script&gt;x&lt;/script&gt; &amp; b</p>')
	})
})
