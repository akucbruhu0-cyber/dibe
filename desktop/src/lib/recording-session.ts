import { invoke } from '@tauri-apps/api/core'

/**
 * The recording started from the main screen, the recording shortcut or the meeting prompt. They are
 * one family: the shortcut toggles a recording the main screen started and vice versa, so each one
 * has to be able to stop the other's session. Dictation keeps its own session id in the hotkey provider.
 */
let normalSessionId: string | null = null
const startedListeners = new Set<(sessionId: string) => void>()

export function rememberNormalSession(sessionId: string) {
	normalSessionId = sessionId
	for (const listener of startedListeners) listener(sessionId)
}

/** Hears the id of each recording of this family as `start_record` returns it (the live transcript binds to it). */
export function onNormalSession(listener: (sessionId: string) => void) {
	startedListeners.add(listener)
	return () => void startedListeners.delete(listener)
}

/** A session that already finished is ignored by `stop_record`, so a stale id is harmless. */
export async function stopNormalSession() {
	if (normalSessionId) await invoke('stop_record', { sessionId: normalSessionId })
}

/** Backend errors arrive as `{ code, message }` objects, which `String()` would turn into "[object Object]". */
export function recordingErrorMessage(error: unknown): string {
	if (error instanceof Error) return error.message
	if (typeof error === 'object' && error !== null && 'message' in error) return String(error.message)
	return String(error)
}

/** `start_record` refuses with this code while another recording is running. */
export function isAlreadyRecording(error: unknown): boolean {
	return typeof error === 'object' && error !== null && 'code' in error && error.code === 'already_recording'
}
