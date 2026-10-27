import { useVirtualizer } from '@tanstack/react-virtual'
import { ArrowDownToLine, Mic, PauseCircle } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Button } from '~/components/ui/button'
import { Spinner } from '~/components/ui/spinner'
import { cn } from '~/lib/style'
import { formatTimestamp, type Segment } from '~/lib/transcript'
import { m } from '~/paraglide/messages.js'
import type { LiveState, LiveView } from '../hooks/use-live-transcript'

/** Below this the text is only the normal rhythm of utterances behind, not worth a word. */
const LAG_SHOWN_MS = 5000
/** Within this many px of the end still counts as reading the live edge. */
const BOTTOM_STICK_PX = 24

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
function StatusLine({ state, still }: { state: LiveState; still: boolean }) {
	const parts = [m.recordingNow(), statusWords[state.status]?.()]
	if (state.status === 'live' && state.lagMs > LAG_SHOWN_MS) parts.push(m.liveLag({ seconds: String(Math.round(state.lagMs / 1000)) }))
	return (
		<p role="status" aria-atomic="true" className="flex items-center justify-center gap-1.5 text-[13px] text-muted-foreground">
			{/* Red, an icon and a word: the state never rests on colour alone. */}
			<span aria-hidden className={cn('h-2 w-2 shrink-0 rounded-full bg-red-500', !still && 'live-pulse')} />
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

/**
 * The finals and the partial after them. Sticks to the end while the reader is there; scrolling up
 * lets go and offers the way back. A scroll counts as the reader's when it goes up: the list only
 * grows at the end, so our own scrolls only go down, or get clamped while already at the end.
 */
function Lines({ state, still }: { state: LiveState; still: boolean }) {
	const scrollRef = useRef<HTMLDivElement>(null)
	const lastTop = useRef(0)
	const [following, setFollowing] = useState(true)
	const { finals, partial } = state
	const count = finals.length + (partial ? 1 : 0)

	const virtualizer = useVirtualizer({
		count,
		getScrollElement: () => scrollRef.current,
		estimateSize: () => 28,
		getItemKey: (index) => (index < finals.length ? index : 'partial'),
		overscan: 8,
	})

	const toEnd = useCallback(() => {
		if (count > 0) virtualizer.scrollToIndex(count - 1, { align: 'end' })
	}, [count, virtualizer])

	useEffect(() => {
		if (following) toEnd()
	}, [following, toEnd, partial?.text])

	const onScroll = useCallback(() => {
		const element = scrollRef.current
		if (!element) return
		if (element.scrollHeight - element.scrollTop - element.clientHeight <= BOTTOM_STICK_PX) setFollowing(true)
		else if (element.scrollTop < lastTop.current) setFollowing(false)
		lastTop.current = element.scrollTop
	}, [])

	if (count === 0) return null

	return (
		<div className="relative w-full">
			<div
				ref={scrollRef}
				onScroll={onScroll}
				tabIndex={0}
				role="region"
				aria-label={m.liveTranscription()}
				className="max-h-48 w-full overflow-y-auto rounded-xl focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/80">
				<div className="relative w-full" style={{ height: virtualizer.getTotalSize() }}>
					{virtualizer.getVirtualItems().map((row) => {
						const isPartial = row.index >= finals.length
						const line: Segment = isPartial && partial ? partial : finals[row.index]
						// The newest final settles from the partial's grey into the text colour.
						const settling = !isPartial && row.index === finals.length - 1 && !still
						return (
							<div
								key={row.key}
								ref={virtualizer.measureElement}
								data-index={row.index}
								data-live-line
								dir="auto"
								className={cn(
									'absolute inset-x-0 top-0 flex gap-2 py-0.5 text-start text-[14px] leading-6',
									isPartial ? 'text-muted-foreground' : 'text-foreground',
									settling && 'live-settle',
								)}
								style={{ transform: `translateY(${row.start}px)` }}>
								<bdi dir="ltr" className="shrink-0 pt-px font-mono text-[11px] tabular-nums text-muted-foreground">
									{formatTimestamp(line.start, false, '', false)}
								</bdi>
								<span className="min-w-0">{line.text}</span>
							</div>
						)
					})}
				</div>
			</div>

			{!following && (
				<button
					type="button"
					onClick={() => {
						setFollowing(true)
						toEnd()
					}}
					className="absolute inset-x-0 bottom-2 mx-auto flex w-fit cursor-pointer items-center gap-1.5 rounded-full border border-border bg-card/95 px-3 py-1.5 text-[12px] font-medium text-foreground shadow-sm backdrop-blur hover:bg-muted">
					<ArrowDownToLine aria-hidden className="h-3.5 w-3.5 text-muted-foreground" />
					{m.jumpToLive()}
				</button>
			)}
		</div>
	)
}

/** The recording indicator and, while it runs, the transcript as it is spoken. */
export function LiveTranscript({ live }: { live: LiveView }) {
	const still = prefersReducedMotion()
	return (
		<div className="flex w-full flex-col gap-3">
			<StatusLine state={live.state} still={still} />
			{live.state.status === 'paused' && <PausedBanner live={live} />}
			<Lines state={live.state} still={still} />
		</div>
	)
}
