import { invoke } from '@tauri-apps/api/core'
import { toast } from 'sonner'
import * as config from '~/lib/config'
import { gpuOutOfMemoryBefore } from '~/lib/gpu-memory'
import { isModelFileUsable } from '~/lib/model'
import type { RecordingOrigin } from '~/lib/recording-origin'
import { recordingErrorMessage } from '~/lib/recording-session'
import { m } from '~/paraglide/messages.js'
import type { Preference } from '~/providers/preference'

/** What `start_record` takes to transcribe while recording (`LiveOptions` in src-tauri/src/server/live.rs). */
export interface LiveOptions {
	language: string
	partials: boolean
	/** The Silero VAD model the server cuts the audio into utterances with. */
	vadModel: string
}

type LivePreference = Pick<Preference, 'liveTranscription' | 'modelPath' | 'gpuDevice' | 'noGpu' | 'unloadTimeoutMinutes' | 'modelOptions' | 'recentLanguages'>

/**
 * Live detection jumps between languages on short utterances, so a live session runs in one: the
 * chosen language, or with "auto" the one used last.
 */
function liveLanguage({ modelOptions, recentLanguages }: LivePreference) {
	if (modelOptions.lang && modelOptions.lang !== 'auto') return modelOptions.lang
	return recentLanguages[0]?.code ?? 'en'
}

/**
 * The `live` option of a recording, or null to record without it. Only recordings that become a
 * project go live; dictation is transcribed whole once it ends. Nothing here may stop a recording
 * from starting: every failure turns into null and a toast.
 */
export async function liveOptions(origin: RecordingOrigin, preference: LivePreference, downloadVad: () => void): Promise<LiveOptions | null> {
	if (origin === 'dictation' || !preference.liveTranscription) return null
	try {
		const { modelPath } = preference
		if (!modelPath) throw new Error(m.noModelSelected())
		// Not awaited: a cold model takes seconds, and the recording must start now. The live client
		// looks the server up behind the same lock this load holds, so its session waits for the
		// model; a load that fails shows up as a paused live transcription.
		invoke('load_model', {
			modelPath,
			gpuDevice: preference.gpuDevice,
			noGpu: preference.noGpu || gpuOutOfMemoryBefore(modelPath),
			unloadTimeoutMinutes: preference.unloadTimeoutMinutes,
		}).catch((error) => console.error('live transcription could not load the model:', error))
		const vadModel = `${await invoke<string>('get_models_folder')}/${config.vadModelFilename}`
		if (!(await isModelFileUsable(vadModel))) {
			toast.warning(m.liveNeedsVad(), {
				description: m.liveNeedsVadInfo(),
				// Long enough to act on: the download is the point of this toast.
				duration: 10_000,
				action: { label: m.downloadModel(), onClick: downloadVad },
			})
			return null
		}
		return { language: liveLanguage(preference), partials: true, vadModel }
	} catch (error) {
		console.error('live transcription is off for this recording:', error)
		toast.warning(m.liveUnavailable(), { description: recordingErrorMessage(error) })
		return null
	}
}
