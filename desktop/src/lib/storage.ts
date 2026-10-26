import { invoke } from '@tauri-apps/api/core'
import * as config from '~/lib/config'
import { getLocale } from '~/paraglide/runtime.js'

// Shapes of `scan_models_storage` (desktop/src-tauri/src/cmd/storage.rs).
export type ModelFileKind = { kind: 'InUse' } | { kind: 'Duplicate'; of: string } | { kind: 'Legacy' } | { kind: 'Other' }

export interface ModelFile {
	path: string
	name: string
	size: number
	kind: ModelFileKind
}

export interface ModelsStorage {
	files: ModelFile[]
	totalBytes: number
	/** What deleting every duplicate and old file would free. */
	reclaimableBytes: number
}

/**
 * The files the app reads right now: the selected model plus the VAD and diarization models. The
 * backend never reports them as deletable and keeps them as the copy that stays among duplicates.
 */
async function filesInUse(modelPath: string | null) {
	const folder = await invoke<string>('get_models_folder')
	const paths = [modelPath, `${folder}/${config.vadModelFilename}`, `${folder}/${config.diarizeModelFilename}`]
	return paths.filter((path): path is string => Boolean(path))
}

export async function scanModelsStorage(modelPath: string | null) {
	return invoke<ModelsStorage>('scan_models_storage', { keep: await filesInUse(modelPath) })
}

export async function deleteModelFile(path: string, modelPath: string | null) {
	await invoke('delete_model_file', { path, keep: await filesInUse(modelPath) })
}

const UNITS = ['byte', 'kilobyte', 'megabyte', 'gigabyte', 'terabyte']

/** Decimal units, like Finder: the 1.6 GB model is 1.6 GB here too. */
export function formatBytes(bytes: number) {
	const power = bytes > 0 ? Math.min(Math.floor(Math.log10(bytes) / 3), UNITS.length - 1) : 0
	const value = bytes / 1000 ** power
	return new Intl.NumberFormat(getLocale(), {
		style: 'unit',
		unit: UNITS[power],
		unitDisplay: 'short',
		maximumFractionDigits: power >= 2 && value < 10 ? 1 : 0,
	}).format(value)
}
