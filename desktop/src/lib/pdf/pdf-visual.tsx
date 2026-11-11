import { Path, Svg, Text, View } from '@react-pdf/renderer'
import { visualDirection, type Visual } from '~/lib/visuals'

/**
 * A summary's visual on a PDF page. Only shapes are drawn as vector graphics; every label is an
 * ordinary <Text> in the document's font, so Hebrew and numbers keep their glyphs and their bidi
 * order (an SVG <text> here would fall back to a font without Hebrew and print empty boxes).
 */

const PALETTE = ['#007AFF', '#FF3B30', '#34C759', '#FF9500', '#AF52DE', '#5AC8FA', '#FF2D55', '#FFCC00']
const colorOf = (index: number, own?: string | null) => own ?? PALETTE[index % PALETTE.length]

export interface PdfInk {
	ink: string
	muted: string
}

type Of<K extends Visual['kind']> = Extract<Visual, { kind: K }>
type Dir = 'rtl' | 'ltr'

/** Start of a doughnut arc at `from` (0..1 of the turn, clockwise from the top) through `to`. */
function arcPath(cx: number, cy: number, r: number, from: number, to: number) {
	const point = (t: number) => [cx + r * Math.sin(t * 2 * Math.PI), cy - r * Math.cos(t * 2 * Math.PI)]
	const [x1, y1] = point(from)
	const [x2, y2] = point(to)
	return `M ${x1.toFixed(2)} ${y1.toFixed(2)} A ${r} ${r} 0 ${to - from > 0.5 ? 1 : 0} 1 ${x2.toFixed(2)} ${y2.toFixed(2)}`
}

export function PdfVisual({ visual, ink, fallback }: { visual: Visual; ink: PdfInk; fallback: Dir }) {
	const dir = visualDirection(visual) ?? fallback
	const align = dir === 'rtl' ? ('right' as const) : ('left' as const)
	const row = dir === 'rtl' ? ('row-reverse' as const) : ('row' as const)
	const text = { fontSize: 10, color: ink.ink, textAlign: align, direction: dir }
	const small = { ...text, fontSize: 8.5, color: ink.muted }
	const bold = { ...text, fontWeight: 700 }
	const kind = visual.kind
	return (
		<View
			wrap={kind === 'compare' || kind === 'timeline'}
			style={{ marginTop: 14, padding: 12, borderWidth: 1, borderColor: ink.muted, borderRadius: 10, borderStyle: 'solid' }}>
			{visual.title.trim() !== '' && <Text style={{ ...bold, fontSize: 11, marginBottom: 8 }}>{visual.title}</Text>}
			{kind === 'chart' && (visual.pie ? <Donut visual={visual} row={row} text={text} bold={bold} /> : <Bars visual={visual} row={row} text={text} bold={bold} dir={dir} />)}
			{kind === 'flow' && <Flow visual={visual} text={text} ink={ink} />}
			{kind === 'timeline' && <Timeline visual={visual} row={row} text={text} small={small} bold={bold} />}
			{kind === 'tree' && <Tree visual={visual} text={text} small={small} bold={bold} dir={dir} />}
			{kind === 'compare' && <Compare visual={visual} text={text} small={small} bold={bold} dir={dir} />}
		</View>
	)
}

type TextStyle = { fontSize: number; color: string; textAlign: 'right' | 'left'; direction: Dir; fontWeight?: number }

function Bars({ visual, row, text, bold, dir }: { visual: Of<'chart'>; row: 'row' | 'row-reverse'; text: TextStyle; bold: TextStyle; dir: Dir }) {
	const max = Math.max(...visual.rows.map((item) => item.value))
	return visual.rows.map((item, i) => (
		<View key={i} style={{ marginTop: i ? 7 : 0 }}>
			<View style={{ flexDirection: row }}>
				<Text style={{ ...text, flex: 1 }}>{item.label}</Text>
				<Text style={{ ...bold, direction: 'ltr' }}>{String(item.value)}</Text>
			</View>
			<View style={{ marginTop: 3, height: 6, flexDirection: dir === 'rtl' ? 'row-reverse' : 'row' }}>
				<View style={{ width: `${(item.value / max) * 100}%`, height: 6, borderRadius: 3, backgroundColor: colorOf(i, item.color) }} />
			</View>
		</View>
	))
}

function Donut({ visual, row, text, bold }: { visual: Of<'chart'>; row: 'row' | 'row-reverse'; text: TextStyle; bold: TextStyle }) {
	const total = visual.rows.reduce((sum, item) => sum + item.value, 0)
	// Round ends reach half a stroke past each arc, so slices sit a stroke (plus a hairline) apart.
	const gap = visual.rows.length > 1 ? (14 + 4) / (2 * Math.PI * 46) : 0
	let start = 0
	const arcs = visual.rows.map((item, i) => {
		const share = item.value / total
		const arc = { from: start + gap / 2, to: start + Math.max(share - gap / 2, gap / 2 + 0.001), color: colorOf(i, item.color) }
		start += share
		return arc
	})
	return (
		<View style={{ flexDirection: row, alignItems: 'center' }}>
			<Svg width={96} height={96} viewBox="0 0 120 120">
				{arcs.map((arc, i) =>
					// A full turn can't be one arc; a single slice is drawn as two halves.
					arc.to - arc.from >= 0.999 ? (
						[0, 0.5].map((half) => <Path key={`${i}-${half}`} d={arcPath(60, 60, 46, half, half + 0.5)} stroke={arc.color} strokeWidth={14} fill="none" />)
					) : (
						<Path key={i} d={arcPath(60, 60, 46, arc.from, arc.to)} stroke={arc.color} strokeWidth={14} strokeLinecap="round" fill="none" />
					),
				)}
			</Svg>
			<View style={{ flex: 1, marginHorizontal: 14 }}>
				{visual.rows.map((item, i) => (
					<View key={i} style={{ flexDirection: row, alignItems: 'center', marginTop: i ? 5 : 0 }}>
						<View style={{ width: 8, height: 8, borderRadius: 4, backgroundColor: colorOf(i, item.color) }} />
						<Text style={{ ...text, flex: 1, marginHorizontal: 6 }}>{item.label}</Text>
						<Text style={{ ...bold, direction: 'ltr' }}>{`${Math.round((item.value / total) * 100)}%`}</Text>
					</View>
				))}
			</View>
		</View>
	)
}

function Flow({ visual, text, ink }: { visual: Of<'flow'>; text: TextStyle; ink: PdfInk }) {
	return visual.layers.map((names, level) => (
		<View key={level} style={{ alignItems: 'center' }}>
			{level > 0 && (
				<Svg width={10} height={14} viewBox="0 0 10 14" style={{ marginVertical: 2 }}>
					<Path d="M5 0 V12 M1 8 L5 12 L9 8" stroke={ink.muted} strokeWidth={1.2} fill="none" />
				</Svg>
			)}
			<View style={{ flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'center' }}>
				{names.map((name) => (
					<Text
						key={name}
						style={{ ...text, textAlign: 'center', margin: 3, paddingVertical: 4, paddingHorizontal: 8, borderWidth: 1, borderStyle: 'solid', borderRadius: 7, borderColor: colorOf(level) }}>
						{name}
					</Text>
				))}
			</View>
		</View>
	))
}

function Timeline({ visual, row, text, small, bold }: { visual: Of<'timeline'>; row: 'row' | 'row-reverse'; text: TextStyle; small: TextStyle; bold: TextStyle }) {
	return visual.events.map((event, i) => (
		<View key={i} wrap={false} style={{ flexDirection: row, marginTop: i ? 8 : 0 }}>
			<View style={{ width: 8, height: 8, borderRadius: 4, marginTop: 2, backgroundColor: colorOf(i) }} />
			<View style={{ flex: 1, marginHorizontal: 8 }}>
				<Text style={small}>{event.time}</Text>
				<Text style={bold}>{event.title}</Text>
				{event.detail.trim() !== '' && <Text style={text}>{event.detail}</Text>}
			</View>
		</View>
	))
}

/** Paper can't click: every node with where its choices lead. */
function Tree({ visual, text, small, bold, dir }: { visual: Of<'tree'>; text: TextStyle; small: TextStyle; bold: TextStyle; dir: Dir }) {
	const indent = dir === 'rtl' ? 'paddingRight' : 'paddingLeft'
	return Object.entries(visual.nodes).map(([id, node], i) => (
		<View key={id} wrap={false} style={{ marginTop: i ? 8 : 0 }}>
			<Text style={bold}>{node.title}</Text>
			{node.body.trim() !== '' && <Text style={small}>{node.body}</Text>}
			{node.choices.map(([label, target]) => (
				<Text key={target + label} style={{ ...text, [indent]: 10, marginTop: 2 }}>
					{`${label} – ${visual.nodes[target]?.title ?? target}`}
				</Text>
			))}
		</View>
	))
}

function Compare({ visual, text, small, bold, dir }: { visual: Of<'compare'>; text: TextStyle; small: TextStyle; bold: TextStyle; dir: Dir }) {
	const edge = dir === 'rtl' ? 'borderRightWidth' : 'borderLeftWidth'
	const edgeColor = dir === 'rtl' ? 'borderRightColor' : 'borderLeftColor'
	const pad = dir === 'rtl' ? 'paddingRight' : 'paddingLeft'
	return visual.rows.map((item, i) => (
		<View key={i} wrap={false} style={{ marginTop: i ? 8 : 0, [edge]: 3, [edgeColor]: colorOf(i), borderStyle: 'solid', [pad]: 8 }}>
			<Text style={{ ...bold, color: colorOf(i) }}>{item[0]}</Text>
			{item.slice(1).map((cell, c) =>
				cell.trim() ? (
					<View key={c} style={{ marginTop: 2 }}>
						<Text style={small}>{visual.header[c + 1]}</Text>
						<Text style={text}>{cell}</Text>
					</View>
				) : null,
			)}
		</View>
	))
}
