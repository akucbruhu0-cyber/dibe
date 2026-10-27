// @vitest-environment jsdom

import { act, renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { Segment } from '~/lib/transcript'
import { reconcileProjectName, useTranscribeQueue } from './use-transcribe-queue'

const mocks = vi.hoisted(() => ({
	invoke: vi.fn(),
	updateTranscriptSegments: vi.fn(async () => true),
	preference: {
		modelPath: '/models/m.bin',
		gpuDevice: null,
		noGpu: false,
		unloadTimeoutMinutes: 5,
		modelOptions: { lang: 'he' },
		autoExport: { enabled: false },
		saveTranscripts: true,
	},
}))
vi.mock('@tauri-apps/api/core', () => ({ invoke: mocks.invoke }))
vi.mock('@tauri-apps/api/event', () => ({ listen: vi.fn(async () => () => {}), emit: vi.fn() }))
vi.mock('sonner', () => ({ toast: { warning: vi.fn(), error: vi.fn(), info: vi.fn(), success: vi.fn(), dismiss: vi.fn() } }))
vi.mock('~/providers/preference', () => ({ usePreferenceProvider: () => mocks.preference }))
vi.mock('~/lib/gpu-memory', () => ({ gpuOutOfMemoryBefore: () => false, rememberGpuOutOfMemory: vi.fn() }))
vi.mock('~/lib/keep-awake', () => ({ KEEP_AWAKE: {}, startKeepAwake: vi.fn(), stopKeepAwake: vi.fn() }))
vi.mock('./use-live-transcript', () => ({ waitForLiveIdle: async () => {} }))
vi.mock('~/lib/transcripts-store', () => ({
	notifyTranscriptsChanged: vi.fn(),
	renameTranscript: vi.fn(),
	saveTranscript: vi.fn(),
	updateTranscriptSegments: mocks.updateTranscriptSegments,
	updateTranscriptSpeakerNames: vi.fn(),
	updateTranscriptSummary: vi.fn(),
	updateTranscriptThread: vi.fn(),
}))

describe('project name reconciliation', () => {
	it('keeps reconciling when the live title changes during a slow rename', async () => {
		let liveName = 'Second draft'
		const rename = vi.fn(async (_path: string, name: string) => {
			if (name === 'Second draft') liveName = 'Final title'
			return {
				path: `/projects/${name}/transcript.vibe.json`,
				mediaPath: `/projects/${name}/audio.wav`,
				name,
				createdAt: new Date(0),
			}
		})

		const result = await reconcileProjectName(
			'Original',
			{ recordPath: '/projects/Original/transcript.vibe.json', mediaPath: '/projects/Original/audio.wav' },
			() => liveName,
			rename,
		)

		expect(rename.mock.calls.map(([, name]) => name)).toEqual(['Second draft', 'Final title'])
		expect(result).toEqual({
			recordPath: '/projects/Final title/transcript.vibe.json',
			mediaPath: '/projects/Final title/audio.wav',
		})
	})
})

describe('transcribing a project again over its transcript', () => {
	const live: Segment[] = [{ start: 0, stop: 150, text: 'שלום מהתמלול החי' }]
	const record = { version: 1, name: 'Record-x', sourcePath: '/p/audio.wav', createdAt: '2026-10-27T08:00:00.000Z', segments: live }

	/** A project with a live transcript, transcribed again; resolves `finish` to end the run. */
	async function rerun() {
		let finish: (segments: Segment[]) => void = () => {}
		mocks.invoke.mockImplementation((command: string) =>
			command === 'transcribe' ? new Promise((resolve) => (finish = (segments) => resolve({ segments }))) : Promise.resolve('loaded'),
		)
		const { result } = renderHook(() => useTranscribeQueue())
		let jobId: string | null = null
		act(() => {
			jobId = result.current.hydrate(record, '/p/transcript.vibe.json', '/p/audio.wav', 'record')
		})
		act(() => result.current.transcribeJob(jobId!, true))
		await waitFor(() => expect(mocks.invoke).toHaveBeenCalledWith('transcribe', expect.anything()))
		return { result, finish: (segments: Segment[]) => act(async () => finish(segments)) }
	}

	it('replaces the transcript once the new one finishes', async () => {
		const { result, finish } = await rerun()
		const fresh = [{ start: 0, stop: 150, text: 'שלום מהקובץ' }]

		await finish(fresh)

		await waitFor(() => expect(result.current.jobs[0]).toMatchObject({ status: 'done', segments: fresh }))
		expect(mocks.updateTranscriptSegments).toHaveBeenLastCalledWith('/p/transcript.vibe.json', fresh)
	})

	it('keeps the transcript when the run is cancelled', async () => {
		const { result, finish } = await rerun()

		// A cancel resolves with what was heard so far: here nothing, which must not overwrite it.
		act(() => result.current.cancelCurrent())
		await finish([])

		await waitFor(() => expect(result.current.jobs[0]).toMatchObject({ status: 'done', segments: live }))
		expect(mocks.updateTranscriptSegments).not.toHaveBeenCalledWith('/p/transcript.vibe.json', [])
	})
})
