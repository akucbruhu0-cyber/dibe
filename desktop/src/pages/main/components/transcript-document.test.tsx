// @vitest-environment jsdom

import { cleanup, fireEvent, render } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import type { Job } from '../hooks/use-transcribe-queue'
import type { TranscriptViewOptions } from '../hooks/use-transcript-view'
import TranscriptDocument from './transcript-document'

const mocks = vi.hoisted(() => ({ setSegments: vi.fn() }))
vi.mock('../session', () => ({ useSession: () => ({ queue: { setSegments: mocks.setSegments, previewJobName: vi.fn(), renameJob: vi.fn() } }) }))
vi.mock('~/providers/preference', () => ({ usePreferenceProvider: () => ({ textAreaDirection: 'rtl' }) }))
vi.mock('@tauri-apps/plugin-dialog', () => ({ save: vi.fn() }))
vi.mock('@tauri-apps/plugin-fs', () => ({ writeTextFile: vi.fn() }))
vi.mock('@tauri-apps/api/path', () => ({ downloadDir: async () => '/Downloads', join: async (...parts: string[]) => parts.join('/') }))

const options = { textSize: 'md', showTimestamps: true, showSpeakers: true } as TranscriptViewOptions

afterEach(() => {
	cleanup()
	vi.useRealTimers()
})

it('saves edits to the project after a pause and on closing, a joined line leaving the transcript', () => {
	vi.useFakeTimers()
	const job = {
		id: 'j1',
		name: 'Meeting',
		status: 'done',
		hydrated: true,
		segments: [
			{ start: 0, stop: 150, text: 'אחת', speaker: 0 },
			{ start: 150, stop: 300, text: 'שתיים', speaker: 1 },
		],
	} as Job
	const { unmount } = render(<TranscriptDocument job={job} options={options} />)
	const host = document.querySelector<HTMLElement>('[data-live-host]')!
	const lines = host.querySelectorAll<HTMLElement>('[data-live-line]')
	// The times are shown beside the lines but are not part of the text.
	expect(host.textContent).toBe('אחתשתיים')

	// What the browser does on a Backspace at the start of the second line.
	lines[0].textContent = 'אחתשתיים'
	lines[1].remove()
	fireEvent.input(host)
	expect(mocks.setSegments).not.toHaveBeenCalled()
	vi.advanceTimersByTime(500)
	expect(mocks.setSegments).toHaveBeenLastCalledWith('j1', [{ start: 0, stop: 150, text: 'אחתשתיים', speaker: 0 }])

	lines[0].textContent = 'אחת ושתיים'
	fireEvent.input(host)
	unmount()
	expect(mocks.setSegments).toHaveBeenCalledTimes(2)
	expect(mocks.setSegments).toHaveBeenLastCalledWith('j1', [{ start: 0, stop: 150, text: 'אחת ושתיים', speaker: 0 }])
})
