// @vitest-environment jsdom

import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Preference } from '~/providers/preference'
import { recoverUnfinishedRecordings, type RecordingProjectDeps } from './recording-project'

const mocks = vi.hoisted(() => ({
	invoke: vi.fn(),
	saveTranscript: vi.fn(),
	notifyTranscriptsChanged: vi.fn(),
	toast: { info: vi.fn(), error: vi.fn() },
}))
vi.mock('@tauri-apps/api/core', () => ({ invoke: mocks.invoke }))
vi.mock('sonner', () => ({ toast: mocks.toast }))
vi.mock('~/lib/transcripts-store', () => ({
	saveTranscript: mocks.saveTranscript,
	notifyTranscriptsChanged: mocks.notifyTranscriptsChanged,
	TRANSCRIPT_VERSION: 1,
}))

const recovered = { path: '/data/recordings-in-progress/2026-10-25 14-30-00.wav', name: '2026-10-25 14-30-00.wav', recordedAt: '2026-10-25T14:30:00+02:00' }
const saved = { mediaPath: '/projects/Record-x/audio.wav', recordPath: '/projects/Record-x/transcript.vibe.json' }

function deps(autoTranscribeAfterRecording = false) {
	const current = {
		preference: { projectsPath: null, modelPath: '/models/m.bin', modelOptions: { lang: 'he' }, autoTranscribeAfterRecording } as unknown as Preference,
		hydrate: vi.fn(() => 'job-1'),
		transcribeJob: vi.fn(),
	} satisfies RecordingProjectDeps
	return { current }
}

beforeEach(() => {
	vi.clearAllMocks()
	mocks.saveTranscript.mockResolvedValue(saved)
})

describe('recoverUnfinishedRecordings', () => {
	it('makes a project of each recovered recording the way a finished recording becomes one', async () => {
		mocks.invoke.mockResolvedValue([recovered])
		const { current } = deps()

		await recoverUnfinishedRecordings({ current })

		expect(mocks.invoke).toHaveBeenCalledWith('take_recovered_recordings')
		expect(mocks.saveTranscript).toHaveBeenCalledWith(
			expect.objectContaining({
				name: 'Record-2026-10-25 14-30-00',
				sourcePath: recovered.path,
				moveSourceMedia: true,
				segments: [],
				createdAt: new Date(recovered.recordedAt),
			}),
		)
		expect(current.hydrate).toHaveBeenCalledWith(
			expect.objectContaining({ createdAt: '2026-10-25T12:30:00.000Z' }),
			saved.recordPath,
			saved.mediaPath,
			'record',
		)
		expect(mocks.notifyTranscriptsChanged).toHaveBeenCalledTimes(1)
		expect(current.transcribeJob).not.toHaveBeenCalled()
		expect(mocks.toast.info).toHaveBeenCalledWith(expect.stringContaining('2026'), expect.anything())
	})

	it('transcribes it when transcribing after recording is on', async () => {
		mocks.invoke.mockResolvedValue([recovered])
		const { current } = deps(true)

		await recoverUnfinishedRecordings({ current })

		expect(current.transcribeJob).toHaveBeenCalledWith('job-1')
	})

	it('does nothing when there is nothing to recover', async () => {
		mocks.invoke.mockResolvedValue([])
		const { current } = deps()

		await recoverUnfinishedRecordings({ current })

		expect(mocks.saveTranscript).not.toHaveBeenCalled()
		expect(mocks.toast.info).not.toHaveBeenCalled()
	})

	it('does not announce a recording it could not save', async () => {
		mocks.invoke.mockResolvedValue([recovered])
		mocks.saveTranscript.mockResolvedValue(null)
		const { current } = deps()

		await recoverUnfinishedRecordings({ current })

		expect(mocks.toast.error).toHaveBeenCalledTimes(1)
		expect(mocks.toast.info).not.toHaveBeenCalled()
		expect(current.hydrate).not.toHaveBeenCalled()
	})

	it('survives a backend that cannot answer', async () => {
		mocks.invoke.mockRejectedValue(new Error('no command'))
		vi.spyOn(console, 'error').mockImplementation(() => {})

		await expect(recoverUnfinishedRecordings(deps())).resolves.toBeUndefined()
	})
})
