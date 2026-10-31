import { useCallback, useEffect, useRef, useState } from 'react'
import { cn } from '~/lib/style'
import { usePreferenceProvider } from '~/providers/preference'
import { useSession } from '../session'
import { applyLiveEdits, type LiveEdits } from '../hooks/use-live-transcript'
import type { Job } from '../hooks/use-transcribe-queue'
import { textSizeClass, type TranscriptViewOptions } from '../hooks/use-transcript-view'
import { EditableTranscript } from './live-transcript'
import { ProjectNameEditor } from './project-name-editor'

/** How long typing must pause before the edits reach the project, so a keystroke is not a disk write. */
const SAVE_AFTER_MS = 500

/**
 * A finished transcript as one editable document, with the live transcript's own editor: the caret,
 * a selection, Cmd+A and Backspace cross from line to line, and the times stay out of the text.
 *
 * Edits are kept against the segments as they were when editing began, so an index names the same
 * line however many lines were joined or emptied since; every save rebuilds the segments from that
 * snapshot. A save waits for a pause in typing, so exports and the summary see the edits a moment
 * later, and closing the editor saves whatever the pause has not yet.
 */
export default function TranscriptDocument({ job, options }: { job: Job; options: TranscriptViewOptions }) {
	const preference = usePreferenceProvider()
	const { setSegments, previewJobName, renameJob } = useSession().queue
	const [state] = useState(() => ({ finals: job.segments, partial: null }))
	const [edits] = useState<LiveEdits>(() => new Map())
	const timer = useRef(0)
	const pageRef = useRef<HTMLDivElement>(null)

	const save = useCallback(() => {
		window.clearTimeout(timer.current)
		timer.current = 0
		setSegments(job.id, applyLiveEdits(state.finals, edits))
	}, [edits, job.id, setSegments, state.finals])

	const onEdit = useCallback(
		(index: number, text: string) => {
			edits.set(index, { start: state.finals[index].start, text })
			window.clearTimeout(timer.current)
			timer.current = window.setTimeout(save, SAVE_AFTER_MS)
		},
		[edits, save, state.finals],
	)

	useEffect(
		() => () => {
			if (timer.current) save()
		},
		[save],
	)

	// Pressing Edit puts the caret in the text, so typing works at once and the mode is plain to see.
	useEffect(() => pageRef.current?.querySelector<HTMLElement>('[data-live-host]')?.focus({ preventScroll: true }), [])

	return (
		<div className="transcript-editor h-full min-h-0 overflow-x-hidden overflow-y-auto">
			<div ref={pageRef} dir={preference.textAreaDirection} className="mx-auto w-full max-w-[86ch] px-8 py-10 xl:max-w-[96ch]">
				<ProjectNameEditor name={job.name} onDraftChange={(name) => previewJobName(job.id, name)} onRename={(name) => renameJob(job.id, name)} />
				{/* The reader's choice to hide the times holds here too; the line keeps no gutter for them. */}
				<div className={cn(textSizeClass[options.textSize], !options.showTimestamps && '[&_[data-live-line]]:ps-0 [&_[data-live-line]]:before:hidden')}>
					<EditableTranscript state={state} edits={edits} onEdit={onEdit} still asBlock={false} />
				</div>
			</div>
		</div>
	)
}
