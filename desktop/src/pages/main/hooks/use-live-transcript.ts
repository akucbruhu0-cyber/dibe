import { invoke } from '@tauri-apps/api/core'
import { listen } from '@tauri-apps/api/event'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { toast } from 'sonner'
import { m } from '~/paraglide/messages.js'
import type { RecordErrorPayload, RecordFinishPayload } from '~/lib/recording-origin'
import { onNormalSession } from '~/lib/recording-session'
import type { Segment } from '~/lib/transcript'

/** What the server says about the audio, in seconds of the recording (`LiveEvent` in src-tauri/src/server/live.rs). */
export type LiveEvent = { type: 'final' | 'partial'; start: number; end: number; text: string } | { type: 'warning'; code: string; message: string }

/** `live_update`: one push's answer. */
export interface LiveUpdate {
	sessionId: string
	events: LiveEvent[]
	/** How far the text is behind the speech. */
	lag_ms: number
}

/** `live_status`: sent only when the state or its message changes. */
export interface LiveStatus {
	sessionId: string
	state: 'starting' | 'live' | 'paused' | 'ended'
	message?: string | null
	/** Why it ended, for the UI to word: `unsupported` is a server from before live transcription. */
	code?: string | null
}

export interface LiveState {
	/** Only ever grows, in place: see `liveReducer`. */
	finals: Segment[]
	partial: Segment | null
	status: 'off' | LiveStatus['state']
	lagMs: number
	message?: string
}

/**
 * What the reader typed over finals, by their index in `LiveState.finals`. The start time rides along
 * so an edit only ever lands on the line it was made on.
 */
export type LiveEdits = Map<number, { start: number; text: string }>

/** What the recording screen shows and does with the live transcription. */
export interface LiveView {
	state: LiveState
	/** The bound session's edits; changes in place, like the finals. */
	edits: LiveEdits
	/** Replaces final `index`'s text with what the reader typed. */
	edit: (index: number, text: string) => void
	/** The recording the transcript belongs to; null while none is bound. */
	sessionId: string | null
	/** "Try again" was pressed and nothing has been heard since. */
	retrying: boolean
	retry: () => void
}

/** A retry that fails the same way sends no status at all; the button comes back after this. */
const RETRY_PENDING_MS = 5000
const QUEUE_WAIT_TOAST = 'queue-waits-for-live'

function initialState(): LiveState {
	return { finals: [], partial: null, status: 'off', lagMs: 0 }
}

/** Server seconds to transcript centiseconds. */
function segment(event: { start: number; end: number; text: string }): Segment {
	return { start: Math.round(event.start * 100), stop: Math.round(event.end * 100), text: event.text }
}

/**
 * The live transcript after one event. A final ends the partial it came from; a partial replaces
 * the last one, and an empty one clears it. A partial shows only while live: a session lost in a
 * pause never clears its own. Finals are appended to `state.finals` in place, so an hours-long
 * transcript is never copied per event: the caller keeps the state in a ref and re-renders by a
 * version counter (never `useReducer`, whose dev double-call would append twice).
 */
export function liveReducer(state: LiveState, event: LiveUpdate | LiveStatus): LiveState {
	if (!('events' in event)) {
		const partial = event.state === 'live' ? state.partial : null
		return { ...state, status: event.state, message: event.message ?? undefined, partial }
	}
	let partial = state.partial
	for (const item of event.events) {
		if (item.type === 'final') {
			state.finals.push(segment(item))
			partial = null
		} else if (item.type === 'partial') {
			partial = item.text.trim() ? segment(item) : null
		}
	}
	return { ...state, partial, lagMs: event.lag_ms }
}

// By session, not in the hook's state: the recording's project is made from them after its
// record_finish, which may reach the project's listener after this hook has reset.
const editsBySession = new Map<string, LiveEdits>()
const noEdits: LiveEdits = new Map()

function editsOf(sessionId: string): LiveEdits {
	let edits = editsBySession.get(sessionId)
	if (!edits) editsBySession.set(sessionId, (edits = new Map()))
	return edits
}

/** The session's edits, handed over once: the recording's project takes them. */
export function takeLiveEdits(sessionId: string): LiveEdits {
	const edits = editsBySession.get(sessionId) ?? new Map()
	editsBySession.delete(sessionId)
	return edits
}

/** `segments` with the reader's edits; a line emptied by hand is gone. */
export function applyLiveEdits<T extends Segment>(segments: T[], edits: LiveEdits): T[] {
	if (edits.size === 0) return segments
	return segments.flatMap((segment, index) => {
		const edit = edits.get(index)
		if (!edit || edit.start !== segment.start) return [segment]
		return edit.text.trim() ? [{ ...segment, text: edit.text }] : []
	})
}

// The file queue waits while a live session runs: the server lends its model to the session, and a
// session outlasts the queue's own retry on a busy server.
let liveActive = false
const idleWaiters: (() => void)[] = []

function setLiveActive(active: boolean) {
	liveActive = active
	if (active) return
	toast.dismiss(QUEUE_WAIT_TOAST)
	for (const resolve of idleWaiters.splice(0)) resolve()
}

/** Resolves once no live session runs, saying so while the queue waits. */
export function waitForLiveIdle(): Promise<void> {
	if (!liveActive) return Promise.resolve()
	toast.info(m.queueWaitsForLive(), { id: QUEUE_WAIT_TOAST, duration: Infinity })
	return new Promise((resolve) => idleWaiters.push(resolve))
}

/**
 * The live transcript of the recording the main screen, the shortcut or the meeting prompt started.
 * One recording runs at a time, but its first statuses come before `start_record` returns its id, so
 * events are held by session until the id is known, then replayed. A session is done for at its
 * `record_finish`/`record_error`: a stop that gave up waiting leaves its client to send late answers.
 */
export function useLiveTranscript(): LiveView {
	const stateRef = useRef<LiveState>(initialState())
	const [version, setVersion] = useState(0)
	const [sessionId, setSessionId] = useState<string | null>(null)
	const [retrying, setRetrying] = useState(false)
	const retryTimer = useRef(0)

	const stopRetrying = useCallback(() => {
		window.clearTimeout(retryTimer.current)
		setRetrying(false)
	}, [])

	useEffect(() => {
		let bound: string | null = null
		const pending = new Map<string, (LiveUpdate | LiveStatus)[]>()
		const finished = new Set<string>()
		const warned = new Set<string>()

		const apply = (event: LiveUpdate | LiveStatus) => {
			if ('events' in event) {
				for (const item of event.events) {
					if (item.type !== 'warning' || warned.has(item.code)) continue
					warned.add(item.code)
					toast.warning(m.liveWarning(), { id: `live-warning-${item.code}`, description: item.message })
				}
			} else {
				stopRetrying()
				if (event.code === 'unsupported') toast.warning(m.liveUnsupported(), { id: 'live-unsupported', description: m.liveNeedsVadInfo() })
			}
			stateRef.current = liveReducer(stateRef.current, event)
			setVersion((current) => current + 1)
		}

		const receive = (event: LiveUpdate | LiveStatus) => {
			if (finished.has(event.sessionId)) return
			if (!('events' in event)) setLiveActive(event.state !== 'ended')
			if (bound === event.sessionId) apply(event)
			else if (bound === null) pending.set(event.sessionId, [...(pending.get(event.sessionId) ?? []), event])
		}

		const reset = (id: string | null) => {
			bound = id
			warned.clear()
			stateRef.current = initialState()
			setSessionId(id)
			stopRetrying()
			setVersion((current) => current + 1)
		}

		const bind = (id: string) => {
			if (finished.has(id) || bound === id) return
			reset(id)
			const replay = pending.get(id) ?? []
			pending.clear()
			replay.forEach(apply)
		}

		const end = ({ payload }: { payload: RecordFinishPayload | RecordErrorPayload }) => {
			finished.add(payload.sessionId)
			pending.delete(payload.sessionId)
			setLiveActive(false)
			if (bound === payload.sessionId) reset(null)
		}

		const unlisteners = [
			listen<LiveUpdate>('live_update', ({ payload }) => receive(payload)),
			listen<LiveStatus>('live_status', ({ payload }) => receive(payload)),
			listen<RecordFinishPayload>('record_finish', end),
			listen<RecordErrorPayload>('record_error', end),
		]
		const unbind = onNormalSession(bind)
		return () => {
			unbind()
			for (const unlisten of unlisteners) void unlisten.then((dispose) => dispose())
			// Nobody hears the session end any more; the queue must not wait for it forever.
			setLiveActive(false)
			window.clearTimeout(retryTimer.current)
		}
	}, [stopRetrying])

	const edit = useCallback(
		(index: number, text: string) => {
			const line = stateRef.current.finals[index]
			if (sessionId && line) editsOf(sessionId).set(index, { start: line.start, text })
		},
		[sessionId],
	)

	const retry = useCallback(() => {
		if (!sessionId) return
		setRetrying(true)
		window.clearTimeout(retryTimer.current)
		retryTimer.current = window.setTimeout(() => setRetrying(false), RETRY_PENDING_MS)
		invoke('retry_live', { sessionId }).catch((error) => {
			console.error('retry_live failed:', error)
			stopRetrying()
		})
	}, [sessionId, stopRetrying])

	// `version` stands in for the finals, which change in place.
	const edits = sessionId ? editsOf(sessionId) : noEdits
	return useMemo(() => ({ state: stateRef.current, edits, edit, sessionId, retrying, retry }), [version, edits, edit, sessionId, retrying, retry])
}
