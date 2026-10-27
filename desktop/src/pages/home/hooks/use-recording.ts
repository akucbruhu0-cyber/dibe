import { invoke } from '@tauri-apps/api/core'
import { type SetStateAction, useContext, useEffect, useState } from 'react'
import { toast } from 'sonner'
import { type AudioDevice, pickSavedDevice } from '~/lib/audio'
import { CONFIG_KEYS } from '~/lib/config-keys'
import { usePersisted } from '~/lib/config-store'
import { KEEP_AWAKE, startKeepAwake, stopKeepAwake } from '~/lib/keep-awake'
import { liveOptions } from '~/lib/live-options'
import { ensureSystemAudioPermission } from '~/lib/permissions'
import { onNormalFinishing, recordingErrorMessage, rememberNormalSession, stopNormalSession } from '~/lib/recording-session'
import { ErrorModalContext } from '~/providers/error-modal'
import { useModelGates } from '~/providers/model-gates'
import { usePreferenceProvider } from '~/providers/preference'
import { m } from '~/paraglide/messages.js'

export function useRecording(onBeforeStart: () => void) {
	const preference = usePreferenceProvider()
	const { downloadVad } = useModelGates()
	const { setState: setErrorModal } = useContext(ErrorModalContext)
	const [devices, setDevices] = useState<AudioDevice[]>([])
	const [savedInputDeviceId, setSavedInputDeviceId] = usePersisted<string | null>(CONFIG_KEYS.inputDeviceId, null)
	const [savedOutputDeviceId, setSavedOutputDeviceId] = usePersisted<string | null>(CONFIG_KEYS.outputDeviceId, null)
	const [inputDevice, setInputDevice] = useState<AudioDevice | null>(null)
	const [outputDevice, setOutputDevice] = useState<AudioDevice | null>(null)
	const [isRecording, setIsRecording] = useState(false)
	/** Stopped, and saving: the live transcript's last words, then the audio. */
	const [isFinishing, setIsFinishing] = useState(false)
	useEffect(() => onNormalFinishing(setIsFinishing), [])
	const [recordingName, setRecordingName] = useState('')

	function setInputDeviceAndSave(value: SetStateAction<AudioDevice | null>) {
		const device = typeof value === 'function' ? value(inputDevice) : value
		setSavedInputDeviceId(device?.id ?? '')
		setInputDevice(device)
	}

	function setOutputDeviceAndSave(value: SetStateAction<AudioDevice | null>) {
		const device = typeof value === 'function' ? value(outputDevice) : value
		setSavedOutputDeviceId(device?.id ?? '')
		setOutputDevice(device)
	}

	async function loadAudioDevices() {
		const newDevices = await invoke<AudioDevice[]>('get_audio_devices')
		const inputs = newDevices.filter((device) => device.isInput)
		const outputs = newDevices.filter((device) => !device.isInput)
		const input = pickSavedDevice(inputs, savedInputDeviceId)
		const output = pickSavedDevice(outputs, savedOutputDeviceId)
		setInputDevice(input.device)
		setOutputDevice(output.device)
		// One toast for both; the id keeps a reload of the tab from stacking copies.
		if (input.missing || output.missing) toast.warning(m.savedDeviceNotConnected(), { id: 'saved-device-not-connected' })
		setDevices(newDevices)
	}

	useEffect(() => {
		if (preference.homeTab === 'record') loadAudioDevices()
	}, [preference.homeTab])

	async function startRecord() {
		if (outputDevice && !(await ensureSystemAudioPermission())) return
		startKeepAwake(KEEP_AWAKE.record)
		onBeforeStart()
		setIsRecording(true)
		const selectedDevices = [inputDevice, outputDevice].filter((device): device is AudioDevice => device !== null)
		try {
			const sessionId = await invoke<string>('start_record', {
				devices: selectedDevices,
				recordingName: recordingName.trim() || null,
				origin: 'manual',
				live: await liveOptions('manual', preference, downloadVad),
			})
			rememberNormalSession(sessionId)
		} catch (error) {
			stopKeepAwake(KEEP_AWAKE.record)
			setIsRecording(false)
			console.error('startRecord error: ', error)
			setErrorModal?.({ log: recordingErrorMessage(error), open: true })
		}
	}

	async function stopRecord() {
		try {
			await stopNormalSession()
		} catch (error) {
			stopKeepAwake(KEEP_AWAKE.record)
			setIsRecording(false)
			console.error('stopRecord error: ', error)
			setErrorModal?.({ log: recordingErrorMessage(error), open: true })
		}
	}

	return {
		devices,
		setDevices,
		inputDevice,
		outputDevice,
		isRecording,
		isFinishing,
		setIsRecording,
		recordingName,
		setRecordingName,
		setInputDevice: setInputDeviceAndSave,
		setOutputDevice: setOutputDeviceAndSave,
		startRecord,
		stopRecord,
	}
}
