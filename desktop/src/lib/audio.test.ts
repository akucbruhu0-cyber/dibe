import { describe, expect, it } from 'vitest'
import { type AudioDevice, pickSavedDevice } from './audio'

const builtIn: AudioDevice = { id: 'coreaudio:BuiltInMic', name: 'MacBook Pro Microphone', isInput: true, isDefault: true }
const airpods: AudioDevice = { id: 'coreaudio:AA-BB-CC:input', name: 'AirPods', isInput: true, isDefault: false }
const inputs = [builtIn, airpods]

describe('pickSavedDevice', () => {
	it('uses the default device when nothing was saved', () => {
		expect(pickSavedDevice(inputs, null)).toEqual({ device: builtIn, missing: false })
	})

	it('finds the saved device by its stable id, wherever it now sits in the list', () => {
		expect(pickSavedDevice([airpods, builtIn], airpods.id)).toEqual({ device: airpods, missing: false })
	})

	it('keeps an explicit choice of no device', () => {
		expect(pickSavedDevice(inputs, '')).toEqual({ device: null, missing: false })
	})

	it('falls back to the default and reports a saved device that is not connected', () => {
		expect(pickSavedDevice([builtIn], airpods.id)).toEqual({ device: builtIn, missing: true })
	})

	it('falls back to the default silently for an index saved before devices had stable ids', () => {
		expect(pickSavedDevice(inputs, '1')).toEqual({ device: builtIn, missing: false })
	})

	it('copes with a machine that has no default device', () => {
		expect(pickSavedDevice([], null)).toEqual({ device: null, missing: false })
		expect(pickSavedDevice([], airpods.id)).toEqual({ device: null, missing: true })
	})
})
