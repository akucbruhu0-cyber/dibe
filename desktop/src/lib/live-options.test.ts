// @vitest-environment jsdom

import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Preference } from '~/providers/preference'
import { liveOptions } from './live-options'

const mocks = vi.hoisted(() => ({
	invoke: vi.fn(),
	usable: vi.fn(),
	toast: { warning: vi.fn(), error: vi.fn() },
	preference: {} as Record<string, unknown>,
	downloadVad: vi.fn(),
}))
vi.mock('@tauri-apps/api/core', () => ({ invoke: mocks.invoke }))
vi.mock('sonner', () => ({ toast: mocks.toast }))
vi.mock('~/lib/model', () => ({ isModelFileUsable: mocks.usable }))
vi.mock('~/providers/preference', () => ({ usePreferenceProvider: () => mocks.preference }))
vi.mock('~/providers/model-gates', () => ({ useModelGates: () => ({ downloadVad: mocks.downloadVad }) }))

function preference(overrides: Partial<Preference> = {}) {
	return {
		liveTranscription: true,
		modelPath: '/models/ivrit.bin',
		gpuDevice: null,
		noGpu: false,
		unloadTimeoutMinutes: 5,
		modelOptions: { lang: 'he' },
		recentLanguages: [],
		...overrides,
	} as unknown as Preference
}

/** A backend with the models folder at /models; every other command just succeeds. */
function backend() {
	mocks.invoke.mockImplementation(async (command: string) => (command === 'get_models_folder' ? '/models' : 'loaded'))
}

beforeEach(() => {
	vi.clearAllMocks()
	backend()
	mocks.usable.mockResolvedValue(true)
	vi.spyOn(console, 'error').mockImplementation(() => {})
})
afterEach(cleanup)

describe('liveOptions', () => {
	it('loads the model and points the server at the VAD model', async () => {
		const live = await liveOptions('manual', preference(), mocks.downloadVad)

		expect(live).toEqual({ language: 'he', partials: true, vadModel: '/models/ggml-silero-v6.2.0.bin' })
		expect(mocks.invoke).toHaveBeenCalledWith('load_model', { modelPath: '/models/ivrit.bin', gpuDevice: null, noGpu: false, unloadTimeoutMinutes: 5 })
	})

	it('is off when the preference is off', async () => {
		expect(await liveOptions('shortcut', preference({ liveTranscription: false }), mocks.downloadVad)).toBeNull()
		expect(mocks.invoke).not.toHaveBeenCalled()
	})

	it('never transcribes dictation live', async () => {
		expect(await liveOptions('dictation', preference(), mocks.downloadVad)).toBeNull()
		expect(mocks.invoke).not.toHaveBeenCalled()
	})

	it('records without live, and offers the VAD download, when the VAD model is missing', async () => {
		mocks.usable.mockResolvedValue(false)

		expect(await liveOptions('meeting', preference(), mocks.downloadVad)).toBeNull()

		expect(mocks.toast.warning).toHaveBeenCalledTimes(1)
		const [, toastOptions] = mocks.toast.warning.mock.calls[0]
		toastOptions.action.onClick()
		expect(mocks.downloadVad).toHaveBeenCalledTimes(1)
	})

	it('uses the most recent language when the setting is automatic, else English', async () => {
		const recent = [
			{ code: 'he', ts: 2 },
			{ code: 'fr', ts: 1 },
		]
		expect(
			(
				await liveOptions(
					'manual',
					preference({ modelOptions: { lang: 'auto' } as Preference['modelOptions'], recentLanguages: recent }),
					mocks.downloadVad,
				)
			)?.language,
		).toBe('he')
		expect((await liveOptions('manual', preference({ modelOptions: { lang: 'auto' } as Preference['modelOptions'] }), mocks.downloadVad))?.language).toBe(
			'en',
		)
	})

	it('turns any failure into a recording without live and a toast', async () => {
		mocks.invoke.mockRejectedValue(new Error('server gone'))

		await expect(liveOptions('manual', preference(), mocks.downloadVad)).resolves.toBeNull()
		expect(mocks.toast.warning).toHaveBeenCalledWith(expect.any(String), expect.objectContaining({ description: 'server gone' }))
	})
})

describe('starting a recording from the main screen', () => {
	it('still records when live transcription cannot run', async () => {
		const { useRecording } = await import('~/pages/home/hooks/use-recording')
		Object.assign(mocks.preference, preference({ homeTab: 'file' } as Partial<Preference>))
		mocks.usable.mockResolvedValue(false)
		mocks.invoke.mockImplementation(async (command: string) =>
			command === 'start_record' ? 'session-1' : command === 'get_models_folder' ? '/models' : undefined,
		)
		const { result } = renderHook(() => useRecording(() => {}))

		await act(() => result.current.startRecord())

		expect(mocks.invoke).toHaveBeenCalledWith('start_record', expect.objectContaining({ origin: 'manual', live: null }))
		expect(result.current.isRecording).toBe(true)
	})
})
