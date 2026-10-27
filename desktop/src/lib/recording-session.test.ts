import { beforeEach, describe, expect, it, vi } from 'vitest'
import { onNormalFinishing, onNormalSession, rememberNormalSession, stopNormalSession } from './recording-session'

const mocks = vi.hoisted(() => ({ invoke: vi.fn() }))
vi.mock('@tauri-apps/api/core', () => ({ invoke: mocks.invoke }))

beforeEach(() => {
	vi.restoreAllMocks()
})

describe('rememberNormalSession', () => {
	it('tells every listener even when one throws, and never throws itself', () => {
		vi.spyOn(console, 'error').mockImplementation(() => {})
		const heard: string[] = []
		const unbindBroken = onNormalSession(() => {
			throw new Error('malformed')
		})
		const unbind = onNormalSession((id) => heard.push(id))

		// A throw here would make the started recording look failed to its caller.
		expect(() => rememberNormalSession('s1')).not.toThrow()
		expect(heard).toEqual(['s1'])
		expect(console.error).toHaveBeenCalled()
		unbindBroken()
		unbind()
	})
})

describe('stopNormalSession', () => {
	it('is finishing from the stop until the backend saved the recording', async () => {
		let saved = () => {}
		mocks.invoke.mockReturnValue(new Promise<void>((resolve) => (saved = resolve)))
		const states: boolean[] = []
		const unbind = onNormalFinishing((finishing) => states.push(finishing))
		rememberNormalSession('s1')

		const stopping = stopNormalSession()
		// stop_record resolves only after record_finish: the live finals and the audio are saved.
		expect(states).toEqual([true])
		saved()
		await stopping

		expect(mocks.invoke).toHaveBeenCalledWith('stop_record', { sessionId: 's1' })
		expect(states).toEqual([true, false])
		unbind()
	})

	it('stops finishing when the stop fails', async () => {
		mocks.invoke.mockRejectedValue(new Error('gone'))
		const states: boolean[] = []
		const unbind = onNormalFinishing((finishing) => states.push(finishing))
		rememberNormalSession('s1')

		await expect(stopNormalSession()).rejects.toThrow('gone')

		expect(states).toEqual([true, false])
		unbind()
	})
})
