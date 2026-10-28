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
	save: vi.fn(),
	writeTextFile: vi.fn(),
}))
vi.mock('@tauri-apps/api/event', () => ({
	listen: vi.fn(async (name: string, callback: (event: { payload: unknown }) => void) => {
		mocks.listeners.set(name, callback)
		return () => mocks.listeners.delete(name)
	}),
}))
vi.mock('@tauri-apps/api/core', () => ({ invoke: mocks.invoke }))
vi.mock('sonner', () => ({ toast: { warning: vi.fn(), info: vi.fn(), dismiss: vi.fn(), success: vi.fn(), error: vi.fn() } }))
vi.mock('@tauri-apps/plugin-dialog', () => ({ save: mocks.save }))
vi.mock('@tauri-apps/plugin-fs', () => ({ writeTextFile: mocks.writeTextFile }))
vi.mock('@tauri-apps/api/path', () => ({ downloadDir: async () => '/Downloads', join: async (...parts: string[]) => parts.join('/') }))

const MIXED = 'שלום John, נפגשים ב-14:30'

function view(state: Partial<LiveState>): LiveView {
	return {
		state: { finals: [], partial: null, status: 'live', lagMs: 0, ...state },
		edits: new Map(),
		edit: vi.fn(),
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
	mocks.save.mockReset()
	mocks.writeTextFile.mockReset().mockResolvedValue(undefined)
	reduceMotion(false)
})
afterEach(cleanup)

describe('LiveTranscript', () => {
	it('lets every line find its own direction, with the time isolated as LTR', () => {
		render(<LiveTranscript live={twoLines} />)

		expect(lines()).toHaveLength(2)
		for (const line of lines()) {
			expect(line.getAttribute('dir')).toBe('auto')
			expect(line.getAttribute('data-time')).toMatch(/^\d\d:\d\d$/)
		}
	})

	it('copies the words without their times', () => {
		render(<LiveTranscript live={twoLines} />)

		// The time is drawn by CSS, so a selection (and a copy) never holds it.
		expect(lines()[0].textContent).toBe(MIXED)
	})

	it('shows the whole text as one block, without times, when asked', () => {
		render(<LiveTranscript live={twoLines} />)

		fireEvent.click(screen.getByRole('button', { name: m.liveOneBlock() }))

		const block = document.querySelector('[data-live-block]')
		expect(block?.textContent).toBe(`${MIXED} עוד רגע`)
		expect(document.querySelector('[data-time]')).toBeNull()
		expect(screen.getByRole('button', { name: m.liveOneBlock() }).getAttribute('aria-pressed')).toBe('true')
		fireEvent.click(screen.getByRole('button', { name: m.liveOneBlock() }))
	})

	it('is one editable document: Cmd+A takes every line, and an edit lands on its own line', () => {
		const live = view({
			finals: [
				{ start: 0, stop: 150, text: MIXED },
				{ start: 150, stop: 300, text: 'משפט שני' },
			],
			partial: { start: 300, stop: 400, text: 'עוד רגע' },
		})
		render(<LiveTranscript live={live} />)
		const host = document.querySelector<HTMLElement>('[data-live-host]')!

		// One editing host for all the finals: the caret and a selection cross lines freely.
		expect(host.getAttribute('contenteditable')).toBe('plaintext-only')
		expect(host.querySelectorAll('[data-live-line]')).toHaveLength(2)
		expect(host.contains(lines()[2])).toBe(false)

		fireEvent.keyDown(screen.getByRole('region'), { key: 'a', metaKey: true })
		expect(window.getSelection()?.toString()).toBe(`${MIXED}משפט שני`)

		lines()[1].textContent = 'משפט שתיים'
		fireEvent.input(host)
		expect(live.edit).toHaveBeenCalledWith(1, 'משפט שתיים')
		expect(live.edit).toHaveBeenCalledTimes(1)
	})

	it('joins two lines into the first when the text between them is deleted', () => {
		const live = view({
			finals: [
				{ start: 0, stop: 150, text: 'אחת' },
				{ start: 150, stop: 300, text: 'שתיים' },
			],
		})
		render(<LiveTranscript live={live} />)
		const host = document.querySelector<HTMLElement>('[data-live-host]')!

		// What the browser does on a Backspace at the start of the second line.
		lines()[0].textContent = 'אחתשתיים'
		lines()[1].remove()
		fireEvent.input(host)

		expect(live.edit).toHaveBeenCalledWith(0, 'אחתשתיים')
		expect(live.edit).toHaveBeenCalledWith(1, '')
	})

	it('adds new lines at the end without touching the ones above', () => {
		const first = { start: 0, stop: 150, text: 'אחת' }
		const live = view({ finals: [first] })
		const { rerender } = render(<LiveTranscript live={live} />)
		const firstLine = lines()[0]
		firstLine.textContent = 'אחת, באמצע עריכה'

		live.state.finals.push({ start: 150, stop: 300, text: 'שתיים' })
		rerender(<LiveTranscript live={{ ...live, state: { ...live.state } }} />)

		expect(lines()).toHaveLength(2)
		expect(lines()[0]).toBe(firstLine)
		expect(firstLine.textContent).toBe('אחת, באמצע עריכה')
	})

	it('justifies the text to both edges', () => {
		render(<LiveTranscript live={twoLines} />)

		expect(document.querySelector('[data-live-host]')?.className).toContain('text-justify')
	})

	it('shows an edited line as the reader left it', () => {
		const live = view({ finals: [{ start: 0, stop: 150, text: MIXED }] })
		live.edits.set(0, { start: 0, text: 'מתוקן' })
		render(<LiveTranscript live={live} />)

		expect(lines()[0].textContent).toBe('מתוקן')
	})

	it('keeps mixed Hebrew, English and a time intact', () => {
		render(<LiveTranscript live={twoLines} />)

		expect(lines()[0].textContent).toContain(MIXED)
	})

	it('greys the partial line only', () => {
		render(<LiveTranscript live={twoLines} />)

		const [finalLine, partialLine] = lines()
		expect(partialLine.classList.contains('text-muted-foreground')).toBe(true)
		expect(finalLine.classList.contains('text-muted-foreground')).toBe(false)
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

	it('grows and shrinks the text, within bounds', () => {
		render(<LiveTranscript live={twoLines} />)
		// Set once, around all the lines.
		const size = () => lines()[0].closest<HTMLElement>('[style*="font-size"]')!.style.fontSize

		const before = parseInt(size())
		fireEvent.click(screen.getByRole('button', { name: m.liveTextLarger() }))
		expect(parseInt(size())).toBeGreaterThan(before)

		for (let i = 0; i < 20; i++) fireEvent.click(screen.getByRole('button', { name: m.liveTextSmaller() }))
		expect(parseInt(size())).toBeGreaterThanOrEqual(12)
		expect(screen.getByRole('button', { name: m.liveTextSmaller() }).hasAttribute('disabled')).toBe(true)
	})

	it('saves everything heard so far, the partial too, without touching the recording', async () => {
		mocks.save.mockResolvedValue('/Downloads/live.txt')
		render(<LiveTranscript live={twoLines} />)

		await act(async () => fireEvent.click(screen.getByRole('button', { name: m.liveDownload() })))

		expect(mocks.writeTextFile).toHaveBeenCalledWith('/Downloads/live.txt', `${MIXED}\nעוד רגע\n`)
		expect(mocks.invoke).not.toHaveBeenCalled()
	})

	it("saves the reader's edits, and the block as one paragraph when the block is shown", async () => {
		mocks.save.mockResolvedValue('/Downloads/live.txt')
		const live = view({ finals: twoLines.state.finals, partial: twoLines.state.partial })
		live.edits.set(0, { start: 0, text: 'מתוקן' })
		render(<LiveTranscript live={live} />)
		fireEvent.click(screen.getByRole('button', { name: m.liveOneBlock() }))

		await act(async () => fireEvent.click(screen.getByRole('button', { name: m.liveDownload() })))

		expect(mocks.writeTextFile).toHaveBeenCalledWith('/Downloads/live.txt', 'מתוקן עוד רגע\n')
		fireEvent.click(screen.getByRole('button', { name: m.liveOneBlock() }))
	})

	it('saves subtitles with their times when asked for .srt', async () => {
		mocks.save.mockResolvedValue('/Downloads/live.srt')
		render(<LiveTranscript live={twoLines} />)

		await act(async () => fireEvent.click(screen.getByRole('button', { name: m.liveDownload() })))

		expect(mocks.writeTextFile.mock.calls[0][1]).toContain('00:00:01,500 --> 00:00:03,000')
	})

	it('says when the recording itself is paused, and stills the dot', () => {
		render(<LiveTranscript live={twoLines} paused />)

		expect(screen.getByRole('status').textContent).toContain(m.recordingPausedNow())
		expect(document.querySelector('.live-pulse')).toBeNull()
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
