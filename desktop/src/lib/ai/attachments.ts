import * as fs from '@tauri-apps/plugin-fs'
import { unzipSync } from 'fflate'
import type { AiPlatform } from './config'

/**
 * Files the user hands the model next to one summary request. Images and PDFs travel as each
 * provider's own image or document input; text files are written into the prompt, so every
 * provider reads those. Nothing here is saved: an attachment lives for one request.
 */
export interface Attachment {
	name: string
	kind: AttachmentKind
	/** MIME type, which the providers need beside the bytes of an image or a PDF. */
	mime: string
	/** Base64 for images and PDFs; the text itself for text files. */
	data: string
}

export type AttachmentKind = 'image' | 'pdf' | 'text'

/** Above this a request is slow to send and most providers refuse it anyway. */
export const MAX_ATTACHMENT_MB = 20

const IMAGE_TYPES = new Map([
	['png', 'image/png'],
	['jpg', 'image/jpeg'],
	['jpeg', 'image/jpeg'],
	['webp', 'image/webp'],
	['gif', 'image/gif'],
])
// A Word document is read for its text, so it goes in the prompt like the plain text files.
const TEXT_EXTENSIONS = ['txt', 'md', 'markdown', 'csv', 'tsv', 'json', 'srt', 'vtt', 'html', 'htm', 'xml', 'yaml', 'yml', 'log', 'docx']

export const ATTACHMENT_EXTENSIONS = [...IMAGE_TYPES.keys(), 'pdf', ...TEXT_EXTENSIONS]

export function fileName(path: string) {
	return path.split(/[\\/]/).pop() ?? path
}

function extension(path: string) {
	return path.split('.').pop()?.toLowerCase() ?? ''
}

export function attachmentKind(path: string): AttachmentKind | null {
	const ext = extension(path)
	if (IMAGE_TYPES.has(ext)) return 'image'
	if (ext === 'pdf') return 'pdf'
	return TEXT_EXTENSIONS.includes(ext) ? 'text' : null
}

/** Ollama's generate endpoint takes images but has no document input. */
export function canRead(platform: AiPlatform, kind: AttachmentKind) {
	return !(platform === 'ollama' && kind === 'pdf')
}

export async function attachmentTooLarge(path: string) {
	return (await fs.stat(path)).size > MAX_ATTACHMENT_MB * 1024 * 1024
}

function base64(bytes: Uint8Array) {
	// btoa takes a binary string; building it in slices keeps a 20 MB file under the argument limit.
	let binary = ''
	for (let index = 0; index < bytes.length; index += 0x8000) binary += String.fromCharCode(...bytes.subarray(index, index + 0x8000))
	return btoa(binary)
}

const WORD_NS = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'

/**
 * A .docx is a zip; its body is word/document.xml, where text sits in runs (w:r) of w:t, with
 * w:tab and w:br between them. One line per paragraph, tables' cells included, in document order.
 */
export function docxText(bytes: Uint8Array) {
	const xml = unzipSync(bytes, { filter: (file) => file.name === 'word/document.xml' })['word/document.xml']
	if (!xml) throw new Error('Not a Word document (.docx)')
	const document = new DOMParser().parseFromString(new TextDecoder().decode(xml), 'application/xml')
	const piece = (node: Element) => {
		// Only inside a run: w:tab also names the tab stops in a paragraph's properties.
		if (node.parentElement?.localName !== 'r') return ''
		return node.localName === 't' ? (node.textContent ?? '') : node.localName === 'tab' ? '\t' : node.localName === 'br' ? '\n' : ''
	}
	return Array.from(document.getElementsByTagNameNS(WORD_NS, 'p'), (paragraph) =>
		Array.from(paragraph.getElementsByTagNameNS(WORD_NS, '*'), piece).join(''),
	).join('\n')
}

export async function readAttachment(path: string, kind: AttachmentKind): Promise<Attachment> {
	const name = fileName(path)
	if (kind === 'text') {
		const data = extension(path) === 'docx' ? docxText(await fs.readFile(path)) : await fs.readTextFile(path)
		return { name, kind, mime: 'text/plain', data }
	}
	const mime = kind === 'pdf' ? 'application/pdf' : IMAGE_TYPES.get(extension(path))!
	return { name, kind, mime, data: base64(await fs.readFile(path)) }
}

/**
 * Text files go after the prompt, each under its name, so the model can tell them apart. Every
 * attachment is named first: a prompt that speaks only of "the transcript" otherwise lets the model
 * pass over images and PDFs it was sent, and text that follows the transcript unannounced.
 */
export function inlineTextFiles(prompt: string, attachments: Attachment[]) {
	if (attachments.length === 0) return prompt
	const names = attachments.map((attachment) => attachment.name).join(', ')
	const note = `Attached files: ${names}. Read them and use what they contain, together with the transcript, to do the task above.`
	const texts = attachments.filter((attachment) => attachment.kind === 'text')
	return [prompt, note, ...texts.map((attachment) => `--- Attached file: ${attachment.name} ---\n${attachment.data}`)].join('\n\n')
}
