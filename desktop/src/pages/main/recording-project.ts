import { invoke } from '@tauri-apps/api/core'
import { toast } from 'sonner'
import { meetingDuring } from '~/lib/calendar'
import { autoProjectName } from '~/lib/project-name'
import type { RecordFinishPayload } from '~/lib/recording-origin'
import type { Segment } from '~/lib/transcript'
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
	/** When the recording began; with `createdAt` (its end), what a calendar event is matched against. */
	startedAt?: Date
	/** Its live transcript, kept as the project's until a transcription of the file replaces it. */
	segments?: Segment[]
	/** Transcribe the file even though it has a live transcript: the user asked to, or that one is incomplete. */
	again?: 'asked' | 'incomplete'
}

export interface RecordingProjectDeps {
	preference: Preference
	hydrate: TranscribeQueue['hydrate']
	transcribeJob: TranscribeQueue['transcribeJob']
}

/**
 * The project file of a recording that just finished. Its live transcript becomes the project's; it
 * is transcribed again when the user asked for that or the live one missed some of the audio, and
 * keeps the live words until that succeeds. A recording without live, or a kept live session that
 * heard nothing, leaves the usual way.
 */
export function finishedRecording(payload: RecordFinishPayload, preference: Pick<Preference, 'afterRecording'>): RecordingFile {
	const startedAt = payload.startedAt ? new Date(payload.startedAt) : undefined
	const file = { path: payload.path, name: payload.name, createdAt: new Date(), startedAt }
	if (!payload.liveSegments) return file
	const segments = payload.liveSegments.length ? payload.liveSegments : undefined
	if (preference.afterRecording === 'retranscribe') return { ...file, segments, again: 'asked' }
	return payload.liveComplete === false ? { ...file, segments, again: 'incomplete' } : { ...file, segments }
}

/** The title of a finished recording's warning; the backend's text goes under it. */
export function finishWarningTitle(payload: RecordFinishPayload): string {
	return payload.deviceDisconnected ? m.recordingDeviceDisconnected() : m.recordingRecoveredWarning()
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
	// A recording made during a calendar event takes its name; the AI title then leaves it be.
	const meeting = preference.calendarNaming && file.startedAt ? await meetingDuring(file.startedAt.getTime(), file.createdAt.getTime()) : null
	const name = meeting?.title || autoProjectName(file.name, 'record')
	const saved = await saveTranscript({
		name,
		sourcePath: file.path,
		projectsPath: preference.projectsPath,
		moveSourceMedia: true,
		segments: file.segments ?? [],
		language: preference.modelOptions.lang,
		modelPath: preference.modelPath,
		createdAt: file.createdAt,
		...(meeting ? { meeting } : {}),
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
		segments: file.segments ?? [],
		...(meeting ? { meeting } : {}),
	}
	const jobId = hydrate(record, saved.recordPath, saved.mediaPath, 'record')
	notifyTranscriptsChanged()
	if (!jobId) return true
	if (file.again === 'incomplete') toast.info(m.liveIncompleteRetranscribing(), { position: 'bottom-center' })
	if (file.again) transcribeJob(jobId, true)
	else if (preference.autoTranscribeAfterRecording && !file.segments) transcribeJob(jobId)
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
