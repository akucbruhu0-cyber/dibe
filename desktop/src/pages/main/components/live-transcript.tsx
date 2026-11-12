import { downloadDir, join } from '@tauri-apps/api/path'
import * as dialog from '@tauri-apps/plugin-dialog'
import * as fs from '@tauri-apps/plugin-fs'
import { AArrowDown, AArrowUp, ArrowDownToLine, Download, Mic, PauseCircle, TextAlignJustify } from 'lucide-react'
import { useCallback, useEffect, useLayoutEffect, useRef, useState, type ReactNode, type RefObject } from 'react'
import { toast } from 'sonner'
import { Button } from '~/components/ui/button'
import { Spinner } from '~/components/ui/spinner'
import { CONFIG_KEYS } from '~/lib/config-keys'
import { usePersisted } from '~/lib/config-store'
import { recordingErrorMessage } from '~/lib/recording-session'
import { cn } from '~/lib/style'
import { asSrt, asText, asVtt, formatTimestamp, type Segment } from '~/lib/transcript'
import { m } from '~/paraglide/messages.js'
import { applyLiveEdits, type LiveEdits, type LiveState, type LiveView } from '../hooks/use-live-transcript'

/** Below this the text is only the normal rhythm of utterances behind, not worth a word. */
const LAG_SHOWN_MS = 5000
/** Within this many px of the end still counts as reading the live edge. */
const BOTTOM_STICK_PX = 24
const FONT_SIZES = { min: 12, max: 28, step: 2, initial: 15 }

/** Read on every render: a recording re-renders every second anyway. */
function prefersReducedMotion() {
	return typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

const statusWords: Record<LiveState['status'], (() => string) | null> = {
	off: null,
	starting: m.liveStarting,
	live: m.liveTranscription,
	paused: m.livePaused,
	ended: m.liveEnded,
}

/**
 * The one live region of the recording screen, read whole on every change. It carries the state and
 * the lag; the timer above it ticks every second and stays out, or VoiceOver would read it nonstop.
 */
function StatusLine({ state, still, paused }: { state: LiveState; still: boolean; paused: boolean }) {
	// A paused recording sends nothing, so whatever the transcription says of itself is beside the point.
	const parts = paused ? [m.recordingPausedNow()] : [m.recordingNow(), statusWords[state.status]?.()]
	if (!paused && state.status === 'live' && state.lagMs > LAG_SHOWN_MS) parts.push(m.liveLag({ seconds: String(Math.round(state.lagMs / 1000)) }))
	return (
		<p role="status" aria-atomic="true" className="flex items-center justify-center gap-1.5 text-[13px] text-muted-foreground">
			{/* Red, an icon and a word: the state never rests on colour alone. */}
			<span
				aria-hidden
				className={cn('h-2 w-2 shrink-0 rounded-full', paused ? 'bg-muted-foreground' : 'bg-red-500', !still && !paused && 'live-pulse')}
			/>
			<Mic aria-hidden className="h-3.5 w-3.5 shrink-0" />
			<span>{parts.filter(Boolean).join(' · ')}</span>
		</p>
	)
}

/** Not an alert: the recording goes on, and this is only about the text. */
function PausedBanner({ live }: { live: LiveView }) {
	return (
		<div className="flex w-full items-center gap-2.5 rounded-xl border border-border bg-muted/50 px-3 py-2 text-[13px] text-foreground">
			<PauseCircle aria-hidden className="h-4 w-4 shrink-0 text-muted-foreground" />
			<span className="min-w-0 flex-1 text-start">{m.livePausedBanner()}</span>
			<Button variant="outline" size="sm" className="h-7 shrink-0 rounded-lg" disabled={live.retrying} onClick={live.retry}>
				{live.retrying && <Spinner className="h-3.5 w-3.5" />}
				{m.tryAgain()}
			</Button>
		</div>
	)
}

/** The reader is selecting in the transcript: the view must not move the selection away. */
function selectingInside(element: HTMLElement | null) {
	const selection = window.getSelection()
	return Boolean(element && selection && !selection.isCollapsed && element.contains(selection.anchorNode))
}

/**
 * Sticks to the end while the reader is there; scrolling up lets go and offers the way back. A scroll
 * counts as the reader's when it goes up: the text only grows at the end, so our own scrolls only go
 * down, or get clamped while already at the end. `changed` is whatever grows the text.
 */
function useFollow(scrollRef: RefObject<HTMLDivElement | null>, changed: string) {
	const lastTop = useRef(0)
	const [following, setFollowing] = useState(true)

	const scrollToEnd = useCallback(() => {
		const element = scrollRef.current
		if (element) element.scrollTop = element.scrollHeight
	}, [scrollRef])

	useEffect(() => {
		if (following && !selectingInside(scrollRef.current)) scrollToEnd()
	}, [following, scrollToEnd, scrollRef, changed])

	const onScroll = useCallback(() => {
		const element = scrollRef.current
		if (!element) return
		if (element.scrollHeight - element.scrollTop - element.clientHeight <= BOTTOM_STICK_PX) setFollowing(true)
		else if (element.scrollTop < lastTop.current) setFollowing(false)
		lastTop.current = element.scrollTop
	}, [scrollRef])

	const jump = useCallback(() => {
		setFollowing(true)
		scrollToEnd()
	}, [scrollToEnd])

	return { following, onScroll, jump }
}

/**
 * The scrolling region the transcript is read in, with the way back to the live edge. Cmd/Ctrl+A in
 * it takes the whole transcript, not the whole window.
 */
function Scroller({ scrollRef, follow, children }: { scrollRef: RefObject<HTMLDivElement | null>; follow: ReturnType<typeof useFollow>; children: ReactNode }) {
	return (
		<div className="relative min-h-0 w-full flex-1">
			<div
				ref={scrollRef}
				onScroll={follow.onScroll}
				onKeyDown={(event) => {
					if (!(event.metaKey || event.ctrlKey) || event.key.toLowerCase() !== 'a') return
					const host = event.currentTarget.querySelector('[data-live-host]')
					if (!host) return
					event.preventDefault()
					window.getSelection()?.selectAllChildren(host)
				}}
				tabIndex={0}
				role="region"
				aria-label={m.liveTranscription()}
				className="h-full min-h-48 w-full overflow-y-auto rounded-xl focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/80">
				{children}
			</div>

			{!follow.following && (
				<button
					type="button"
					onClick={follow.jump}
					className="absolute inset-x-0 bottom-2 mx-auto flex w-fit cursor-pointer items-center gap-1.5 glass rounded-full border px-3 py-1.5 text-[12px] font-medium text-foreground hover:bg-card">
					<ArrowDownToLine aria-hidden className="h-3.5 w-3.5 text-muted-foreground" />
					{m.jumpToLive()}
				</button>
			)}
		</div>
	)
}

/** A final's words as the reader last left them. */
function shownText(final: Segment, edit: { start: number; text: string } | undefined) {
	return edit?.start === final.start ? edit.text : final.text
}

/**
 * A timed line: the time is CSS content hanging at the line's start, so it is seen but never
 * selected, copied or edited, and wrapped lines align under the words.
 */
const LINE_CLASS =
	'relative block py-0.5 ps-12 text-justify leading-[1.6] before:absolute before:start-0 before:top-[0.45em] before:font-mono before:text-[11px] before:leading-none before:tabular-nums before:text-muted-foreground before:content-[attr(data-time)]'

interface TranscriptProps {
	state: Pick<LiveState, 'finals' | 'partial'>
	edits: LiveEdits
	onEdit: LiveView['edit']
	still: boolean
	/** One paragraph without times, instead of a timed line each. */
	asBlock: boolean
}

/**
 * The finals as one editable document, and the partial after it. One editing host for every line:
 * the caret, a selection, Cmd+A and Backspace all cross from line to line as in any document.
 *
 * The host's lines are written here, not by React, and only ever appended: React re-rendering the
 * host would reset the caret and undo the reader's typing every time a new final arrives. Each line
 * carries its final's index, so after an edit every line is read back and the changed ones reported;
 * a line that disappeared (joined into the one before it, or deleted) is reported empty.
 */
export function EditableTranscript({ state, edits, onEdit, still, asBlock }: TranscriptProps) {
	const hostRef = useRef<HTMLDivElement & HTMLSpanElement>(null)
	const written = useRef(0)
	const { finals, partial } = state

	useLayoutEffect(() => {
		const host = hostRef.current
		if (!host) return
		// A new recording: start over.
		if (finals.length < written.current) {
			host.replaceChildren()
			written.current = 0
		}
		for (let index = written.current; index < finals.length; index++) {
			const text = shownText(finals[index], edits.get(index))
			if (!text.trim()) continue // Deleted by the reader.
			const line = document.createElement(asBlock ? 'span' : 'div')
			line.dataset.index = String(index)
			if (asBlock) {
				line.textContent = host.childNodes.length > 0 ? ` ${text}` : text
			} else {
				line.dataset.liveLine = ''
				line.dataset.time = formatTimestamp(finals[index].start, false, '', false)
				line.dir = 'auto'
				line.className = LINE_CLASS
				line.textContent = text
				// The newest final settles from the partial's grey into the text colour.
				if (!still && index === finals.length - 1) line.classList.add('live-settle')
			}
			host.append(line)
		}
		written.current = finals.length
	}, [finals, finals.length, edits, asBlock, still])

	const readBack = useCallback(() => {
		const host = hostRef.current
		if (!host) return
		const texts = new Map<number, string>()
		let current = -1
		// Text the browser left outside any line (typing after deleting everything) joins the line before it.
		for (const node of host.childNodes) {
			const index = node instanceof HTMLElement && node.dataset.index ? Number(node.dataset.index) : current
			current = Math.max(index, 0)
			texts.set(current, (texts.get(current) ?? '') + (node.textContent ?? ''))
		}
		for (let index = 0; index < written.current; index++) {
			const text = asBlock ? (texts.get(index) ?? '').trim() : (texts.get(index) ?? '')
			if (text !== shownText(finals[index], edits.get(index))) onEdit(index, text)
		}
	}, [asBlock, edits, finals, onEdit])

	// Inline inside the block's paragraph (a <div> may not sit in a <p>), a block of its own otherwise.
	const Host = asBlock ? 'span' : 'div'
	const host = (
		<Host
			ref={hostRef}
			data-live-host
			contentEditable="plaintext-only"
			suppressContentEditableWarning
			spellCheck={false}
			onInput={readBack}
			// A line stays a line: Enter would make a new one that no final owns.
			onKeyDown={(event) => event.key === 'Enter' && event.preventDefault()}
			className={cn('text-justify text-foreground outline-none', asBlock ? 'inline leading-[1.8]' : 'block')}
		/>
	)

	if (asBlock) {
		return (
			<p data-live-block dir="auto" className="text-justify leading-[1.8]">
				{host}
				{partial && <span className="text-muted-foreground">{(finals.length > 0 ? ' ' : '') + partial.text}</span>}
			</p>
		)
	}
	return (
		<>
			{host}
			{partial && (
				<div data-live-line data-time={formatTimestamp(partial.start, false, '', false)} dir="auto" className={cn(LINE_CLASS, 'text-muted-foreground')}>
					{partial.text}
				</div>
			)}
		</>
	)
}

/** The live transcript: timed lines or one block, editable, scrolling with the speech. */
function Transcript(props: TranscriptProps & { fontSize: number }) {
	const scrollRef = useRef<HTMLDivElement>(null)
	const { finals, partial } = props.state
	const follow = useFollow(scrollRef, `${finals.length}:${partial?.text ?? ''}`)

	if (finals.length === 0 && !partial) return null

	return (
		<Scroller scrollRef={scrollRef} follow={follow}>
			<div style={{ fontSize: props.fontSize }}>
				{/* Remounted on a switch of layout: its lines are rebuilt in the other shape. */}
				<EditableTranscript key={props.asBlock ? 'block' : 'lines'} {...props} />
			</div>
		</Scroller>
	)
}

/** What the reader sees right now, edits and partial included: the recording and its transcription go on. */
async function saveSoFar({ finals, partial }: LiveState, edits: LiveEdits, asBlock: boolean) {
	const edited = applyLiveEdits(finals, edits)
	const segments = partial ? [...edited, partial] : edited
	const stamp = new Date().toISOString().slice(0, 16).replace('T', ' ').replace(':', '-')
	let defaultPath = `live ${stamp}.txt`
	try {
		defaultPath = await join(await downloadDir(), defaultPath)
	} catch {
		// No Downloads folder here: the dialog starts wherever it likes.
	}
	const target = await dialog.save({
		defaultPath,
		canCreateDirectories: true,
		filters: [
			{ name: 'Text', extensions: ['txt'] },
			{ name: 'SubRip', extensions: ['srt'] },
			{ name: 'WebVTT', extensions: ['vtt'] },
		],
	})
	if (!target) return
	const extension = target.split('.').pop()?.toLowerCase()
	const text = asBlock ? `${segments.map((segment) => segment.text.trim()).join(' ')}\n` : asText(segments)
	const content = extension === 'srt' ? asSrt(segments) : extension === 'vtt' ? `WEBVTT\n\n${asVtt(segments)}` : text
	await fs.writeTextFile(target, content)
	toast.success(m.liveDownloaded(), { position: 'bottom-center' })
}

function ToolButton({
	label,
	onClick,
	disabled,
	pressed,
	children,
}: {
	label: string
	onClick: () => void
	disabled?: boolean
	/** A toggle: on or off. */
	pressed?: boolean
	children: ReactNode
}) {
	return (
		// Not `Button`: its transition would move under reduced motion, and this row stays still.
		<button
			type="button"
			aria-label={label}
			aria-pressed={pressed}
			title={label}
			disabled={disabled}
			onClick={onClick}
			className={cn(
				'inline-flex h-8 w-8 cursor-pointer items-center justify-center rounded-lg text-muted-foreground hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/80 disabled:cursor-default disabled:opacity-40 disabled:hover:bg-transparent',
				pressed && 'bg-muted text-foreground',
			)}>
			{children}
		</button>
	)
}

/**
 * The recording indicator and, while it runs, the transcript as it is spoken: at a size of the
 * reader's choosing, as timed lines or one block, and editable as it grows.
 */
export function LiveTranscript({ live, paused = false }: { live: LiveView; paused?: boolean }) {
	const still = prefersReducedMotion()
	const [fontSize, setFontSize] = usePersisted(CONFIG_KEYS.liveFontSize, FONT_SIZES.initial)
	const [asBlock, setAsBlock] = usePersisted(CONFIG_KEYS.liveAsBlock, false)
	const resize = (by: number) => setFontSize((size) => Math.min(FONT_SIZES.max, Math.max(FONT_SIZES.min, size + by)))
	const empty = live.state.finals.length === 0 && !live.state.partial
	const download = () => saveSoFar(live.state, live.edits, asBlock).catch((error) => toast.error(recordingErrorMessage(error)))
	return (
		<div className="flex min-h-0 w-full flex-1 flex-col gap-3">
			<div className="flex items-center gap-1">
				<div className="flex-1">
					<StatusLine state={live.state} still={still} paused={paused} />
				</div>
				<ToolButton label={m.liveOneBlock()} pressed={asBlock} onClick={() => setAsBlock(!asBlock)}>
					<TextAlignJustify aria-hidden className="h-4 w-4" />
				</ToolButton>
				<ToolButton label={m.liveTextSmaller()} disabled={fontSize <= FONT_SIZES.min} onClick={() => resize(-FONT_SIZES.step)}>
					<AArrowDown aria-hidden className="h-4 w-4" />
				</ToolButton>
				<ToolButton label={m.liveTextLarger()} disabled={fontSize >= FONT_SIZES.max} onClick={() => resize(FONT_SIZES.step)}>
					<AArrowUp aria-hidden className="h-4 w-4" />
				</ToolButton>
				<ToolButton label={m.liveDownload()} disabled={empty} onClick={() => void download()}>
					<Download aria-hidden className="h-4 w-4" />
				</ToolButton>
			</div>
			{!paused && live.state.status === 'paused' && <PausedBanner live={live} />}
			<Transcript state={live.state} edits={live.edits} onEdit={live.edit} still={still} asBlock={asBlock} fontSize={fontSize} />
		</div>
	)
}
