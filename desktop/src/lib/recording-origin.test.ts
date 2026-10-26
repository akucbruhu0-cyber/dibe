import { describe, expect, it } from 'vitest'
import { isMine } from './recording-origin'

describe('isMine', () => {
	it('rejects a recording that another caller started', () => {
		expect(isMine({ origin: 'dictation' }, 'manual')).toBe(false)
	})

	it('accepts the single origin it was given', () => {
		expect(isMine({ origin: 'dictation' }, 'dictation')).toBe(true)
	})

	it('accepts any origin in the list', () => {
		expect(isMine({ origin: 'meeting' }, ['manual', 'shortcut', 'meeting'])).toBe(true)
		expect(isMine({ origin: 'dictation' }, ['manual', 'shortcut', 'meeting'])).toBe(false)
	})
})
