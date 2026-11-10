// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { toDocx } from '~/lib/docx'
import { attachmentKind, docxText } from './attachments'

describe('docxText', () => {
	it('reads the paragraphs of a Word document, Hebrew included', async () => {
		const blob = await toDocx('שיעור', [{ start: 0, stop: 100, text: 'שלום & להתראות' }], 'rtl', 'Speaker', { content: 'both', summary: 'לקרוא את פרק ד' })
		const text = docxText(new Uint8Array(await blob.arrayBuffer()))

		expect(text).toContain('שלום & להתראות')
		expect(text).toContain('לקרוא את פרק ד')
		expect(text).not.toMatch(/<w:/)
		expect(attachmentKind('/notes/שיעור.DOCX')).toBe('text')
	})

	it('refuses a zip that is not a Word document', () => {
		expect(() => docxText(new Uint8Array([0x50, 0x4b, 0x05, 0x06, ...new Array(18).fill(0)]))).toThrow('Not a Word document')
	})
})
