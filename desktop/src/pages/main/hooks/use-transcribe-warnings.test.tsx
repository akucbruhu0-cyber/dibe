// @vitest-environment jsdom

import { cleanup, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { m } from '~/paraglide/messages.js'
import { useTranscribeWarnings } from './use-transcribe-warnings'

const mocks = vi.hoisted(() => ({
	listeners: new Map<string, (event: { payload: unknown }) => void>(),
	warning: vi.fn(),
}))
vi.mock('@tauri-apps/api/event', () => ({
	listen: vi.fn(async (name: string, callback: (event: { payload: unknown }) => void) => {
		mocks.listeners.set(name, callback)
		return () => mocks.listeners.delete(name)
	}),
}))
vi.mock('sonner', () => ({ toast: { warning: mocks.warning } }))

beforeEach(() => {
	mocks.listeners.clear()
	mocks.warning.mockReset()
})
afterEach(cleanup)

describe('useTranscribeWarnings', () => {
	it('tells the user speakers are missing, with the server reason', () => {
		renderHook(() => useTranscribeWarnings())

		mocks.listeners.get('transcribe_warning')?.({ payload: { code: 'diarization_failed', message: 'model missing' } })

		expect(mocks.warning).toHaveBeenCalledTimes(1)
		expect(mocks.warning).toHaveBeenCalledWith(m.diarizationFailed(), expect.objectContaining({ description: 'model missing' }))
	})

	it('shows a warning it has no wording for as it came from the server', () => {
		renderHook(() => useTranscribeWarnings())

		mocks.listeners.get('transcribe_warning')?.({ payload: { code: 'something_new', message: 'heads up' } })

		expect(mocks.warning).toHaveBeenCalledWith('heads up', expect.anything())
	})

	it('stops listening when unmounted', async () => {
		const { unmount } = renderHook(() => useTranscribeWarnings())
		expect(mocks.listeners.has('transcribe_warning')).toBe(true)

		unmount()

		await waitFor(() => expect(mocks.listeners.has('transcribe_warning')).toBe(false))
	})
})
