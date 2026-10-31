import { invoke } from '@tauri-apps/api/core'
import { listen, type UnlistenFn } from '@tauri-apps/api/event'
import { webviewWindow } from '@tauri-apps/api'
import * as dialog from '@tauri-apps/plugin-dialog'
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { m } from '~/paraglide/messages.js'
import * as config from '~/lib/config'
import { pathToNamedPath } from '~/lib/fs'
import { cleanupPartialDownloads, listInstalledModels, type InstalledModel } from '~/lib/model'
import { isMine, type RecordErrorPayload, type RecordFinishPayload, type RecordingOrigin } from '~/lib/recording-origin'
import { pauseNormalSession, recordingErrorMessage } from '~/lib/recording-session'
import type { NamedPath, ProjectSource } from '~/lib/types'
import { useConfirmExit } from '~/lib/use-confirm-exit'
import { useRecordingShortcut } from '~/providers/recording-shortcut'
import { ErrorModalContext } from '~/providers/error-modal'
import { usePreferenceProvider, type Preference } from '~/providers/preference'
import { useAudioDownload } from '~/pages/home/hooks/use-audio-download'
import { useRecording } from '~/pages/home/hooks/use-recording'
import { useDropTarget } from './hooks/use-drop-target'
import { useSummaries, type Summaries } from './hooks/use-summaries'
import { useTranscribeQueue, type TranscribeQueue } from './hooks/use-transcribe-queue'
import { useTranscribeWarnings } from './hooks/use-transcribe-warnings'
import { applyLiveEdits, takeLiveEdits, useLiveTranscript, type LiveView } from './hooks/use-live-transcript'
import { createRecordingProject, finishedRecording, finishWarningTitle, recoverUnfinishedRecordings } from './recording-project'

/** Recordings that end up as a project here; dictation is handled by the hotkey provider. */
const PROJECT_RECORDING_ORIGINS: RecordingOrigin[] = ['manual', 'shortcut', 'meeting']

export type SessionMode = 'idle' | 'working' | 'done'
export type IdlePanel = 'none' | 'record' | 'link'

type Recording = ReturnType<typeof useRecording>
type AudioDownload = ReturnType<typeof useAudioDownload>

export interface Session {
	mode: SessionMode
	queue: TranscribeQueue
	/** AI summaries of finished transcripts (settings → Summarize). */
	summaries: Summaries
	preference: Preference
	dragging: boolean
	panel: IdlePanel
	setPanel: (panel: IdlePanel) => void
	recording: Recording
	/** Seconds recorded, pauses left out. */
	recordElapsed: number
	recordPaused: boolean
	toggleRecordPause: () => Promise<void>
	/** The running recording's screen is on top, though a transcript is open behind it. */
	liveInFront: boolean
	/** Brings the running recording's screen back, leaving the open transcript as it is. */
	showLive: () => void
	/** The transcript of the running recording, as it is spoken. */
	live: LiveView
	link: AudioDownload
	collectingFolder: boolean
	browse: () => Promise<void>
	startNew: () => void
}

const SessionContext = createContext<Session | null>(null)

export function useSession() {
	const session = useContext(SessionContext)
	if (!session) throw new Error('useSession must be used inside <SessionProvider>')
	return session
}

const mediaExtensions = [...config.audioExtensions, ...config.videoExtensions]

export function SessionProvider({ children }: { children: ReactNode }) {
	const navigate = useNavigate()
	const preference = usePreferenceProvider()
	const recordingShortcut = useRecordingShortcut()
	const { setState: setErrorModal } = useContext(ErrorModalContext)
	const queue = useTranscribeQueue()
	const summaries = useSummaries(queue)
	useTranscribeWarnings()
	const live = useLiveTranscript()
	// Live transcription is what the app is mostly opened for, so it opens on the recording panel.
	const [panel, setPanel] = useState<IdlePanel>('record')
	const [collectingFolder, setCollectingFolder] = useState(false)
	const [recordElapsed, setRecordElapsed] = useState(0)
	const [recordPaused, setRecordPaused] = useState(false)
	const [liveInFront, setLiveInFront] = useState(false)

	const enqueueRef = useRef(queue.enqueue)
	useEffect(() => {
		enqueueRef.current = queue.enqueue
	}, [queue.enqueue])
	const recordingCompletionRef = useRef({
		hydrate: queue.hydrate,
		transcribeJob: queue.transcribeJob,
		preference,
	})
	useEffect(() => {
		recordingCompletionRef.current = { hydrate: queue.hydrate, transcribeJob: queue.transcribeJob, preference }
	}, [preference, queue.hydrate, queue.transcribeJob])

	const enqueuePaths = useCallback(
		async (paths: string[], source: ProjectSource) => {
			const files: NamedPath[] = []
			for (const item of paths) {
				// A picked or dropped path may be a folder — detect and expand to its media files.
				const isMediaFile = mediaExtensions.some((ext) => item.toLowerCase().endsWith(`.${ext.toLowerCase()}`))
				if (isMediaFile) {
					files.push({ ...(await pathToNamedPath(item)), source })
					continue
				}
				setCollectingFolder(true)
				try {
					const expanded = await invoke<string[]>('glob_files', {
						folder: item,
						patterns: mediaExtensions,
						recursive: preference.advancedTranscribeOptions.includeSubFolders,
					})
					for (const path of expanded) files.push({ ...(await pathToNamedPath(path)), source })
				} catch {
					files.push({ ...(await pathToNamedPath(item)), source })
				} finally {
					setCollectingFolder(false)
				}
			}
			if (files.length) enqueueRef.current(files)
		},
		[preference.advancedTranscribeOptions.includeSubFolders],
	)

	const dragging = useDropTarget(
		useCallback(
			(paths: string[]) => {
				setPanel('none')
				void enqueuePaths(paths, 'file')
			},
			[enqueuePaths],
		),
	)

	/** Downloaded files remain explicitly URL-sourced after they land on disk. */
	const transcribeDownloads = useCallback(
		async (paths: string[]) => {
			await enqueuePaths(paths, 'url')
		},
		[enqueuePaths],
	)

	const manualRecording = useRecording(() => setPanel('record'))
	const recording = useMemo(
		() => ({ ...manualRecording, isRecording: manualRecording.isRecording || recordingShortcut.isShortcutRecording }),
		[manualRecording, recordingShortcut.isShortcutRecording],
	)
	const link = useAudioDownload(transcribeDownloads)

	useEffect(() => {
		recordingShortcut.setNormalRecordingActive(recording.isRecording)
		return () => recordingShortcut.setNormalRecordingActive(false)
	}, [recording.isRecording, recordingShortcut.setNormalRecordingActive])

	useEffect(() => {
		if (recordingShortcut.isShortcutRecording) setPanel('record')
	}, [recordingShortcut.isShortcutRecording])

	/** Milliseconds recorded before the clock's current run. */
	const recordedBefore = useRef(0)
	useEffect(() => {
		setRecordElapsed(0)
		setRecordPaused(false)
		recordedBefore.current = 0
	}, [recording.isRecording])
	// The clock stops at Stop, what follows is saving, and at a pause, which records nothing.
	useEffect(() => {
		if (!recording.isRecording || recording.isFinishing || recordPaused) return
		const runningSince = Date.now()
		const timer = window.setInterval(() => setRecordElapsed(Math.floor((recordedBefore.current + Date.now() - runningSince) / 1000)), 500)
		return () => {
			window.clearInterval(timer)
			recordedBefore.current += Date.now() - runningSince
		}
	}, [recording.isRecording, recording.isFinishing, recordPaused])

	// Opening a transcript (every open is a new job) puts it in front; so does the recording's end.
	useEffect(() => setLiveInFront(false), [queue.selectedId, recording.isRecording])
	const showLive = useCallback(() => {
		setPanel('record')
		setLiveInFront(true)
	}, [])

	const toggleRecordPause = useCallback(async () => {
		try {
			await pauseNormalSession(!recordPaused)
			setRecordPaused(!recordPaused)
		} catch (error) {
			console.error('pause_record failed:', error)
			toast.error(recordingErrorMessage(error))
		}
	}, [recordPaused])

	// A finished recording becomes a durable project (see createRecordingProject).
	useEffect(() => {
		const unlisten: Promise<UnlistenFn> = listen<RecordFinishPayload>('record_finish', async ({ payload }) => {
			if (!isMine(payload, PROJECT_RECORDING_ORIGINS)) return
			recording.setIsRecording(false)
			setPanel('record')
			if (payload.warning) toast.warning(finishWarningTitle(payload), { description: payload.warning, position: 'bottom-center' })
			const deps = recordingCompletionRef.current
			// What the reader corrected while it was live is what the project keeps.
			const edits = takeLiveEdits(payload.sessionId)
			const liveSegments = payload.liveSegments && applyLiveEdits(payload.liveSegments, edits)
			await createRecordingProject(finishedRecording({ ...payload, liveSegments }, deps.preference), deps)
		})
		return () => {
			unlisten.then((fn) => fn())
		}
	}, [recording.setIsRecording])

	// A recording the previous run never got to stop (crash, force-quit) is rebuilt by the backend at
	// launch; each one becomes a project here. It is handed over once, so this must not be cancelled.
	useEffect(() => {
		void recoverUnfinishedRecordings(recordingCompletionRef)
	}, [])

	useEffect(() => {
		const unlisten: Promise<UnlistenFn> = listen<RecordErrorPayload>('record_error', ({ payload }) => {
			if (!isMine(payload, PROJECT_RECORDING_ORIGINS)) return
			const message = payload.message || m.error()
			recording.setIsRecording(false)
			setPanel('record')
			toast.error(m.error(), { description: message, position: 'bottom-center' })
			setErrorModal?.({ log: message, open: true })
		})
		return () => {
			unlisten.then((fn) => fn())
		}
	}, [recording.setIsRecording, setErrorModal])

	const browse = useCallback(async () => {
		/**
		 * One picker for both: macOS' open panel takes files and folders together, so the native
		 * command handles it there. Everywhere else it returns null and the plugin dialog (files
		 * only) stands in — folders still arrive by drag and drop.
		 */
		let picked: string[] | null = null
		try {
			picked = await invoke<string[] | null>('pick_media_paths', { extensions: mediaExtensions })
		} catch (error) {
			console.warn('native picker unavailable:', error)
		}
		if (!picked) {
			const selected = await dialog.open({ multiple: true, filters: [{ name: 'Audio or Video files', extensions: mediaExtensions }] })
			if (!selected) return
			picked = Array.isArray(selected) ? selected : [selected]
		}
		if (!picked.length) return
		setPanel('none')
		await enqueuePaths(picked, 'file')
	}, [enqueuePaths])

	const startNew = useCallback(() => {
		queue.reset()
		setPanel('record')
	}, [queue])

	// Same guards the old home page ran on mount.
	useEffect(() => {
		async function checkIfCrashedRecently() {
			try {
				if (await invoke<boolean>('is_crashed_recently')) {
					dialog.message(m.crashedRecently())
					await invoke('rename_crash_file')
				}
			} catch (error) {
				console.error(error)
			}
		}

		/** A truncated or corrupt file is not a model, so say so instead of letting server fail on it. */
		function reportCorruptModels(corrupt: InstalledModel[]) {
			for (const model of corrupt) {
				console.error(`corrupt model file ${model.path}: ${model.reason}`)
				toast.error(m.modelFileCorrupt(), {
					description: m.modelFileCorruptDescription({ name: model.name }),
					action: {
						label: m.reDownload(),
						// Re-downloading writes over the broken file rather than beside it.
						onClick: () => navigate('/setup', { state: { replacePath: model.path } }),
					},
				})
			}
		}

		async function checkModelExists() {
			try {
				const modelsFolder = await invoke<string>('get_models_folder')
				// Partial downloads cannot be resumed, so a leftover `.part` is only wasted space.
				await cleanupPartialDownloads(modelsFolder)
				const installed = await listInstalledModels(modelsFolder)
				const models = installed.filter((model) => model.valid)
				reportCorruptModels(installed.filter((model) => !model.valid))
				if (models.length === 0) {
					preference.setModelPath(null)
					if (!preference.skippedSetup) navigate('/setup')
					return
				}
				if (!preference.modelPath || !models.some((model) => model.path === preference.modelPath)) {
					preference.setModelPath(models[0].path)
				}
			} catch (error) {
				console.error(error)
				navigate('/setup')
			}
		}

		async function showWindow() {
			const currentWindow = webviewWindow.getCurrentWebviewWindow()
			await currentWindow.show()
			if (import.meta.env.PROD) await currentWindow.setFocus()
		}

		void checkIfCrashedRecently()
		void checkModelExists()
		void showWindow()
	}, [])

	// Results persist the moment they exist, so closing only warns when something would
	// actually be lost: a run in flight, finished results that never reached disk (saving
	// disabled or failed), or a recording that is running or still being saved.
	const hasUnsavedResults = queue.jobs.some((job) => job.status === 'done' && !job.hydrated && !job.savedPath)
	useConfirmExit(preference.closeToTray, queue.running || hasUnsavedResults || recording.isRecording)

	const mode: SessionMode = queue.jobs.length === 0 ? 'idle' : queue.running || queue.jobs.some((job) => job.status === 'queued') ? 'working' : 'done'

	const value = useMemo<Session>(
		() => ({
			mode,
			queue,
			summaries,
			preference,
			dragging,
			panel,
			setPanel,
			recording,
			recordElapsed,
			recordPaused,
			toggleRecordPause,
			liveInFront,
			showLive,
			live,
			link,
			collectingFolder,
			browse,
			startNew,
		}),
		[
			mode,
			queue,
			summaries,
			preference,
			dragging,
			panel,
			recording,
			recordElapsed,
			recordPaused,
			toggleRecordPause,
			liveInFront,
			showLive,
			live,
			link,
			collectingFolder,
			browse,
			startNew,
		],
	)

	return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>
}
