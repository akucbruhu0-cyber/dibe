export interface AudioDevice {
	isDefault: boolean
	isInput: boolean
	id: string
	name: string
}

/** Ids saved before devices had stable ids were positions in the device list ("0", "2"). */
const isLegacyIndex = (id: string) => /^\d+$/.test(id)

/**
 * The device to select for the id saved last time, out of `devices` (all inputs or all outputs).
 * Nothing saved (`null`) means the default; an empty id is the user's choice of no device. A saved
 * device that is not connected falls back to the default and is reported as `missing`, except an
 * index from an older version, which means nothing now and falls back silently.
 */
export function pickSavedDevice(devices: AudioDevice[], savedId: string | null): { device: AudioDevice | null; missing: boolean } {
	const fallback = devices.find((device) => device.isDefault) ?? null
	if (savedId === null) return { device: fallback, missing: false }
	if (savedId === '') return { device: null, missing: false }
	const saved = devices.find((device) => device.id === savedId)
	if (saved) return { device: saved, missing: false }
	return { device: fallback, missing: !isLegacyIndex(savedId) }
}
