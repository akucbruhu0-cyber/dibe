import { invoke } from '@tauri-apps/api/core'
import { platform } from '@tauri-apps/plugin-os'
import { useCallback, useEffect, useState } from 'react'
import { toast } from 'sonner'
import ShortcutRecorder from '~/components/shortcut-recorder'
import { MeetingServiceIcons } from '~/components/meeting-service-icons'
import { Button } from '~/components/ui/button'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '~/components/ui/select'
import { Switch } from '~/components/ui/switch'
import { openCalendarSettings, requestCalendarPermission } from '~/lib/calendar'
import { getDefaultRecordingShortcut } from '~/lib/config'
import type { PermissionStatus } from '~/lib/permissions'
import { m } from '~/paraglide/messages.js'
import { useRecordingShortcut } from '~/providers/recording-shortcut'
import { usePreferenceProvider, type AfterRecording } from '~/providers/preference'
import { SettingsGroup, SettingsRow, rowControlClass } from './shared'

type PermissionKind = 'microphone' | 'system_audio'

const statusLabels: Record<PermissionStatus, () => string> = {
	granted: m.permissionGranted,
	denied: m.permissionDenied,
	not_determined: m.permissionNotDetermined,
	restricted: m.permissionRestricted,
	not_applicable: m.permissionNotApplicable,
}

export function normalizePermissionStatus(value: PermissionStatus | boolean): PermissionStatus {
	if (typeof value === 'boolean') return value ? 'granted' : 'denied'
	return value
}

function PermissionRow({ kind, label, description }: { kind: PermissionKind; label: string; description: string }) {
	const [status, setStatus] = useState<PermissionStatus | null>(null)
	const [checking, setChecking] = useState(true)
	const statusCommand = kind === 'microphone' ? 'get_microphone_permission_status' : 'get_system_audio_permission_status'
	const requestCommand = kind === 'microphone' ? 'request_microphone_permission' : 'request_system_audio_permission'
	const settingsCommand = kind === 'microphone' ? 'open_microphone_settings' : 'open_system_audio_settings'

	const refresh = useCallback(async () => {
		setChecking(true)
		try {
			setStatus(await invoke<PermissionStatus>(statusCommand))
		} catch (error) {
			console.error(`Could not check ${kind} permission:`, error)
			setStatus(null)
		} finally {
			setChecking(false)
		}
	}, [kind, statusCommand])

	useEffect(() => {
		void refresh()
		window.addEventListener('focus', refresh)
		return () => window.removeEventListener('focus', refresh)
	}, [refresh])

	async function requestAccess() {
		setChecking(true)
		try {
			const result = await invoke<PermissionStatus | boolean>(requestCommand)
			setStatus(normalizePermissionStatus(result))
		} catch (error) {
			console.error(`Could not request ${kind} permission:`, error)
			toast.error(m.permissionCheckFailed(), { description: String(error) })
		} finally {
			setChecking(false)
		}
	}

	async function openSettings() {
		try {
			await invoke(settingsCommand)
		} catch (error) {
			console.error(`Could not open ${kind} settings:`, error)
			toast.error(m.permissionCheckFailed(), { description: String(error) })
		}
	}

	const showSettings = status === 'denied' || status === 'restricted'
	// The system-audio preflight API cannot distinguish "never asked" from denied, so keep the
	// request action available there; a denied microphone has a precise status and goes to Settings.
	const showRequest = status === 'not_determined' || status === null || (kind === 'system_audio' && status === 'denied')

	return (
		<SettingsRow label={label} description={description}>
			<span className="text-xs text-muted-foreground">
				{checking ? m.permissionChecking() : status ? statusLabels[status]() : m.permissionCheckFailed()}
			</span>
			{showRequest && (
				<Button variant="outline" size="sm" disabled={checking} onClick={requestAccess}>
					{m.requestPermission()}
				</Button>
			)}
			{showSettings && (
				<Button variant="outline" size="sm" disabled={checking} onClick={openSettings}>
					{m.openSystemSettings()}
				</Button>
			)}
		</SettingsRow>
	)
}

/**
 * Google Meet is recognised by reading the browser's window title, which macOS gates behind Screen
 * Recording. Zoom and Teams come from the process list and work without it, so this never blocks
 * the feature — it says which half is missing, and offers the one place that can fix it.
 */
function MeetPermissionRow({ enabled }: { enabled: boolean }) {
	const [status, setStatus] = useState<PermissionStatus | null>(null)

	const refresh = useCallback(async () => {
		try {
			setStatus(await invoke<PermissionStatus>('get_screen_recording_permission_status'))
		} catch (error) {
			console.error('Could not check screen recording permission:', error)
			setStatus(null)
		}
	}, [])

	useEffect(() => {
		if (!enabled) return
		void refresh()
		// macOS reports the new answer only once the app is focused again after a grant.
		window.addEventListener('focus', refresh)
		return () => window.removeEventListener('focus', refresh)
	}, [enabled, refresh])

	// The prompt only ever appears once per app; asking on enable is the single chance to show it.
	useEffect(() => {
		if (!enabled || status !== 'not_determined') return
		void invoke<PermissionStatus>('request_screen_recording_permission')
			.then(setStatus)
			.catch((error) => console.error('Could not request screen recording permission:', error))
	}, [enabled, status])

	if (!enabled || status === null || status === 'granted' || status === 'not_applicable') return null

	return (
		<SettingsRow label={m.meetPermission()} description={m.meetPermissionInfo()}>
			<button
				type="button"
				onClick={() => void invoke('open_screen_recording_settings')}
				className="cursor-pointer text-xs font-medium text-primary-strong underline-offset-4 hover:underline">
				{m.openSystemSettings()}
			</button>
		</SettingsRow>
	)
}

/** Turning it on asks macOS for calendar access; without it the switch stays off. */
function CalendarNamingRow() {
	const { calendarNaming, setCalendarNaming } = usePreferenceProvider()
	const change = useCallback(
		async (on: boolean) => {
			if (!on) return setCalendarNaming(false)
			const status = await requestCalendarPermission().catch(() => 'denied' as const)
			if (status === 'granted') return setCalendarNaming(true)
			toast.error(m.calendarPermissionDenied(), {
				position: 'bottom-center',
				action: { label: m.openSystemSettings(), onClick: () => void openCalendarSettings() },
			})
		},
		[setCalendarNaming],
	)
	return (
		<SettingsRow label={m.calendarNaming()} description={m.calendarNamingInfo()}>
			<Switch checked={calendarNaming} onCheckedChange={(on) => void change(on)} aria-label={m.calendarNaming()} />
		</SettingsRow>
	)
}

export function RecordingSection() {
	const shortcut = useRecordingShortcut()
	const preference = usePreferenceProvider()
	const { meetingDetectionEnabled, setMeetingDetectionEnabled, autoTranscribeAfterRecording, setAutoTranscribeAfterRecording } = preference
	const isMacOS = platform() === 'macos'

	return (
		<div className="space-y-6">
			<SettingsGroup title={m.meetingDetection()}>
				<SettingsRow label={<MeetingServiceIcons label={m.supportedMeetingServices()} />} description={m.meetingDetectionInfo()}>
					<Switch checked={meetingDetectionEnabled} onCheckedChange={setMeetingDetectionEnabled} aria-label={m.meetingDetection()} />
				</SettingsRow>
				<MeetPermissionRow enabled={meetingDetectionEnabled} />
			</SettingsGroup>

			<SettingsGroup title={m.liveTranscription()}>
				<SettingsRow label={m.liveTranscriptionSetting()} description={m.liveTranscriptionSettingInfo()}>
					<Switch
						checked={preference.liveTranscription}
						onCheckedChange={preference.setLiveTranscription}
						aria-label={m.liveTranscriptionSetting()}
					/>
				</SettingsRow>
				{preference.liveTranscription && (
					<SettingsRow label={m.livePartialsSetting()} description={m.livePartialsSettingInfo()}>
						<Switch checked={preference.livePartials} onCheckedChange={preference.setLivePartials} aria-label={m.livePartialsSetting()} />
					</SettingsRow>
				)}
				{preference.liveTranscription && (
					<SettingsRow label={m.liveBatterySaver()} description={m.liveBatterySaverInfo()}>
						<Select value={String(preference.livePushSeconds)} onValueChange={(value) => preference.setLivePushSeconds(Number(value))}>
							<SelectTrigger className={`w-56 ${rowControlClass}`} aria-label={m.liveBatterySaver()}>
								<SelectValue />
							</SelectTrigger>
							<SelectContent>
								<SelectItem value="0">{m.liveBatterySaverOff()}</SelectItem>
								<SelectItem value="10">{m.liveBatterySaverEvery10()}</SelectItem>
								<SelectItem value="30">{m.liveBatterySaverEvery30()}</SelectItem>
							</SelectContent>
						</Select>
					</SettingsRow>
				)}
				{preference.liveTranscription && (
					<SettingsRow label={m.afterRecording()} description={m.afterRecordingInfo()}>
						<Select value={preference.afterRecording} onValueChange={(value: AfterRecording) => preference.setAfterRecording(value)}>
							<SelectTrigger className={`w-56 ${rowControlClass}`} aria-label={m.afterRecording()}>
								<SelectValue />
							</SelectTrigger>
							<SelectContent>
								<SelectItem value="keep">{m.afterRecordingKeep()}</SelectItem>
								<SelectItem value="retranscribe">{m.afterRecordingRetranscribe()}</SelectItem>
							</SelectContent>
						</Select>
					</SettingsRow>
				)}
			</SettingsGroup>

			{isMacOS && (
				<SettingsGroup title={m.calendarGroup()}>
					<CalendarNamingRow />
				</SettingsGroup>
			)}

			<SettingsGroup title={m.recordingControls()}>
				<SettingsRow label={m.autoTranscribeAfterRecording()} description={m.autoTranscribeAfterRecordingInfo()}>
					<Switch
						checked={autoTranscribeAfterRecording}
						onCheckedChange={setAutoTranscribeAfterRecording}
						aria-label={m.autoTranscribeAfterRecording()}
					/>
				</SettingsRow>
				<SettingsRow label={m.recordingShortcutEnabled()} description={m.recordingShortcutEnabledInfo()}>
					<Switch checked={shortcut.recordingShortcutEnabled} onCheckedChange={shortcut.setRecordingShortcutEnabled} />
				</SettingsRow>
				{shortcut.recordingShortcutEnabled && (
					<SettingsRow label={m.recordingShortcut()} description={m.recordingShortcutInfo()}>
						<ShortcutRecorder
							value={shortcut.recordingShortcut}
							onChange={shortcut.setRecordingShortcut}
							defaultValue={getDefaultRecordingShortcut()}
							onCapturingChange={shortcut.setRecordingShortcutCapturing}
						/>
					</SettingsRow>
				)}
			</SettingsGroup>

			{isMacOS && (
				<SettingsGroup title={m.recordingPermissions()}>
					<PermissionRow kind="microphone" label={m.microphonePermission()} description={m.microphonePermissionInfo()} />
					<PermissionRow kind="system_audio" label={m.systemAudioPermission()} description={m.systemAudioPermissionInfo()} />
				</SettingsGroup>
			)}
		</div>
	)
}
