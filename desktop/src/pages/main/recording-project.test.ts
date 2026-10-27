// @vitest-environment jsdom

import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Preference } from '~/providers/preference'
import type { RecordFinishPayload } from '~/lib/recording-origin'
import { createRecordingProject, finishedRecording, finishWarningTitle, recoverUnfinishedRecordings, type RecordingProjectDeps } from './recording-project'

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

function deps(autoTranscribeAfterRecording = false, afterRecording: Preference['afterRecording'] = 'keep') {
	const current = {
		preference: {
			projectsPath: null,
			modelPath: '/models/m.bin',
			modelOptions: { lang: 'he' },
			autoTranscribeAfterRecording,
			afterRecording,
		} as unknown as Preference,
		hydrate: vi.fn(() => 'job-1'),
		transcribeJob: vi.fn(),
	} satisfies RecordingProjectDeps
	return { current }
}

/** The backend answers `take_recovered_recordings` with `list`; `forget_recovered` just succeeds. */
function backend(list: unknown[]) {
	mocks.invoke.mockImplementation(async (command: string) => (command === 'take_recovered_recordings' ? list : undefined))
}

/** The paths the UI told the backend to delete. */
function forgotten() {
	return mocks.invoke.mock.calls.filter(([command]) => command === 'forget_recovered').map(([, args]) => args.path)
}

beforeEach(() => {
	vi.clearAllMocks()
	mocks.saveTranscript.mockResolvedValue(saved)
})

describe('recoverUnfinishedRecordings', () => {
	it('makes a project of each recovered recording the way a finished recording becomes one', async () => {
		backend([recovered])
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
		// Only now that it is a project may the backend stop offering it.
		expect(mocks.invoke).toHaveBeenCalledWith('forget_recovered', { path: recovered.path })
		expect(current.transcribeJob).not.toHaveBeenCalled()
		expect(mocks.toast.info).toHaveBeenCalledWith(expect.stringContaining('2026'), expect.anything())
	})

	it('transcribes it when transcribing after recording is on', async () => {
		backend([recovered])
		const { current } = deps(true)

		await recoverUnfinishedRecordings({ current })

		expect(current.transcribeJob).toHaveBeenCalledWith('job-1')
	})

	it('does nothing when there is nothing to recover', async () => {
		backend([])
		const { current } = deps()

		await recoverUnfinishedRecordings({ current })

		expect(mocks.saveTranscript).not.toHaveBeenCalled()
		expect(mocks.toast.info).not.toHaveBeenCalled()
	})

	it('does not announce a recording it could not save', async () => {
		backend([recovered])
		mocks.saveTranscript.mockResolvedValue(null)
		const { current } = deps()

		await recoverUnfinishedRecordings({ current })

		expect(mocks.toast.error).toHaveBeenCalledTimes(1)
		expect(mocks.toast.info).not.toHaveBeenCalled()
		expect(current.hydrate).not.toHaveBeenCalled()
		expect(forgotten()).toEqual([])
	})

	it('keeps going when one recording throws, and forgets only the ones it saved', async () => {
		const second = { path: '/data/recovered/2026-10-26 09-00-00.wav', name: '2026-10-26 09-00-00.wav', recordedAt: '2026-10-26T09:00:00+02:00' }
		backend([recovered, second])
		mocks.saveTranscript.mockRejectedValueOnce(new Error('disk full'))
		vi.spyOn(console, 'error').mockImplementation(() => {})
		const { current } = deps()

		await recoverUnfinishedRecordings({ current })

		expect(mocks.saveTranscript).toHaveBeenCalledTimes(2)
		expect(current.hydrate).toHaveBeenCalledTimes(1)
		// The first stays in the recovered folder and is offered again next launch.
		expect(forgotten()).toEqual([second.path])
		expect(mocks.toast.info).toHaveBeenCalledTimes(1)
	})

	it('still announces a saved recording when the backend cannot forget it', async () => {
		mocks.invoke.mockImplementation(async (command: string) => {
			if (command === 'forget_recovered') throw new Error('locked')
			return [recovered]
		})
		vi.spyOn(console, 'error').mockImplementation(() => {})

		await recoverUnfinishedRecordings(deps())

		expect(mocks.toast.info).toHaveBeenCalledTimes(1)
	})

	it('survives a backend that cannot answer', async () => {
		mocks.invoke.mockRejectedValue(new Error('no command'))
		vi.spyOn(console, 'error').mockImplementation(() => {})

		await expect(recoverUnfinishedRecordings(deps())).resolves.toBeUndefined()
	})
})

describe('a finished recording with live transcription', () => {
	const liveSegments = [{ start: 0, stop: 150, text: 'שלום John, נפגשים ב-14:30' }]
	const finish = (segments: RecordFinishPayload['liveSegments'], liveComplete: boolean | null = segments ? true : null): RecordFinishPayload => ({
		sessionId: 's1',
		origin: 'manual',
		path: '/data/recordings-in-progress/2026-10-27 10-00-00.wav',
		name: '2026-10-27 10-00-00.wav',
		liveSegments: segments,
		liveComplete,
	})

	it('keeps the live transcript as the project transcript, without transcribing again', async () => {
		const { current } = deps(true, 'keep')

		await createRecordingProject(finishedRecording(finish(liveSegments), current.preference), current)

		expect(mocks.saveTranscript).toHaveBeenCalledWith(expect.objectContaining({ segments: liveSegments }))
		expect(current.hydrate).toHaveBeenCalledWith(expect.objectContaining({ segments: liveSegments }), saved.recordPath, saved.mediaPath, 'record')
		expect(current.transcribeJob).not.toHaveBeenCalled()
	})

	it('keeps an incomplete live transcript and transcribes the recording to replace it', async () => {
		const { current } = deps(false, 'keep')

		await createRecordingProject(finishedRecording(finish(liveSegments, false), current.preference), current)

		// The live words stay the project's until a transcription of the file succeeds.
		expect(mocks.saveTranscript).toHaveBeenCalledWith(expect.objectContaining({ segments: liveSegments }))
		expect(current.hydrate).toHaveBeenCalledWith(expect.objectContaining({ segments: liveSegments }), saved.recordPath, saved.mediaPath, 'record')
		expect(current.transcribeJob).toHaveBeenCalledWith('job-1', true)
		expect(mocks.toast.info).toHaveBeenCalledWith('The live transcript was incomplete. Transcribing the recording again.', expect.anything())
	})

	it('transcribes the file again when asked to, keeping the live words until then', async () => {
		// Transcribing after recording is off by default; asking to transcribe again is enough.
		const { current } = deps(false, 'retranscribe')

		await createRecordingProject(finishedRecording(finish(liveSegments), current.preference), current)

		expect(mocks.saveTranscript).toHaveBeenCalledWith(expect.objectContaining({ segments: liveSegments }))
		expect(current.transcribeJob).toHaveBeenCalledWith('job-1', true)
		expect(mocks.toast.info).not.toHaveBeenCalled()
	})

	it('leaves a recording without live transcription to the usual flow', async () => {
		const { current } = deps(false, 'retranscribe')

		await createRecordingProject(finishedRecording(finish(null), current.preference), current)

		expect(mocks.saveTranscript).toHaveBeenCalledWith(expect.objectContaining({ segments: [] }))
		expect(current.transcribeJob).not.toHaveBeenCalled()
	})

	it('falls back to transcribing when live transcription was off or heard nothing', async () => {
		for (const segments of [null, []]) {
			vi.clearAllMocks()
			mocks.saveTranscript.mockResolvedValue(saved)
			const { current } = deps(true, 'keep')

			await createRecordingProject(finishedRecording(finish(segments), current.preference), current)

			expect(mocks.saveTranscript).toHaveBeenCalledWith(expect.objectContaining({ segments: [] }))
			expect(current.transcribeJob).toHaveBeenCalledWith('job-1')
		}
	})
})

describe('the warning of a finished recording', () => {
	const finish = { sessionId: 's1', origin: 'meeting', path: '/r.wav', name: 'r.wav', warning: 'A recording device stopped' } as const

	it('says a device disconnected when one did', () => {
		expect(finishWarningTitle({ ...finish, deviceDisconnected: true })).toBe('A recording device disconnected. The recording was saved.')
	})

	it('keeps the general title for anything else', () => {
		expect(finishWarningTitle({ ...finish, deviceDisconnected: false })).toBe('Recording saved with reduced processing')
	})
})
