import type { ModelDownload } from './config'

const HF = 'https://huggingface.co'

export type ModelFit = 'good' | 'tight' | 'tooHeavy'

export interface CatalogModel {
	id: string
	name: string
	download: ModelDownload
	/** Download size, for display. */
	sizeBytes: number
	/** Least RAM it loads in comfortably on a desktop OS, in GB. */
	minRamGb: number
	/** 'he': Hebrew only; 'eu': 25 European languages; 'all': Whisper's 99. */
	languages: 'he' | 'eu' | 'all'
	speed: 'fastest' | 'fast' | 'medium'
	accuracy: 'basic' | 'good' | 'best'
}

/**
 * What the first-run screen offers. The Parakeet and small Whisper entries are the Android catalog's
 * files (same size and sha256). The two Turbo files are the existing desktop defaults, with no
 * published hash yet, so they keep only the backend's size and magic-byte checks.
 */
export const MODEL_CATALOG: CatalogModel[] = [
	{
		id: 'parakeet-he',
		name: 'Parakeet Hebrew',
		download: {
			url: `${HF}/vibe-app/parakeet-tdt-0.6b-v3-he-gguf/resolve/main/parakeet-tdt-0.6b-v3-he-q8_0.gguf`,
			size: 758_224_288,
			sha256: '37a6787344617fd7402397cd7ec4dd4f4bda779cb3112ac571e7b3329194e8dd',
		},
		sizeBytes: 758_224_288,
		minRamGb: 4,
		languages: 'he',
		speed: 'fast',
		accuracy: 'best',
	},
	{
		id: 'whisper-turbo',
		name: 'Whisper Large v3 Turbo',
		download: { url: `${HF}/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo.bin` },
		sizeBytes: 1_624_555_275,
		minRamGb: 8,
		languages: 'all',
		speed: 'medium',
		accuracy: 'best',
	},
	{
		id: 'whisper-turbo-he',
		name: 'Whisper Turbo Hebrew (ivrit.ai)',
		download: { url: `${HF}/ivrit-ai/whisper-large-v3-turbo-ggml/resolve/main/ggml-model.bin` },
		sizeBytes: 1_624_555_275,
		minRamGb: 8,
		languages: 'he',
		speed: 'medium',
		accuracy: 'best',
	},
	{
		id: 'parakeet-multi',
		name: 'Parakeet Multilingual',
		download: {
			url: `${HF}/vibe-app/parakeet-tdt-0.6b-v3-gguf/resolve/main/parakeet-tdt-0.6b-v3-Q4_K_M.gguf`,
			size: 485_425_536,
			sha256: '5c61c041314d87ef1ffc278f5fb1a8ea25cceb8ce6053f8b516bab7ba16d7508',
		},
		sizeBytes: 485_425_536,
		minRamGb: 4,
		languages: 'eu',
		speed: 'fast',
		accuracy: 'good',
	},
	{
		id: 'whisper-small',
		name: 'Whisper Small',
		download: {
			url: `${HF}/ggerganov/whisper.cpp/resolve/main/ggml-small-q8_0.bin`,
			size: 264_464_607,
			sha256: '49c8fb02b65e6049d5fa6c04f81f53b867b5ec9540406812c643f177317f779f',
		},
		sizeBytes: 264_464_607,
		minRamGb: 2,
		languages: 'all',
		speed: 'fastest',
		accuracy: 'basic',
	},
]

const GB = 1024 ** 3

/** How a model sits in this machine's RAM. The OS and other apps take ~4 GB, hence the margin. */
export function fitFor(totalMemoryBytes: number, minRamGb: number): ModelFit {
	const gb = Math.round(totalMemoryBytes / GB)
	if (gb >= minRamGb + 4) return 'good'
	if (gb >= minRamGb) return 'tight'
	return 'tooHeavy'
}

/**
 * The one model the first-run screen puts on top. Hebrew goes to Parakeet Hebrew, the phone app's
 * model: fast, live-capable and under half Turbo's size, so it suits weak and strong machines alike.
 * Everyone else gets Turbo when it fits comfortably, else the small Whisper.
 */
export function recommendModel(hebrew: boolean, totalMemoryBytes: number): CatalogModel {
	const byId = (id: string) => MODEL_CATALOG.find((model) => model.id === id)!
	const candidates = hebrew ? ['parakeet-he', 'whisper-small'] : ['whisper-turbo', 'whisper-small']
	return (
		candidates.map(byId).find((model, index) => {
			const fit = fitFor(totalMemoryBytes, model.minRamGb)
			// The first choice must fit well; the fallback only has to load.
			return index === 0 ? fit !== 'tooHeavy' && (hebrew || fit === 'good') : fit !== 'tooHeavy'
		}) ?? byId('whisper-small')
	)
}

/** Recommended first, then models that fit this machine, then the rest; catalog order within each. */
export function sortForMachine(recommended: CatalogModel, totalMemoryBytes: number): CatalogModel[] {
	const rank = (model: CatalogModel) => (model.id === recommended.id ? 0 : { good: 1, tight: 2, tooHeavy: 3 }[fitFor(totalMemoryBytes, model.minRamGb)])
	return [...MODEL_CATALOG].sort((a, b) => rank(a) - rank(b))
}
