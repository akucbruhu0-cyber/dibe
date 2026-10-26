import { listen, type UnlistenFn } from '@tauri-apps/api/event'
import { useEffect } from 'react'
import { toast } from 'sonner'
import { m } from '~/paraglide/messages.js'

/** The backend's `transcribe_warning` payload: something asked for did not happen, the transcript did. */
interface TranscribeWarning {
	code: string
	message: string
}

/** Non-blocking notice for what the server could not do while still delivering the transcript. */
export function useTranscribeWarnings() {
	useEffect(() => {
		const unlisten: Promise<UnlistenFn> = listen<TranscribeWarning>('transcribe_warning', ({ payload }) => {
			if (payload.code === 'diarization_failed') {
				toast.warning(m.diarizationFailed(), { description: payload.message, position: 'bottom-center' })
			} else {
				toast.warning(payload.message, { position: 'bottom-center' })
			}
		})
		return () => {
			void unlisten.then((fn) => fn())
		}
	}, [])
}
