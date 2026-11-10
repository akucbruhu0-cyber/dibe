import { invoke } from '@tauri-apps/api/core'
import { emit, listen } from '@tauri-apps/api/event'
import { useContext, useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { ErrorModalContext } from '~/providers/error-modal'
import { usePreferenceProvider } from '~/providers/preference'
import * as utils from '~/lib/model'
import * as osExt from '@tauri-apps/plugin-os'
import * as config from '~/lib/config'
import { ask } from '@tauri-apps/plugin-dialog'
import { join } from '@tauri-apps/api/path'
import { MODEL_CATALOG, recommendModel, type CatalogModel } from '~/lib/model-catalog'

export interface SystemInfo {
	total_memory: number
	cpu_name: string
	cpu_cores: number
}

export function viewModel() {
	const location = useLocation()
	const [downloadProgress, setDownloadProgress] = useState(0)
	const [isOnline, setIsOnline] = useState<boolean | null>(null)
	/** True once a download started; until then the screen is the intro and the model list. */
	const [downloading, setDownloading] = useState(false)
	const [system, setSystem] = useState<SystemInfo | null>(null)
	const [hebrew, setHebrew] = useState(false)
	/** A link or a re-download skips the list: the user already said what to fetch. */
	const direct = Boolean(location?.state?.downloadURL || location?.state?.replacePath)
	const downloadProgressRef = useRef(0)
	const { setState: setErrorModal } = useContext(ErrorModalContext)
	const navigate = useNavigate()
	const preference = usePreferenceProvider()

	function handleProgressEvenets() {
		listen('download_progress', (event) => {
			// event.event is the event name (useful if you want to use a single callback fn for multiple event types)
			// event.payload is the payload object
			const [current, total] = event.payload as [number, number]
			const newDownloadProgress = Number(current / total) * 100

			if (newDownloadProgress > downloadProgressRef.current) {
				// for some reason it jumps if not
				setDownloadProgress(newDownloadProgress)
				downloadProgressRef.current = newDownloadProgress
			}
		})
	}

	async function readModelMetadata(modelPath: string) {
		return invoke<utils.ModelMetadata>('get_model_metadata', { modelPath }).catch((error) => {
			console.warn('No specialized GGUF metadata available:', error)
			return null
		})
	}

	async function ensureRequiredVad(metadata: utils.ModelMetadata | null, askFirst: boolean) {
		if (!metadata?.capabilities.requires_vad) return true
		const modelsFolder = await invoke<string>('get_models_folder')
		const vadPath = await join(modelsFolder, config.vadModelFilename)
		if (await utils.isModelFileUsable(vadPath)) return true

		const confirmed =
			!askFirst ||
			(await ask('This transcription model requires Silero VAD. Download it before selecting the model?', {
				title: 'Download required VAD model',
				kind: 'info',
			}))
		if (!confirmed) return false
		await invoke('download_model', { url: config.vadModelUrl, path: vadPath })
		return true
	}

	async function selectDownloadedModel(modelPath: string, askFirst: boolean) {
		const metadata = await readModelMetadata(modelPath)
		if (!(await ensureRequiredVad(metadata, askFirst))) return false
		preference.setModelMetadata(metadata)
		preference.setModelPath(modelPath)
		return true
	}

	async function downloadModel(chosen?: CatalogModel) {
		handleProgressEvenets()
		setDownloading(true)

		let lastError = null

		try {
			let urls: config.ModelDownload[] = []

			// Determine model URLs
			if (location?.state?.downloadURL) {
				// A URL the user supplied carries no size or hash, so the backend falls back to the
				// size and magic-byte checks it runs on every download.
				urls = [{ url: location.state.downloadURL }]
				console.log(`[model] Using provided model URL: ${urls[0].url}`)
			} else if (chosen) {
				// Turbo keeps its mirrors; every other catalog file has one home.
				urls = chosen.id === 'whisper-turbo' ? [...config.modelUrls.default] : [chosen.download]
			} else {
				urls = [...config.modelUrls.default]
				if (hebrew) urls.unshift(...config.modelUrls.hebrew)
			}

			// Try downloading from each URL
			for (const source of urls) {
				try {
					console.log(`[model] Attempting to download from: ${source.url}`)
					// Re-downloading a corrupt model overwrites it in place instead of dropping a
					// second, randomly named copy next to the broken one.
					const path = await utils.downloadModel(source, { replacePath: location?.state?.replacePath })
					if (!path) {
						console.log('[model] Download cancelled')
						return
					}
					console.log(`[model] Download succeeded: ${path}`)
					if (!(await selectDownloadedModel(path, !chosen))) {
						navigate('/#settings', { replace: true })
						return
					}
					navigate('/', { replace: true, state: { disableBack: true } })
					return
				} catch (err) {
					console.error(`[model] Failed to download from ${source.url}:`, err)
					lastError = err
				}
			}

			throw new Error(`All model downloads failed. Last error: ${lastError}`)
		} catch (err) {
			console.error(`[model] Unhandled error:`, err)
			setErrorModal?.({ open: true, log: String(err) })
		}
	}

	/** Checks the connection first; offline shows the retry dialog instead of a failed download. */
	async function downloadIfOnline(chosen?: CatalogModel) {
		const isOnlineResponse = await invoke<boolean>('is_online')
		if (isOnlineResponse) downloadModel(chosen)
		setIsOnline(isOnlineResponse)
		setPending(chosen)
	}

	const [pending, setPending] = useState<CatalogModel | undefined>()

	async function cancelSetup() {
		// Cancel and go to settings
		preference.setSkippedSetup(true)
		emit('abort_download')
		navigate('/#settings', { replace: true, state: { disableBack: true } })
	}

	useEffect(() => {
		if (direct) {
			downloadIfOnline()
			return
		}
		invoke<SystemInfo>('get_system_info')
			.then(setSystem)
			.catch((error) => {
				console.error('failed to read system info:', error)
				setSystem({ total_memory: 0, cpu_name: '', cpu_cores: 0 })
			})
		osExt
			.locale()
			.then((locale) => setHebrew(preference.displayLanguage === 'he-IL' || Boolean(locale?.endsWith('-IL')) || Boolean(locale?.startsWith('he'))))
			.catch(() => setHebrew(preference.displayLanguage === 'he-IL'))
	}, [])

	// Unknown memory (0) recommends as if the machine were small: the safe side.
	const recommended = system ? recommendModel(hebrew, system.total_memory) : MODEL_CATALOG[0]

	return {
		navigate,
		cancelSetup,
		setErrorModal,
		downloadProgress,
		downloadIfOnline,
		retry: () => downloadIfOnline(pending),
		downloading,
		direct,
		system,
		recommended,
		setDownloadProgress,
		downloadProgressRef,
		isOnline,
		location,
	}
}
