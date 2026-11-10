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
export function aiModelOptions(platform: AiPlatform, openaiBaseUrl = ''): AiModelOption[] {
	if (platform === 'openai' && !/^https:\/\/api\.openai\.com\b/.test(openaiBaseUrl)) return []
	const models = curated[platform].map(([id, name]) => ({ id, name, badge: badge(platform, id) }))
	if (!models.some((model) => model.id === defaultModel(platform)))
		models.push({ id: defaultModel(platform), name: defaultModel(platform), badge: badge(platform, defaultModel(platform)) })
	return models.sort((a, b) => Number(!a.badge) - Number(!b.badge))
}
