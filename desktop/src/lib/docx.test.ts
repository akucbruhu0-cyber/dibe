import { inflateRawSync } from 'node:zlib'
import { describe, expect, it } from 'vitest'
import { toDocx } from './docx'

/** One file out of a .docx (a zip), read from its local header: enough for the files Word writes. */
function unzip(zip: Buffer, name: string) {
	for (let at = 0; at + 30 < zip.length; ) {
		if (zip.readUInt32LE(at) !== 0x04034b50) break
		const method = zip.readUInt16LE(at + 8)
		const size = zip.readUInt32LE(at + 18)
		const nameLength = zip.readUInt16LE(at + 26)
		const extraLength = zip.readUInt16LE(at + 28)
		const start = at + 30 + nameLength + extraLength
		if (zip.toString('utf8', at + 30, at + 30 + nameLength) === name) {
			const data = zip.subarray(start, start + size)
			return (method === 8 ? inflateRawSync(data) : data).toString('utf8')
		}
		at = start + size
	}
	throw new Error(`${name} is not in the document`)
}

async function documentXml(summary: string) {
	const blob = await toDocx('שיעור', [{ start: 0, stop: 100, text: 'שלום' }], 'rtl', 'Speaker', { content: 'both', summary })
	return unzip(Buffer.from(await blob.arrayBuffer()), 'word/document.xml')
}

describe('toDocx', () => {
	it('writes an AI summary’s Markdown as Word formatting, not as marks', async () => {
		const xml = await documentXml('### סקירה ממצה\n\nהדיון **במתודולוגיות** של מחקר.\n\n---\n\n- **מעבר לשון:** תהליך רציף\n1. ראשון')

		expect(xml).not.toMatch(/###|\*\*|---/)
		// A heading style, a bold run, a bordered rule, and list markers hanging in their indent.
		expect(xml).toContain('<w:pStyle w:val="Heading2"/>')
		expect(xml).toMatch(/<w:r><w:rPr><w:b\/>(?:(?!<\/w:r>).)*במתודולוגיות/)
		expect(xml).toContain('<w:pBdr><w:bottom')
		expect(xml).toContain('•\t')
		expect(xml).toContain('1.\t')
		expect(xml).toContain('w:hanging="360"')
	})
})
