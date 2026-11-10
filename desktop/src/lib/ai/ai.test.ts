import { afterEach, describe, expect, it, vi } from 'vitest'
import { DEFAULT_AI, defaultModel, migrateLegacy, modernizePrompt, presetPrompt } from './config'
import { chunkLines, createClient, fillPrompt, inputBudgetBytes, testConnection } from './client'
import { inlineTextFiles, type Attachment } from './attachments'

const fetchMock = vi.fn()
vi.mock('@tauri-apps/plugin-http', () => ({ fetch: (...args: unknown[]) => fetchMock(...args) }))

afterEach(() => fetchMock.mockReset())

describe('migrateLegacy', () => {
	it('returns the defaults with nothing to migrate', () => {
		expect(migrateLegacy(null)).toEqual(DEFAULT_AI)
		expect(migrateLegacy(undefined)).toEqual(DEFAULT_AI)
	})

	it('moves the old prompt to the summary task and gives dictation its own', () => {
		const migrated = migrateLegacy(
			{ platform: 'ollama', enabled: true, model: 'llama3', prompt: 'Summarize: %s', ollamaBaseUrl: 'http://box:11434', contextTokens: 8192 } as never,
			true,
		)
		expect(migrated.connection).toMatchObject({ platform: 'ollama', model: 'llama3', ollamaBaseUrl: 'http://box:11434', contextTokens: 8192 })
		expect(migrated.tasks.summary).toEqual({ enabled: true, autoOnFinish: true, preset: 'custom', prompt: 'Summarize: {transcript}' })
		expect(migrated.tasks.dictation).toEqual({ enabled: true, preset: 'punctuation', prompt: presetPrompt('punctuation') })
	})

	it('keeps the summary off when it was off', () => {
		const migrated = migrateLegacy({ platform: 'claude', enabled: false, prompt: '' } as never, true)
		expect(migrated.tasks.summary.enabled).toBe(false)
		expect(migrated.tasks.summary.autoOnFinish).toBe(false)
		expect(migrated.tasks.summary.preset).toBe('summary')
	})

	it('modernizes only the placeholder', () => {
		expect(modernizePrompt('a %s b', 'dictation')).toBe('a {text} b')
	})
})

describe('fillPrompt', () => {
	it('fills every placeholder, and %s for old prompts', () => {
		expect(fillPrompt('{language}: {transcript} / {speakers}', { transcript: 'hi', language: 'Hebrew', speakers: 'A, B' })).toBe('Hebrew: hi / A, B')
		expect(fillPrompt('old %s', { text: 'hi' })).toBe('old hi')
		expect(fillPrompt('{text}', { transcript: 'hi' })).toBe('hi')
	})
})

describe('chunkLines', () => {
	it('keeps one chunk when it fits, splits between lines when it does not', () => {
		const lines = ['one', 'two', 'three']
		expect(chunkLines(lines, '{transcript}', 4096)).toEqual(['one\ntwo\nthree'])
		const long = Array.from({ length: 200 }, (_, index) => `line ${index} ${'x'.repeat(100)}`)
		const chunks = chunkLines(long, '{transcript}', 4096)
		expect(chunks.length).toBeGreaterThan(1)
		expect(chunks.join('\n')).toBe(long.join('\n'))
		const budget = inputBudgetBytes(4096)
		for (const chunk of chunks) expect(chunk.length).toBeLessThanOrEqual(budget)
	})
})

function streamResponse(lines: string[]) {
	const encoder = new TextEncoder()
	const body = new ReadableStream<Uint8Array>({
		start(controller) {
			for (const line of lines) controller.enqueue(encoder.encode(line + '\n'))
			controller.close()
		},
	})
	return { ok: true, status: 200, statusText: 'OK', body, text: async () => lines.join('\n'), json: async () => ({}) }
}

describe('createClient', () => {
	it('streams Ollama NDJSON', async () => {
		fetchMock.mockResolvedValue(streamResponse(['{"response":"Hel"}', '{"response":"lo"}', '{"done":true}']))
		const tokens: string[] = []
		const text = await createClient({ ...DEFAULT_AI.connection, platform: 'ollama' }).stream('p', (token) => tokens.push(token))
		expect(text).toBe('Hello')
		expect(tokens).toEqual(['Hel', 'lo'])
		expect(fetchMock.mock.calls[0][0]).toBe('http://localhost:11434/api/generate')
		expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toMatchObject({ stream: true, model: DEFAULT_AI.connection.model })
	})

	it('streams OpenAI SSE and stops at [DONE]', async () => {
		fetchMock.mockResolvedValue(
			streamResponse(['data: {"choices":[{"delta":{"content":"a"}}]}', '', 'data: {"choices":[{"delta":{"content":"b"}}]}', 'data: [DONE]']),
		)
		const text = await createClient({ ...DEFAULT_AI.connection, platform: 'openai', openaiApiKey: 'k' }).stream('p', () => {})
		expect(text).toBe('ab')
		expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe('Bearer k')
	})

	it('streams Gemini through its OpenAI-compatible endpoint with its own key', async () => {
		fetchMock.mockResolvedValue(streamResponse(['data: {"choices":[{"delta":{"content":"שלום"}}]}', 'data: [DONE]']))
		const connection = { ...DEFAULT_AI.connection, platform: 'gemini' as const, model: defaultModel('gemini'), geminiApiKey: 'g', openaiApiKey: 'other' }
		const text = await createClient(connection).stream('p', () => {})
		expect(text).toBe('שלום')
		expect(fetchMock.mock.calls[0][0]).toBe('https://generativelanguage.googleapis.com/v1beta/openai/chat/completions')
		expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe('Bearer g')
		expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toMatchObject({ model: 'gemini-3.8-flash', stream: true })
	})

	it('names Gemini in its errors and survives settings saved before Gemini existed', async () => {
		fetchMock.mockResolvedValue({ ok: false, status: 400, statusText: 'Bad Request', text: async () => '{"error":{"message":"API key not valid"}}' })
		const saved = { ...DEFAULT_AI.connection, platform: 'gemini' as const } as Partial<typeof DEFAULT_AI.connection>
		delete saved.geminiApiKey
		const result = await testConnection(saved as typeof DEFAULT_AI.connection)
		expect(result).toMatchObject({ ok: false, error: 'Gemini: 400 Bad Request · API key not valid' })
		expect(fetchMock.mock.calls[0][1].headers.Authorization).toBeUndefined()
	})

	it('streams Claude content deltas only', async () => {
		fetchMock.mockResolvedValue(
			streamResponse([
				'event: message_start',
				'data: {"type":"message_start"}',
				'data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"hi"}}',
			]),
		)
		const text = await createClient({ ...DEFAULT_AI.connection, platform: 'claude' }).stream('p', () => {})
		expect(text).toBe('hi')
	})

	it("sends images and PDFs as each provider's media, and text files inside the prompt", async () => {
		const files: Attachment[] = [
			{ name: 'slide.png', kind: 'image', mime: 'image/png', data: 'iVBO' },
			{ name: 'notes.md', kind: 'text', mime: 'text/plain', data: '# Agenda' },
			{ name: 'deck.pdf', kind: 'pdf', mime: 'application/pdf', data: 'JVBE' },
		]
		const prompt = inlineTextFiles('Summarize', files)
		expect(prompt).toBe(
			'Summarize\n\nAttached files: slide.png, notes.md, deck.pdf. Read them and use what they contain, together with the transcript, to do the task above.\n\n--- Attached file: notes.md ---\n# Agenda',
		)
		expect(inlineTextFiles('Summarize', [])).toBe('Summarize')

		fetchMock.mockImplementation(async () => streamResponse([]))
		await createClient({ ...DEFAULT_AI.connection, platform: 'claude' }).stream(prompt, () => {}, files)
		expect(JSON.parse(fetchMock.mock.calls[0][1].body).messages[0].content).toEqual([
			{ type: 'image', source: { type: 'base64', media_type: 'image/png', data: 'iVBO' } },
			{ type: 'document', source: { type: 'base64', media_type: 'application/pdf', data: 'JVBE' } },
			{ type: 'text', text: prompt },
		])

		await createClient({ ...DEFAULT_AI.connection, platform: 'openai' }).stream(prompt, () => {}, files)
		expect(JSON.parse(fetchMock.mock.calls[1][1].body).messages[0].content).toEqual([
			{ type: 'text', text: prompt },
			{ type: 'image_url', image_url: { url: 'data:image/png;base64,iVBO' } },
			{ type: 'file', file: { filename: 'deck.pdf', file_data: 'data:application/pdf;base64,JVBE' } },
		])

		await createClient({ ...DEFAULT_AI.connection, platform: 'ollama' }).stream(prompt, () => {}, files.slice(0, 2))
		expect(JSON.parse(fetchMock.mock.calls[2][1].body)).toMatchObject({ prompt, images: ['iVBO'] })
	})

	it('turns an error body into a readable message', async () => {
		fetchMock.mockResolvedValue({ ok: false, status: 401, statusText: 'Unauthorized', text: async () => '{"error":{"message":"bad key"}}' })
		const result = await testConnection({ ...DEFAULT_AI.connection, platform: 'openai' })
		expect(result).toMatchObject({ ok: false, error: 'OpenAI: 401 Unauthorized · bad key' })
	})

	it('reports a working connection with its latency', async () => {
		fetchMock.mockResolvedValue({ ok: true, json: async () => ({ content: [{ text: 'OK' }] }) })
		const result = await testConnection(DEFAULT_AI.connection)
		expect(result).toMatchObject({ ok: true, reply: 'OK' })
	})
})
