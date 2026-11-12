import { describe, expect, it } from 'vitest'
import { meetingFrontmatter, parseMeeting, pickEvent, toMeeting, type CalendarEvent } from './calendar'

const event = (id: string, title: string, startMs: number, endMs: number): CalendarEvent => ({ id, title, startMs, endMs, calendar: null, attendees: [] })

describe('pickEvent', () => {
	it('takes the event the recording overlaps most', () => {
		const standup = event('a', 'Standup', 0, 900)
		const review = event('b', 'Review', 900, 3600)
		expect(pickEvent([standup, review], 600, 3000)).toBe(review)
	})

	it('breaks a tie by the earlier start, then the lower id', () => {
		expect(pickEvent([event('b', 'Late', 100, 1100), event('c', 'Early', 0, 1100)], 100, 1100)?.title).toBe('Early')
		expect(pickEvent([event('z', 'Z', 0, 100), event('y', 'Y', 0, 100)], 0, 100)?.id).toBe('y')
	})

	it('ignores events that only touch the recording, and untitled ones', () => {
		expect(pickEvent([event('a', 'Before', 0, 100), event('b', ' ', 100, 300)], 100, 200)).toBeNull()
	})
})

describe('meetingFrontmatter', () => {
	it('writes date, meeting, calendar and attendees as YAML', () => {
		const meeting = toMeeting({ ...event('a', ' Q3 "plan" ', Date.UTC(2026, 10, 12, 9), Date.UTC(2026, 10, 12, 10)), calendar: 'Work', attendees: ['דנה', 'dana@x.com', 'דנה', ''] })
		expect(meetingFrontmatter(meeting)).toBe(
			'---\ndate: 2026-11-12T09:00:00.000Z\nmeeting: "Q3 \\"plan\\""\ncalendar: "Work"\nattendees:\n  - "דנה"\n  - "dana@x.com"\n---\n\n',
		)
	})

	it('leaves out what the event does not have', () => {
		expect(meetingFrontmatter({ title: 'Call', start: 'S', end: 'E' })).toBe('---\ndate: S\nmeeting: "Call"\n---\n\n')
	})
})

describe('parseMeeting', () => {
	it('keeps a well-formed meeting and drops a malformed one', () => {
		expect(parseMeeting({ title: 'Call', start: 'S', end: 'E', attendees: ['A', 3] })).toEqual({ title: 'Call', start: 'S', end: 'E', attendees: ['A'] })
		expect(parseMeeting({ title: 'Call' })).toBeUndefined()
	})
})
