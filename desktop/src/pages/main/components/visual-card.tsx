import { AnimatePresence, MotionConfig, motion, type Variants } from 'framer-motion'
import { ArrowDown, RotateCcw, Undo2 } from 'lucide-react'
import { useState } from 'react'
import { m } from '~/paraglide/messages.js'
import { cn } from '~/lib/style'
import { visualDirection, type Visual } from '~/lib/visuals'

/** Distinct in light and dark; a row's own `#hex` wins. Same order as the Android app. */
const PALETTE = ['#007AFF', '#FF3B30', '#34C759', '#FF9500', '#AF52DE', '#5AC8FA', '#FF2D55', '#FFCC00']
const colorOf = (index: number, own?: string | null) => own ?? PALETTE[index % PALETTE.length]
const numbers = new Intl.NumberFormat(undefined, { maximumFractionDigits: 1 })
const EASE = [0.4, 0, 0.2, 1] as const

/** The card settles in first, then its parts follow one after another. */
const card: Variants = {
	hidden: { opacity: 0, scale: 0.97 },
	show: { opacity: 1, scale: 1, transition: { duration: 0.3, ease: EASE, when: 'beforeChildren', staggerChildren: 0.09 } },
}
const rise: Variants = {
	hidden: { opacity: 0, y: 12 },
	show: { opacity: 1, y: 0, transition: { duration: 0.4, ease: EASE } },
}
const grow: Variants = {
	hidden: { scaleX: 0 },
	show: { scaleX: 1, transition: { duration: 0.8, ease: EASE } },
}

/**
 * One visual of a summary. It animates when it scrolls into view, not when it mounts: visuals sit
 * below the fold and would be done before anyone got there. Its direction comes from its own text,
 * so a Hebrew chart reads right to left in an English interface.
 */
export function VisualCard({ visual }: { visual: Visual }) {
	const dir = visualDirection(visual) ?? undefined
	return (
		<MotionConfig reducedMotion="user">
			<motion.figure
				dir={dir}
				variants={card}
				initial="hidden"
				whileInView="show"
				viewport={{ once: true, amount: 0.3 }}
				className="not-prose my-6 rounded-2xl border border-border bg-card p-5 text-card-foreground">
				{visual.title.trim() && <figcaption className="mb-4 text-sm font-semibold">{visual.title}</figcaption>}
				{visual.kind === 'chart' && (visual.pie ? <Donut visual={visual} /> : <Bars visual={visual} />)}
				{visual.kind === 'flow' && <Flow visual={visual} />}
				{visual.kind === 'timeline' && <Timeline visual={visual} />}
				{visual.kind === 'tree' && <Tree visual={visual} />}
				{visual.kind === 'compare' && <Compare visual={visual} />}
			</motion.figure>
		</MotionConfig>
	)
}

type Of<K extends Visual['kind']> = Extract<Visual, { kind: K }>

function Bars({ visual }: { visual: Of<'chart'> }) {
	const max = Math.max(...visual.rows.map((row) => row.value))
	return (
		<div className="space-y-3">
			{visual.rows.map((row, i) => (
				<motion.div key={i} variants={rise} className="space-y-1.5">
					<div className="flex items-baseline gap-3 text-sm">
						<span className="flex-1">{row.label}</span>
						<span className="font-semibold tabular-nums">{numbers.format(row.value)}</span>
					</div>
					<div className="h-2.5 overflow-hidden rounded-full bg-muted">
						<motion.div
							variants={grow}
							className="h-full rounded-full origin-left rtl:origin-right"
							style={{ width: `${(row.value / max) * 100}%`, backgroundColor: colorOf(i, row.color) }}
						/>
					</div>
				</motion.div>
			))}
		</div>
	)
}

function Donut({ visual }: { visual: Of<'chart'> }) {
	const total = visual.rows.reduce((sum, row) => sum + row.value, 0)
	// Round ends reach half a stroke past each arc, so slices sit a stroke (plus a hairline) apart.
	const gap = visual.rows.length > 1 ? (14 + 4) / (2 * Math.PI * 46) : 0
	let start = 0
	const arcs = visual.rows.map((row, i) => {
		const share = row.value / total
		const arc = { i, start, length: Math.max(share - gap, 0.001), color: colorOf(i, row.color) }
		start += share
		return arc
	})
	return (
		<div className="flex flex-wrap items-center justify-center gap-6">
			<svg viewBox="0 0 120 120" className="h-40 w-40 shrink-0 -rotate-90" role="img" aria-label={visual.rows.map((row) => `${row.label} ${Math.round((row.value / total) * 100)}%`).join(', ')}>
				<circle cx="60" cy="60" r="46" fill="none" strokeWidth="14" className="stroke-muted" />
				{arcs.map((arc) => (
					// The SVG attribute rotates about the exact centre; framer's origin on SVG is relative to
					// each shape's own box, which put every slice on a different pivot.
					<g key={arc.i} transform={`rotate(${arc.start * 360 + gap * 180} 60 60)`}>
						<motion.circle
							cx="60"
							cy="60"
							r="46"
							fill="none"
							strokeWidth="14"
							strokeLinecap="round"
							stroke={arc.color}
							variants={{
								hidden: { pathLength: 0 },
								// All slices grow at once, each from its own start.
								show: { pathLength: arc.length, transition: { duration: 0.9, ease: EASE } },
							}}
						/>
					</g>
				))}
			</svg>
			<ul className="min-w-48 flex-1 space-y-2">
				{visual.rows.map((row, i) => (
					<motion.li key={i} variants={rise} className="flex items-center gap-2.5 text-sm">
						<span className="h-3 w-3 shrink-0 rounded-full" style={{ backgroundColor: colorOf(i, row.color) }} />
						<span className="flex-1">{row.label}</span>
						<span className="font-semibold tabular-nums">{Math.round((row.value / total) * 100)}%</span>
					</motion.li>
				))}
			</ul>
		</div>
	)
}

function Flow({ visual }: { visual: Of<'flow'> }) {
	return (
		<div className="flex flex-col items-center">
			{visual.layers.map((names, level) => (
				<motion.div key={level} variants={rise} className="flex w-full flex-col items-center">
					{level > 0 && <ArrowDown className="my-1.5 h-4 w-4 text-muted-foreground" />}
					<div className="flex flex-wrap justify-center gap-2">
						{names.map((name) => {
							const color = colorOf(level)
							return (
								<span
									key={name}
									className="rounded-xl border px-3.5 py-2 text-center text-sm"
									style={{ borderColor: `${color}8c`, backgroundColor: `${color}1a` }}>
									{name}
								</span>
							)
						})}
					</div>
				</motion.div>
			))}
		</div>
	)
}

function Timeline({ visual }: { visual: Of<'timeline'> }) {
	return (
		<ol>
			{visual.events.map((event, i) => {
				const last = i === visual.events.length - 1
				return (
					<motion.li key={i} variants={rise} className="flex gap-3">
						<div className="flex w-3 flex-col items-center">
							<span className="mt-1 h-3 w-3 shrink-0 rounded-full" style={{ backgroundColor: colorOf(i) }} />
							{!last && <span className="w-0.5 flex-1 bg-border" />}
						</div>
						<div className={cn('space-y-0.5', !last && 'pb-5')}>
							<p className="text-xs font-medium text-primary">{event.time}</p>
							<p className="text-sm font-semibold">{event.title}</p>
							{event.detail.trim() && <p className="text-sm text-muted-foreground">{event.detail}</p>}
						</div>
					</motion.li>
				)
			})}
		</ol>
	)
}

/** Interactive: one node at a time, its choices as buttons; a choice to a missing node is not offered. */
function Tree({ visual }: { visual: Of<'tree'> }) {
	const [path, setPath] = useState([visual.start])
	const id = path[path.length - 1]
	const node = visual.nodes[id]
	const choices = node.choices.filter(([, target]) => target in visual.nodes)
	return (
		<motion.div variants={rise}>
			<AnimatePresence mode="wait" initial={false}>
				<motion.div key={path.join('/')} initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }} transition={{ duration: 0.18 }} className="space-y-2.5">
					<p className="font-semibold">{node.title}</p>
					{node.body.trim() && <p className="text-sm text-muted-foreground">{node.body}</p>}
					{choices.map(([label, target]) => (
						<button
							key={target + label}
							type="button"
							onClick={() => setPath([...path, target])}
							className="block w-full cursor-pointer rounded-xl border border-border px-4 py-2.5 text-start text-sm transition-colors hover:bg-muted">
							{label}
						</button>
					))}
					{path.length > 1 && (
						<div className="flex gap-1 pt-1">
							<button type="button" onClick={() => setPath(path.slice(0, -1))} className="flex cursor-pointer items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-sm text-primary hover:bg-muted">
								<Undo2 className="h-3.5 w-3.5" />
								{m.visualBack()}
							</button>
							<button type="button" onClick={() => setPath([visual.start])} className="flex cursor-pointer items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-sm text-primary hover:bg-muted">
								<RotateCcw className="h-3.5 w-3.5" />
								{m.visualStartOver()}
							</button>
						</div>
					)}
				</motion.div>
			</AnimatePresence>
		</motion.div>
	)
}

/** One card per compared item, side by side where there is room. */
function Compare({ visual }: { visual: Of<'compare'> }) {
	return (
		<div className="grid gap-3 sm:grid-cols-[repeat(auto-fit,minmax(14rem,1fr))]">
			{visual.rows.map((row, i) => {
				const color = colorOf(i)
				return (
					<motion.div key={i} variants={rise} className="space-y-2 rounded-xl border-s-4 bg-muted/50 p-3.5" style={{ borderColor: color }}>
						<p className="text-sm font-semibold" style={{ color }}>
							{row[0]}
						</p>
						{row.slice(1).map((cell, c) =>
							cell.trim() ? (
								<div key={c}>
									<p className="text-xs text-muted-foreground">{visual.header[c + 1]}</p>
									<p className="text-sm">{cell}</p>
								</div>
							) : null,
						)}
					</motion.div>
				)
			})}
		</div>
	)
}

/** Shown while the visuals pass runs: the shape of a chart, breathing. */
export function VisualSkeleton() {
	return (
		<div className="not-prose my-6 space-y-3 rounded-2xl border border-border p-5" aria-live="polite">
			<p className="text-sm text-muted-foreground">{m.visualDrawing()}</p>
			{[90, 60, 75, 40].map((width) => (
				<div key={width} className="h-2.5 animate-pulse rounded-full bg-muted" style={{ width: `${width}%` }} />
			))}
		</div>
	)
}
