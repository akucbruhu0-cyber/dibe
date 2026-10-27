// @vitest-environment jsdom

import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { rememberNormalSession } from '~/lib/recording-session'
import { liveReducer, useLiveTranscript, waitForLiveIdle, type LiveState, type LiveStatus, type LiveUpdate } from './use-live-transcript'

const mocks = vi.hoisted(() => ({
	listeners: new Map<string, (event: { payload: unknown }) => void>(),
	invoke: vi.fn(),
	toast: { warning: vi.fn(), info: vi.fn(), dismiss: vi.fn() },
}))
vi.mock('@tauri-apps/api/event', () => ({
	listen: vi.fn(async (name: string, callback: (event: { payload: unknown }) => void) => {
		mocks.listeners.set(name, callback)
		return () => mocks.listeners.delete(name)
	}),
}))
vi.mock('@tauri-apps/api/core', () => ({ invoke: mocks.invoke }))
vi.mock('sonner', () => ({ toast: mocks.toast }))

const off = (): LiveState => ({ finals: [], partial: null, status: 'off', lagMs: 0 })
const update = (events: LiveUpdate['events'], sessionId = 's1', lag_ms = 0): LiveUpdate => ({ sessionId, events, lag_ms })
const status = (state: LiveStatus['state'], sessionId = 's1', message: string | null = null): LiveStatus => ({ sessionId, state, message })
const partial = (text: string, start = 1) => ({ type: 'partial' as const, start, end: start + 1, text })
const final = (text: string, start = 1) => ({ type: 'final' as const, start, end: start + 1, text })

/** Delivers a backend event the way Tauri would, once the hook listens. */
function emit(name: string, payload: unknown) {
	act(() => mocks.listeners.get(name)?.({ payload }))
}

async function mounted() {
	const hook = renderHook(() => useLiveTranscript())
	// `listen` resolves asynchronously; let the effect register every listener.
	await act(async () => {})
	return hook
}

beforeEach(() => {
	mocks.listeners.clear()
	vi.clearAllMocks()
	mocks.invoke.mockResolvedValue(undefined)
})
afterEach(() => {
	cleanup()
	vi.useRealTimers()
})

describe('liveReducer', () => {
	it('replaces the partial with the next one', () => {
		let state = liveReducer(off(), update([partial('שלום')]))
		state = liveReducer(state, update([partial('שלום לכולם')]))

		expect(state.partial?.text).toBe('שלום לכולם')
		expect(state.finals).toEqual([])
	})

	it('clears the partial on an empty one', () => {
		const state = liveReducer(liveReducer(off(), update([partial('שלום')])), update([partial('')]))

		expect(state.partial).toBeNull()
	})

	it('appends a final, in centiseconds, and clears the partial', () => {
		let state = liveReducer(off(), update([partial('שלום')]))
		state = liveReducer(state, update([final('שלום לכולם', 1.5)], 's1', 1200))

		expect(state.finals).toEqual([{ start: 150, stop: 250, text: 'שלום לכולם' }])
		expect(state.partial).toBeNull()
		expect(state.lagMs).toBe(1200)
	})

	it('keeps the finals when the session ends', () => {
		const state = liveReducer(liveReducer(off(), update([final('אחת'), final('שתיים', 3)])), status('ended'))

		expect(state.status).toBe('ended')
		expect(state.finals.map((segment) => segment.text)).toEqual(['אחת', 'שתיים'])
	})

	it('keeps the message of a pause, and drops it once live again', () => {
		const paused = liveReducer(liveReducer(off(), update([partial('שלום')])), status('paused', 's1', 'no model loaded'))
		expect(paused).toMatchObject({ status: 'paused', message: 'no model loaded', partial: null })

		expect(liveReducer(paused, status('live')).message).toBeUndefined()
	})
})

describe('useLiveTranscript', () => {
	it('buffers the events that come before start_record returns, and replays them for its id', async () => {
		const { result } = await mounted()

		emit('live_status', status('starting'))
		emit('live_update', update([partial('שלום')]))
		expect(result.current.state.status).toBe('off')

		act(() => rememberNormalSession('s1'))

		expect(result.current.sessionId).toBe('s1')
		expect(result.current.state.status).toBe('starting')
		expect(result.current.state.partial?.text).toBe('שלום')
	})

	it('ignores events of another session', async () => {
		const { result } = await mounted()
		act(() => rememberNormalSession('s1'))

		emit('live_update', update([final('לא שלי')], 'other'))
		emit('live_update', update([final('שלי')]))

		expect(result.current.state.finals.map((segment) => segment.text)).toEqual(['שלי'])
	})

	it('ignores what a session sends after its record_finish', async () => {
		const { result } = await mounted()
		act(() => rememberNormalSession('s1'))
		emit('live_update', update([final('לפני')]))

		emit('record_finish', { sessionId: 's1', origin: 'manual', path: '/r.wav', name: 'r.wav', liveSegments: [] })
		// A stop that gave up waiting leaves the client running; its late answers are not ours.
		emit('live_update', update([final('אחרי')]))
		emit('live_status', status('ended'))

		expect(result.current.sessionId).toBeNull()
		expect(result.current.state).toMatchObject({ status: 'off', finals: [] })
	})

	it('shows a warning once per code in a session', async () => {
		await mounted()
		act(() => rememberNormalSession('s1'))

		const gap = { type: 'warning' as const, code: 'gap', message: 'skipped some audio' }
		emit('live_update', update([gap]))
		emit('live_update', update([gap]))

		expect(mocks.toast.warning).toHaveBeenCalledTimes(1)
		expect(mocks.toast.warning).toHaveBeenCalledWith(expect.any(String), expect.objectContaining({ description: 'skipped some audio' }))
	})

	it('retries the bound session and stays pending until the next status, or 5 s', async () => {
		vi.useFakeTimers()
		const { result } = await mounted()
		act(() => rememberNormalSession('s1'))
		emit('live_status', status('paused', 's1', 'unreachable'))

		act(() => result.current.retry())
		expect(mocks.invoke).toHaveBeenCalledWith('retry_live', { sessionId: 's1' })
		expect(result.current.retrying).toBe(true)

		emit('live_status', status('live'))
		expect(result.current.retrying).toBe(false)

		// A retry that fails the same way sends nothing at all.
		emit('live_status', status('paused', 's1', 'unreachable'))
		act(() => result.current.retry())
		expect(result.current.retrying).toBe(true)
		act(() => vi.advanceTimersByTime(5000))
		expect(result.current.retrying).toBe(false)
	})
})

describe('waitForLiveIdle', () => {
	it('holds the file queue while a live session runs, and lets it go when it ends', async () => {
		await mounted()
		emit('live_status', status('starting'))

		let idle = false
		void waitForLiveIdle().then(() => (idle = true))
		await act(async () => {})
		expect(idle).toBe(false)
		expect(mocks.toast.info).toHaveBeenCalledTimes(1)

		emit('live_status', status('ended'))
		await act(async () => {})
		expect(idle).toBe(true)
	})

	it('lets the queue go at record_finish even without an ended status', async () => {
		await mounted()
		emit('live_status', status('live', 's2'))

		let idle = false
		void waitForLiveIdle().then(() => (idle = true))
		emit('record_finish', { sessionId: 's2', origin: 'shortcut', path: '/r.wav', name: 'r.wav', liveSegments: null })
		await act(async () => {})

		expect(idle).toBe(true)
	})

	it('says once, in the app language, that the server has no live transcription, and lets the queue go', async () => {
		await mounted()
		emit('live_status', status('starting'))
		act(() => rememberNormalSession('s1'))
		let idle = false
		void waitForLiveIdle().then(() => (idle = true))

		emit('live_status', { ...status('ended', 's1', "This server version doesn't support live transcription"), code: 'unsupported' })
		await act(async () => {})

		expect(idle).toBe(true)
		expect(mocks.toast.warning).toHaveBeenCalledWith(
			"This server version doesn't support live transcription",
			expect.objectContaining({ id: 'live-unsupported' }),
		)
	})

	it('does not wait when nothing is live', async () => {
		await expect(waitForLiveIdle()).resolves.toBeUndefined()
	})
})
