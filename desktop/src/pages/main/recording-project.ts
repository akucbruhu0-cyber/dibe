import { invoke } from '@tauri-apps/api/core'
import { toast } from 'sonner'
import { autoProjectName } from '~/lib/project-name'
import { notifyTranscriptsChanged, saveTranscript, TRANSCRIPT_VERSION, type TranscriptRecord } from '~/lib/transcripts-store'
import { m } from '~/paraglide/messages.js'
import { getLocale } from '~/paraglide/runtime.js'
import type { Preference } from '~/providers/preference'
import type { TranscribeQueue } from './hooks/use-transcribe-queue'

/** A recording the backend left on disk, whether it just finished or was rebuilt after a crash. */
export interface RecordingFile {
	path: string
	/** File name, which the project name is derived from. */
	name: string
	createdAt: Date
}

export interface RecordingProjectDeps {
	preference: Preference
	hydrate: TranscribeQueue['hydrate']
	transcribeJob: TranscribeQueue['transcribeJob']
}

/** Mirrors `RecoveredRecording` in src-tauri/src/recording/recovery.rs. */
interface RecoveredRecording {
	path: string
	name: string
	/** RFC 3339 */
	recordedAt: string
}

/**
 * A recording becomes a durable project first. Transcription is an optional second step which
 * updates that same project, so a failed/disabled transcription never costs the user the audio.
 * Resolves to whether the project was saved.
 */
export async function createRecordingProject(file: RecordingFile, { preference, hydrate, transcribeJob }: RecordingProjectDeps): Promise<boolean> {
	const name = autoProjectName(file.name, 'record')
	const saved = await saveTranscript({
		name,
		sourcePath: file.path,
		projectsPath: preference.projectsPath,
		moveSourceMedia: true,
		segments: [],
		language: preference.modelOptions.lang,
		modelPath: preference.modelPath,
		createdAt: file.createdAt,
	})
	if (!saved) {
		const message = `Failed to save recording project; the recording remains at ${file.path}`
		console.error(message)
		toast.error(m.error(), { description: message, position: 'bottom-center' })
		return false
	}

	const record: TranscriptRecord = {
		version: TRANSCRIPT_VERSION,
		name,
		sourcePath: saved.mediaPath,
		createdAt: file.createdAt.toISOString(),
		language: preference.modelOptions.lang,
		modelPath: preference.modelPath,
		segments: [],
	}
	const jobId = hydrate(record, saved.recordPath, saved.mediaPath, 'record')
	notifyTranscriptsChanged()
	if (preference.autoTranscribeAfterRecording && jobId) transcribeJob(jobId)
	return true
}

/**
 * Makes a project of every recording that an earlier run never got to finish (the app crashed or
 * was force-quit mid-recording), the same way a finished recording becomes one. The backend keeps
 * each one in its recovered folder and offers it again at every launch until it is forgotten, so a
 * recording is forgotten only once its project exists, and one that fails here is simply offered
 * again next time without costing the others.
 */
export async function recoverUnfinishedRecordings(deps: { current: RecordingProjectDeps }) {
	let recovered: RecoveredRecording[]
	try {
		recovered = await invoke<RecoveredRecording[]>('take_recovered_recordings')
	} catch (error) {
		console.error('could not collect unfinished recordings:', error)
		return
	}
	for (const { path, name, recordedAt } of recovered) {
		const createdAt = new Date(recordedAt)
		try {
			if (!(await createRecordingProject({ path, name, createdAt }, deps.current))) continue
		} catch (error) {
			console.error(`could not make a project of the recovered recording ${path}:`, error)
			continue
		}
		await invoke('forget_recovered', { path }).catch((error) => console.error(`could not forget the recovered recording ${path}:`, error))
		const date = createdAt.toLocaleDateString(getLocale(), { dateStyle: 'medium' })
		toast.info(m.unfinishedRecordingRecovered({ date }), { position: 'bottom-center' })
	}
}
