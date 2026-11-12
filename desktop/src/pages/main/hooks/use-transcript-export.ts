import { invoke } from '@tauri-apps/api/core'
import { dirname, downloadDir, join } from '@tauri-apps/api/path'
import * as clipboard from '@tauri-apps/plugin-clipboard-manager'
import * as dialog from '@tauri-apps/plugin-dialog'
import * as fs from '@tauri-apps/plugin-fs'
import { useCallback, useMemo } from 'react'
import { toast } from 'sonner'
import { m } from '~/paraglide/messages.js'
import { formatExtensions, type TextFormat } from '~/components/format-select'
import { openPath } from '~/lib/app'
import { toDocx } from '~/lib/docx'
import { visualToPng } from '~/lib/visual-png'
import { projectExportFilename } from '~/lib/project-name'
import { transcriptToPdf } from '~/lib/pdf/transcript-pdf'
import { serializeTranscriptExport, type TranscriptExportContent, type TranscriptExportOptions } from '~/lib/transcript-export'
import type { Meeting } from '~/lib/calendar'
import type { Segment, SpeakerNames } from '~/lib/transcript'
import type { NamedPath } from '~/lib/types'
import { usePreferenceProvider } from '~/providers/preference'

interface UseTranscriptExportOptions {
	enabled?: boolean
	/** Overrides the app's text direction for this export only. */
	direction?: 'rtl' | 'ltr'
	segments: Segment[]
	summary?: string
	file: NamedPath | null
	format: TextFormat
	content: TranscriptExportContent
	showTimestamps: boolean
	showSpeakers: boolean
	speakerNames?: SpeakerNames
	meeting?: Meeting
}

export function useTranscriptExport({
	enabled = true,
	direction,
	segments,
	summary,
	file,
	format,
	content,
	showTimestamps,
	showSpeakers,
	speakerNames,
	meeting,
}: UseTranscriptExportOptions) {
	const preference = usePreferenceProvider()
	const speakerLabel = m.speakerPrefix()
	const serializerOptions = useMemo<TranscriptExportOptions>(
		() => ({
			content,
			showTimestamps,
			showSpeakers,
			speakerLabel,
			speakerNames,
			title: file?.name ?? '',
			meeting,
			direction: direction ?? preference.textAreaDirection,
			theme: preference.exportOptions.theme,
		}),
		[
			content,
			direction,
			file?.name,
			meeting,
			preference.exportOptions.theme,
			preference.textAreaDirection,
			showSpeakers,
			showTimestamps,
			speakerLabel,
			speakerNames,
		],
	)
	const preview = useMemo(
		() => (enabled ? serializeTranscriptExport(format, segments, summary, serializerOptions) : ''),
		[enabled, format, segments, serializerOptions, summary],
	)
	const renderedPreview = useMemo(
		() =>
			enabled && (format === 'html' || format === 'docx' || format === 'pdf')
				? serializeTranscriptExport('html', segments, summary, serializerOptions)
				: undefined,
		[enabled, format, segments, serializerOptions, summary],
	)

	const copy = useCallback(async () => {
		await clipboard.writeText(preview)
		toast.success(m.exportCopied(), { position: 'bottom-center' })
	}, [preview])

	const save = useCallback(async () => {
		if (!file || (segments.length === 0 && !summary)) return false
		const extension = formatExtensions[format].slice(1)
		const suggestedName = projectExportFilename(file.name, extension)
		let defaultDirectory = await dirname(file.path)
		try {
			defaultDirectory = await downloadDir()
		} catch {
			// Some platforms may not expose a Downloads directory; use the transcript's folder.
		}
		const defaultPath = await join(defaultDirectory, suggestedName)
		const target = await dialog.save({
			filters: [{ name: '', extensions: [extension] }],
			canCreateDirectories: true,
			defaultPath,
		})
		if (!target) return false

		if (format === 'pdf') {
			const bytes = await transcriptToPdf(segments, summary ?? '', serializerOptions, {
				transcript: m.exportTranscript(),
				summary: m.exportSummary(),
			})
			await fs.writeFile(target, bytes)
		} else if (format === 'docx') {
			const document = await toDocx(file.name, segments, serializerOptions.direction, speakerLabel, {
				content,
				showTimestamps,
				showSpeakers,
				speakerNames,
				summary,
				transcriptLabel: m.exportTranscript(),
				summaryLabel: m.exportSummary(),
				drawVisual: visualToPng,
			})
			await fs.writeFile(target, new Uint8Array(await document.arrayBuffer()))
		} else {
			// This is the exact string shown in the live preview.
			await fs.writeTextFile(target, preview)
		}

		toast.success(m.saveSuccess(), {
			description: suggestedName,
			position: 'bottom-center',
			action: { label: m.showInFolder(), onClick: () => openPath({ name: '', path: target }) },
		})
		return true
	}, [
		content,
		file,
		format,
		preference.textAreaDirection,
		preference.theme,
		preview,
		segments,
		serializerOptions,
		showSpeakers,
		showTimestamps,
		speakerLabel,
		speakerNames,
		summary,
	])

	/** The transcript as a note in Apple Notes on this Mac, in the dialog's content and toggles. */
	const sendToNotes = useCallback(async () => {
		if (!file || (segments.length === 0 && !summary)) return
		try {
			const html = serializeTranscriptExport('html', segments, summary, { ...serializerOptions, theme: 'light' })
			await invoke('send_to_apple_notes', { title: file.name, html })
			toast.success(m.sentToAppleNotes(), { position: 'bottom-center' })
		} catch (error) {
			console.error('send to Apple Notes failed:', error)
			toast.error(m.appleNotesFailed(), { description: String(error), position: 'bottom-center' })
		}
	}, [file, segments, serializerOptions, summary])

	return { preview, renderedPreview, copy, save, sendToNotes }
}
