import { fetch } from '@tauri-apps/plugin-http'
import { defaultModel, type AiPlatform } from './config'

export type AiModelBadge = 'free' | 'lowCost' | 'local'

export interface AiModelOption {
	id: string
	name: string
	badge?: AiModelBadge
}

/**
 * The picker's ready-made list, recommended first. Same list as the Android app (`AiModels.kt`),
 * checked 2026-11-09 against each provider's models and pricing pages. Any other id can still be typed.
 */
const curated: Record<AiPlatform, [id: string, name: string][]> = {
	gemini: [
		['gemini-3.8-flash', 'Gemini 3.8 Flash'],
		['gemini-3.6-flash', 'Gemini 3.6 Flash'],
		['gemini-3.5-flash-lite', 'Gemini 3.5 Flash-Lite'],
		['gemini-3.1-flash-lite', 'Gemini 3.1 Flash-Lite'],
		['gemini-4-argon', 'Gemini 4 Argon'],
		['gemini-3.1-pro-preview', 'Gemini 3.1 Pro Preview'],
	],
	openai: [
		['gpt-6-luna', 'GPT-6 Luna'],
		['gpt-5.6-luna', 'GPT-5.6 Luna'],
		['gpt-6.1-terra', 'GPT-6.1 Terra'],
		['gpt-6.1-sol', 'GPT-6.1 Sol'],
		['gpt-6-astra', 'GPT-6 Astra'],
	],
	claude: [
		['claude-haiku-5-5', 'Claude Haiku 5.5'],
		['claude-sonnet-5-5', 'Claude Sonnet 5.5'],
		['claude-sonnet-5', 'Claude Sonnet 5'],
		['claude-opus-5-5', 'Claude Opus 5.5'],
	],
	ollama: [['gemma4:e2b', 'gemma4:e2b']],
}

// ponytail: name rules, not prices; a provider renaming its cheap line needs a new rule here.
function badge(platform: AiPlatform, id: string): AiModelBadge | undefined {
	if (platform === 'ollama') return 'local'
	if (platform === 'gemini') return id.includes('flash') ? 'free' : undefined
	if (platform === 'openai') return id.includes('-luna') || id.includes('-nano') ? 'lowCost' : undefined
	return id.startsWith('claude-haiku-5') ? 'lowCost' : undefined
}

/**
 * Models offered for a platform, badged ones first. A custom OpenAI-compatible server has its own
 * ids, so it gets no list and the field stays free text.
 */
export function aiModelOptions(platform: AiPlatform, openaiBaseUrl = '', live: [id: string, name: string][] = []): AiModelOption[] {
	if (platform === 'openai' && !/^https:\/\/api\.openai\.com\b/.test(openaiBaseUrl)) return []
	const known = new Set(curated[platform].map(([id]) => id))
	const models = [...curated[platform], ...live.filter(([id]) => !known.has(id))].map(([id, name]) => ({ id, name, badge: badge(platform, id) }))
	if (!models.some((model) => model.id === defaultModel(platform)))
		models.push({ id: defaultModel(platform), name: defaultModel(platform), badge: badge(platform, defaultModel(platform)) })
	return models.sort((a, b) => Number(!a.badge) - Number(!b.badge))
}

/** Ids on Google's list that answer generateContent but are not text chat models (same as Android's `AiModels.kt`). */
const geminiNonText = ['image', 'tts', 'live', 'embedding', 'robotics', 'transcribe', 'audio', 'banana', 'omni', 'customtools', 'lyria', 'computer-use', 'antigravity', 'deep-research']

/** The text models in a `GET /v1beta/models` response, as the phone lists them (previews included). */
export function parseGeminiModels(body: unknown): [id: string, name: string][] {
	const models = (body as { models?: unknown })?.models
	if (!Array.isArray(models)) return []
	return models.flatMap((model: { name?: unknown; displayName?: unknown; supportedGenerationMethods?: unknown }) => {
		const id = typeof model?.name === 'string' ? model.name.replace(/^models\//, '') : ''
		const methods = Array.isArray(model?.supportedGenerationMethods) ? model.supportedGenerationMethods : []
		if (!id || !methods.includes('generateContent') || geminiNonText.some((word) => id.includes(word))) return []
		return [[id, typeof model.displayName === 'string' && model.displayName ? model.displayName : id] as [string, string]]
	})
}

/** Every Gemini text model this key can use; empty without a key or on any failure (the curated list still shows). */
export async function fetchGeminiModels(apiKey: string): Promise<[id: string, name: string][]> {
	if (!apiKey.trim()) return []
	try {
		const response = await fetch('https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000', { headers: { 'x-goog-api-key': apiKey.trim() } })
		return response.ok ? parseGeminiModels(await response.json()) : []
	} catch (error) {
		console.warn('could not list Gemini models:', error)
		return []
	}
}
