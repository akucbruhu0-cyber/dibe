/** Who started a recording; mirrors `RecordingOrigin` in src-tauri/src/recording/session.rs. */
export type RecordingOrigin = 'manual' | 'shortcut' | 'dictation' | 'meeting'

/** Payloads of `record_finish` / `record_error`. Every listener hears both, so each filters by origin. */
export interface RecordFinishPayload {
	sessionId: string
	origin: RecordingOrigin
	path: string
	name: string
	warning?: string | null
}

export interface RecordErrorPayload {
	sessionId: string
	origin: RecordingOrigin
	message: string
}

/** Whether a recording event belongs to a caller that started recordings with the given origin(s). */
export function isMine(payload: { origin: RecordingOrigin }, mine: RecordingOrigin | RecordingOrigin[]): boolean {
	return Array.isArray(mine) ? mine.includes(payload.origin) : payload.origin === mine
}
