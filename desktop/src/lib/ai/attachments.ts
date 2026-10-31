import * as fs from '@tauri-apps/plugin-fs'
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
const TEXT_EXTENSIONS = ['txt', 'md', 'markdown', 'csv', 'tsv', 'json', 'srt', 'vtt', 'html', 'htm', 'xml', 'yaml', 'yml', 'log']

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

export async function readAttachment(path: string, kind: AttachmentKind): Promise<Attachment> {
	const name = fileName(path)
	if (kind === 'text') return { name, kind, mime: 'text/plain', data: await fs.readTextFile(path) }
	const mime = kind === 'pdf' ? 'application/pdf' : IMAGE_TYPES.get(extension(path))!
	return { name, kind, mime, data: base64(await fs.readFile(path)) }
}

/** Text files go after the prompt, each under its name, so the model can tell them apart. */
export function inlineTextFiles(prompt: string, attachments: Attachment[]) {
	const texts = attachments.filter((attachment) => attachment.kind === 'text')
	return [prompt, ...texts.map((attachment) => `--- Attached file: ${attachment.name} ---\n${attachment.data}`)].join('\n\n')
}
