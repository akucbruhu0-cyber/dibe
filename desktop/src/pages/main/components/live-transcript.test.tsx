// @vitest-environment jsdom

import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { rememberNormalSession } from '~/lib/recording-session'
import { m } from '~/paraglide/messages.js'
import { useLiveTranscript, type LiveState, type LiveView } from '../hooks/use-live-transcript'
import { LiveTranscript } from './live-transcript'

const mocks = vi.hoisted(() => ({
	listeners: new Map<string, (event: { payload: unknown }) => void>(),
	invoke: vi.fn(),
}))
vi.mock('@tauri-apps/api/event', () => ({
	listen: vi.fn(async (name: string, callback: (event: { payload: unknown }) => void) => {
		mocks.listeners.set(name, callback)
		return () => mocks.listeners.delete(name)
	}),
}))
vi.mock('@tauri-apps/api/core', () => ({ invoke: mocks.invoke }))
vi.mock('sonner', () => ({ toast: { warning: vi.fn(), info: vi.fn(), dismiss: vi.fn() } }))

const MIXED = 'שלום John, נפגשים ב-14:30'

function view(state: Partial<LiveState>): LiveView {
	return {
		state: { finals: [], partial: null, status: 'live', lagMs: 0, ...state },
		sessionId: 's1',
		retrying: false,
		retry: vi.fn(),
	}
}

const twoLines = view({
	finals: [{ start: 0, stop: 150, text: MIXED }],
	partial: { start: 150, stop: 300, text: 'עוד רגע' },
})

function reduceMotion(reduce: boolean) {
	window.matchMedia = ((query: string) => ({ matches: reduce && query.includes('reduce'), media: query })) as unknown as typeof window.matchMedia
}

/** The rendered transcript lines, final and partial. */
function lines() {
	return [...document.querySelectorAll<HTMLElement>('[data-live-line]')]
}

beforeAll(() => {
	// jsdom lays nothing out; give the virtualized list a viewport so it renders rows.
	Object.defineProperty(HTMLElement.prototype, 'offsetHeight', { configurable: true, get: () => 400 })
	Object.defineProperty(HTMLElement.prototype, 'offsetWidth', { configurable: true, get: () => 400 })
})
beforeEach(() => {
	mocks.listeners.clear()
	mocks.invoke.mockReset().mockResolvedValue(undefined)
	reduceMotion(false)
})
afterEach(cleanup)

describe('LiveTranscript', () => {
	it('lets every line find its own direction, with the time isolated as LTR', () => {
		render(<LiveTranscript live={twoLines} />)

		expect(lines()).toHaveLength(2)
		for (const line of lines()) {
			expect(line.getAttribute('dir')).toBe('auto')
			expect(line.querySelector('bdi[dir="ltr"]')?.textContent).toMatch(/^\d\d:\d\d$/)
		}
	})

	it('keeps mixed Hebrew, English and a time intact', () => {
		render(<LiveTranscript live={twoLines} />)

		expect(lines()[0].textContent).toContain(MIXED)
	})

	it('greys the partial line only', () => {
		render(<LiveTranscript live={twoLines} />)

		const [finalLine, partialLine] = lines()
		expect(partialLine.className).toContain('text-muted-foreground')
		expect(finalLine.className).not.toContain('text-muted-foreground')
	})

	it('has exactly one status region, and the lines are not one', () => {
		render(<LiveTranscript live={twoLines} />)

		const regions = screen.getAllByRole('status')
		expect(regions).toHaveLength(1)
		expect(regions[0].getAttribute('aria-atomic')).toBe('true')
		expect(document.querySelectorAll('[aria-live]')).toHaveLength(0)
	})

	it('animates the settling final and the recording dot, unless motion is reduced', () => {
		const animated = /live-settle|live-pulse|transition|animate-/
		const { unmount } = render(<LiveTranscript live={twoLines} />)
		expect(document.body.innerHTML).toMatch(animated)
		unmount()

		reduceMotion(true)
		render(<LiveTranscript live={twoLines} />)
		expect(document.body.innerHTML).not.toMatch(animated)
	})

	it('mentions the lag only past 5 seconds', () => {
		const { rerender } = render(<LiveTranscript live={view({ lagMs: 5000 })} />)
		expect(screen.getByRole('status').textContent).not.toContain(m.liveLag({ seconds: '5' }))

		rerender(<LiveTranscript live={view({ lagMs: 8200 })} />)
		expect(screen.getByRole('status').textContent).toContain(m.liveLag({ seconds: '8' }))
	})

	it('says a paused live transcription leaves the recording going, and retries it', async () => {
		function Harness() {
			return <LiveTranscript live={useLiveTranscript()} />
		}
		render(<Harness />)
		await act(async () => {})
		act(() => rememberNormalSession('s1'))
		act(() => mocks.listeners.get('live_status')?.({ payload: { sessionId: 's1', state: 'paused', message: 'unreachable' } }))

		expect(screen.getByText(m.livePausedBanner())).toBeTruthy()
		expect(screen.queryByRole('alert')).toBeNull()

		fireEvent.click(screen.getByRole('button', { name: m.tryAgain() }))
		expect(mocks.invoke).toHaveBeenCalledWith('retry_live', { sessionId: 's1' })
	})
})
