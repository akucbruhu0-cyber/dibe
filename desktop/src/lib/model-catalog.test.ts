import { describe, expect, it } from 'vitest'
import { fitFor, recommendModel, sortForMachine } from './model-catalog'
import { aiModelOptions } from './ai/models'

const GB = 1024 ** 3

describe('model catalog', () => {
	it('rates fit with room for the OS', () => {
		expect(fitFor(16 * GB, 8)).toBe('good')
		expect(fitFor(8 * GB, 8)).toBe('tight')
		expect(fitFor(4 * GB, 8)).toBe('tooHeavy')
	})

	it('recommends Parakeet Hebrew for Hebrew on weak and strong machines alike', () => {
		expect(recommendModel(true, 8 * GB).id).toBe('parakeet-he')
		expect(recommendModel(true, 32 * GB).id).toBe('parakeet-he')
		expect(recommendModel(true, 2 * GB).id).toBe('whisper-small')
	})

	it('gives Turbo only to machines it fits comfortably', () => {
		expect(recommendModel(false, 16 * GB).id).toBe('whisper-turbo')
		expect(recommendModel(false, 8 * GB).id).toBe('whisper-small')
		// Unknown memory reads as 0: the safe side.
		expect(recommendModel(false, 0).id).toBe('whisper-small')
	})

	it('lists the recommended model first and heavy ones last', () => {
		const list = sortForMachine(recommendModel(true, 6 * GB), 6 * GB)
		expect(list[0].id).toBe('parakeet-he')
		expect(list[list.length - 1].id).toMatch(/turbo/)
	})
})

describe('AI model list', () => {
	it('puts badged models first and always offers the default', () => {
		const gemini = aiModelOptions('gemini')
		expect(gemini[0].badge).toBe('free')
		const badges = gemini.map((model) => Boolean(model.badge))
		expect(badges).toEqual([...badges].sort((a, b) => Number(b) - Number(a)))
		expect(aiModelOptions('claude').some((model) => model.id === 'claude-sonnet-5')).toBe(true)
	})

	it('offers no list for a custom OpenAI-compatible server', () => {
		expect(aiModelOptions('openai', 'http://localhost:1234/v1')).toEqual([])
		expect(aiModelOptions('openai', 'https://api.openai.com/v1').length).toBeGreaterThan(0)
	})
})
