// @vitest-environment jsdom

import { describe, expect, it } from 'vitest'
import { DEFAULT_AI } from '~/lib/ai'
import type { Job } from './use-transcribe-queue'
import { shouldAutoSummarize, shouldAutoTitle } from './use-summaries'

const segments = [{ start: 0, stop: 1, text: 'New transcript' }]

function completed(overrides: Partial<Pick<Job, 'status' | 'hydrated' | 'summary' | 'segments'>> = {}) {
	return { status: 'done', hydrated: false, summary: undefined, segments, ...overrides } as Pick<Job, 'status' | 'hydrated' | 'summary' | 'segments'>
}

describe('automatic summaries', () => {
	it('is opt-in by default', () => {
		expect(DEFAULT_AI.tasks.summary.autoOnFinish).toBe(false)
	})

	it('runs only when a session job transitions to done', () => {
		expect(shouldAutoSummarize(completed(), 'running')).toBe(true)
		expect(shouldAutoSummarize(completed(), undefined)).toBe(false)
		expect(shouldAutoSummarize(completed(), 'done')).toBe(false)
	})

	it('never auto-runs for hydrated, empty, or already summarized jobs', () => {
		expect(shouldAutoSummarize(completed({ hydrated: true }), 'running')).toBe(false)
		expect(shouldAutoSummarize(completed({ segments: [] }), 'running')).toBe(false)
		expect(shouldAutoSummarize(completed({ summary: 'Existing' }), 'running')).toBe(false)
	})
})

describe('automatic titles', () => {
	const recording = (overrides: Partial<Job> = {}) =>
		({ status: 'done', hydrated: false, source: 'record', name: 'Record-2026-11-12 08-00-00', segments, ...overrides }) as Job

	it('titles a recording that arrives with its live transcript, and every finished (re-)transcription', () => {
		expect(shouldAutoTitle(recording({ hydrated: true }), undefined)).toBe(true)
		expect(shouldAutoTitle(recording({ hydrated: true }), 'running')).toBe(true)
		// Re-transcribed from Recents: a new job of source 'file' that keeps the recording's name.
		expect(shouldAutoTitle(recording({ source: 'file' }), 'running')).toBe(true)
		// An imported file keeps its app-given File- name until the AI names it.
		expect(shouldAutoTitle(recording({ source: 'file', name: 'File-ראיון עם סבא' }), 'running')).toBe(true)
	})

	it('leaves named, empty, unfinished and merely opened projects alone', () => {
		expect(shouldAutoTitle(recording({ name: 'Weekly sync' }), 'running')).toBe(false)
		expect(shouldAutoTitle(recording({ segments: [] }), 'running')).toBe(false)
		expect(shouldAutoTitle(recording({ status: 'running' }), 'queued')).toBe(false)
		expect(shouldAutoTitle(recording(), 'done')).toBe(false)
		expect(shouldAutoTitle(recording({ hydrated: true, source: undefined }), undefined)).toBe(false)
	})
})
