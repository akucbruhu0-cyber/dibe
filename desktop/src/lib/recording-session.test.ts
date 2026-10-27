import { beforeEach, describe, expect, it, vi } from 'vitest'
import { onNormalSession, rememberNormalSession } from './recording-session'

vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }))

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
