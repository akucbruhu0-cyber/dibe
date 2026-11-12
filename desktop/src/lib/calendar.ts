import { invoke } from '@tauri-apps/api/core'

/**
 * Naming a recording after the calendar event it was made during, from the calendars already on
 * this Mac (EventKit, src-tauri/src/cmd/calendar.rs). No account, no server. The Android app has
 * the same rule and record shape (android/.../calendar); keep them in step.
 */

/** Mirrors `CalendarEvent` in src-tauri/src/cmd/calendar.rs. */
export interface CalendarEvent {
	id: string
	title: string
	startMs: number
	endMs: number
	calendar: string | null
	attendees: string[]
}

/** Saved on the project (`meeting` in transcript.vibe.json). Times are ISO 8601, UTC. */
export interface Meeting {
	title: string
	start: string
	end: string
	calendar?: string
	attendees?: string[]
}

export type CalendarPermission = 'granted' | 'denied' | 'not_determined' | 'restricted' | 'not_applicable'

/**
 * The event a recording belongs to: the one it overlaps most; on a tie the earlier one, then the
 * lower id. Untitled events and ones it does not overlap at all never count.
 */
export function pickEvent(events: CalendarEvent[], startMs: number, endMs: number): CalendarEvent | null {
	let best: CalendarEvent | null = null
	let bestOverlap = 0
	for (const event of events) {
		if (!event.title.trim()) continue
		const overlap = Math.min(endMs, event.endMs) - Math.max(startMs, event.startMs)
		if (overlap <= 0) continue
		const better =
			!best ||
			overlap > bestOverlap ||
			(overlap === bestOverlap && (event.startMs < best.startMs || (event.startMs === best.startMs && event.id < best.id)))
		if (better) {
			best = event
			bestOverlap = overlap
		}
	}
	return best
}

export function toMeeting(event: CalendarEvent): Meeting {
	const attendees = [...new Set(event.attendees.map((name) => name.trim()).filter(Boolean))]
	return {
		title: event.title.trim(),
		start: new Date(event.startMs).toISOString(),
		end: new Date(event.endMs).toISOString(),
		...(event.calendar ? { calendar: event.calendar } : {}),
		...(attendees.length ? { attendees } : {}),
	}
}

/** The meeting a recording was made during, or null without access, an event, or on any error. */
export async function meetingDuring(startMs: number, endMs: number): Promise<Meeting | null> {
	try {
		const events = await invoke<CalendarEvent[]>('calendar_events', { startMs, endMs })
		const event = pickEvent(events, startMs, endMs)
		return event ? toMeeting(event) : null
	} catch (error) {
		console.warn('calendar lookup failed:', error)
		return null
	}
}

export const calendarPermission = () => invoke<CalendarPermission>('get_calendar_permission_status')
export const requestCalendarPermission = () => invoke<CalendarPermission>('request_calendar_permission')
export const openCalendarSettings = () => invoke<void>('open_calendar_settings')

/** Read back from a record; anything malformed is no meeting. */
export function parseMeeting(value: unknown): Meeting | undefined {
	if (typeof value !== 'object' || value === null) return undefined
	const { title, start, end, calendar, attendees } = value as Record<string, unknown>
	if (typeof title !== 'string' || typeof start !== 'string' || typeof end !== 'string') return undefined
	const names = Array.isArray(attendees) ? attendees.filter((name): name is string => typeof name === 'string') : []
	return { title, start, end, ...(typeof calendar === 'string' ? { calendar } : {}), ...(names.length ? { attendees: names } : {}) }
}

/**
 * YAML frontmatter for a Markdown export of a meeting, so Obsidian (or any notes app that reads
 * it) gets the date and the people. Strings are JSON literals: valid YAML double-quoted scalars.
 * Must match `meetingFrontmatter` in the Android exporter byte for byte.
 */
export function meetingFrontmatter(meeting: Meeting): string {
	const lines = ['---', `date: ${meeting.start}`, `meeting: ${JSON.stringify(meeting.title)}`]
	if (meeting.calendar) lines.push(`calendar: ${JSON.stringify(meeting.calendar)}`)
	if (meeting.attendees?.length) lines.push('attendees:', ...meeting.attendees.map((name) => `  - ${JSON.stringify(name)}`))
	lines.push('---', '', '')
	return lines.join('\n')
}
