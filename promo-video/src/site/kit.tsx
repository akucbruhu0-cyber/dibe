// Shared kit for the website animations (site/SPEC.md §4). Light brand-book palette, not the dark promo one:
// Paper canvas, Mint surfaces, Ink text, Forest anchor; Live red only on the REC dot/clock, Spark only on the AI star,
// glow only behind an AI element while it works. Every periodic motion uses whole cycles per loop, so loops are seamless.
import React from "react";
import { AbsoluteFill, Easing, interpolate, interpolateColors, useCurrentFrame } from "remotion";
import { loadFont as loadPlex } from "@remotion/google-fonts/IBMPlexSansHebrew";
import { loadFont as loadMono } from "@remotion/google-fonts/IBMPlexMono";
import { fontFamily as rubik } from "../ui";
import { useDir } from "../i18n";

export { rubik };
export const plex = loadPlex("normal", { weights: ["400", "500", "600"], subsets: ["hebrew", "latin"] }).fontFamily;
export const mono = loadMono("normal", { weights: ["400", "500"], subsets: ["latin"] }).fontFamily;

/** Brand book tokens (light mode). */
export const B = {
  paper: "#F5FAF6",
  mint: "#CDEBD5",
  mintSoft: "#E6F5EA",
  ink: "#0F2418",
  forest: "#1E6A2E",
  muted: "#4D6656",
  line: "#CFE0D4",
  card: "#FFFFFF",
  spark: "#FFB574",
  live: "#EE4F45",
  glow: ["#FFC37E", "#FF9FC2", "#B8A4FF", "#86D2FF", "#93E6B0"],
};

const clamp = { extrapolateLeft: "clamp", extrapolateRight: "clamp" } as const;
/** Brand "enter": 320 ms, cubic-bezier(.2,.8,.2,1), 4 px rise, no bounce. */
export const enterEase = Easing.bezier(0.2, 0.8, 0.2, 1);
export const inOut = Easing.bezier(0.77, 0, 0.175, 1);
export const ENTER = 10; // frames ≈ 320 ms
export const SETTLE = 27; // frames ≈ 900 ms glow fade
const TAU = Math.PI * 2;

/** 0→1 over frames [a, b]. */
export const ramp = (f: number, a: number, b: number, easing = enterEase) => interpolate(f, [a, b], [0, 1], { ...clamp, easing });
/** Brand enter style starting at frame `at`. */
export const enter = (f: number, at: number): React.CSSProperties => {
  const p = ramp(f, at, at + ENTER);
  return { opacity: p, translate: `0 ${(1 - p) * 4}px` };
};
/** Pending (grey) → finished (ink). */
export const resolveColor = (p: number) => interpolateColors(p, [0, 1], ["rgba(77,102,86,0.55)", B.ink]);

export const Canvas: React.FC<{ children: React.ReactNode; transparent?: boolean }> = ({ children, transparent }) => {
  const dir = useDir();
  return <AbsoluteFill style={{ background: transparent ? undefined : B.paper, fontFamily: plex, color: B.ink, direction: dir }}>{children}</AbsoluteFill>;
};

/** Light app surface: radius 22 (cards) / 36 (hero), hairline border, depth from pastel only (no shadow). */
export const Surface: React.FC<{ style?: React.CSSProperties; radius?: number; children?: React.ReactNode }> = ({ style, radius = 22, children }) => (
  <div style={{ background: B.card, border: `2px solid ${B.line}`, borderRadius: radius, ...style }}>{children}</div>
);

/** REC dot: steady red dot + one ring growing ×2.6 and fading. Ring period = loop / cycles (≈1.4 s). */
export const RecDot: React.FC<{ size?: number; loop: number; on?: number }> = ({ size = 22, loop, on = 1 }) => {
  const f = useCurrentFrame();
  const cycles = Math.max(1, Math.round(loop / 42));
  const q = ((f * cycles) / loop) % 1;
  const ringP = Easing.bezier(0.23, 1, 0.32, 1)(q);
  return (
    <div style={{ position: "relative", width: size, height: size, flexShrink: 0, opacity: on }}>
      <div style={{ position: "absolute", inset: 0, borderRadius: "50%", background: B.live, scale: String(1 + 1.6 * ringP), opacity: 0.35 * (1 - ringP) }} />
      <div style={{ position: "absolute", inset: 0, borderRadius: "50%", background: B.live }} />
    </div>
  );
};

/** Live waveform. Whole sine cycles per loop → seamless. `active` 0 flattens it (recording stopped). */
export const LiveWave: React.FC<{ loop: number; width: number; height: number; bars?: number; active?: number; color?: string }> = ({
  loop,
  width,
  height,
  bars = 40,
  active = 1,
  color = B.forest,
}) => {
  const f = useCurrentFrame();
  const p = f / loop;
  const k1 = Math.max(1, Math.round(loop / 34));
  const k2 = Math.max(1, Math.round(loop / 80));
  const bw = Math.max(4, Math.round((width / bars) * 0.45));
  return (
    <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", width, height, direction: "ltr" }}>
      {Array.from({ length: bars }, (_, i) => {
        const env = 0.35 + 0.65 * Math.sin((Math.PI * (i + 0.5)) / bars);
        const a = (0.5 + 0.5 * Math.sin(TAU * k1 * p + i * 0.7)) * (0.55 + 0.45 * Math.sin(TAU * k2 * p + i * 0.23));
        const h = 6 + (height - 6) * env * a * active;
        return <div key={i} style={{ width: bw, height: h, borderRadius: bw, background: color, opacity: 0.45 + 0.5 * a * active }} />;
      })}
    </div>
  );
};

/** AI glow: five-colour ring blurred 16 px, rotating once per 11 s, breathing 4.2 s (0.55–0.9, ≤1.02), ~3 px proud of the element. */
export const Glow: React.FC<{ on: number; radius?: number; spread?: number; children: React.ReactNode; style?: React.CSSProperties }> = ({
  on,
  radius = 22,
  spread = 3,
  children,
  style,
}) => {
  const f = useCurrentFrame();
  const sec = f / 30;
  const breath = 0.5 - 0.5 * Math.cos((TAU * sec) / 4.2);
  const g = B.glow;
  return (
    <div style={{ position: "relative", ...style }}>
      {on > 0.001 && (
        <div
          style={{
            position: "absolute",
            inset: -spread,
            borderRadius: radius + spread,
            background: `conic-gradient(from ${(360 * sec) / 11}deg, ${g[0]}, ${g[1]}, ${g[2]}, ${g[3]}, ${g[4]}, ${g[0]})`,
            filter: "blur(16px)",
            opacity: on * (0.55 + 0.35 * breath),
            scale: String(1 + 0.02 * breath),
          }}
        />
      )}
      <div style={{ position: "relative" }}>{children}</div>
    </div>
  );
};

/** The 4-point AI star from the logo, always as the trio 1 / 0.5 / 0.3, always Spark. */
const STAR = "M0 -1 C0.12 -0.12 0.12 -0.12 1 0 C0.12 0.12 0.12 0.12 0 1 C-0.12 0.12 -0.12 0.12 -1 0 C-0.12 -0.12 -0.12 -0.12 0 -1 Z";
export const SparkTrio: React.FC<{ size?: number }> = ({ size = 32 }) => (
  <svg width={size} height={size} viewBox="-1 -1 2 2" style={{ flexShrink: 0 }}>
    <path d={STAR} fill={B.spark} transform="translate(-0.2 0.2) scale(0.62)" />
    <path d={STAR} fill={B.spark} transform="translate(0.52 -0.5) scale(0.31)" />
    <path d={STAR} fill={B.spark} transform="translate(0.62 0.42) scale(0.19)" />
  </svg>
);

/**
 * A rolling live transcript: `lines` repeat cyclically, `per` frames each. The newest line is pending (grey, words
 * entering one by one, newest word tinted), then resolves to ink and the stack moves up one row. With n lines in
 * one loop the visible window at the loop end equals the start → seamless.
 */
export const RollingTranscript: React.FC<{
  lines: string[];
  loop: number;
  offset?: number;
  rows?: number;
  rowH: number;
  fontSize: number;
  width: number;
  bars?: boolean; // abstract bars instead of text (language-neutral)
}> = ({ lines, loop, offset = 0, rows = 3, rowH, fontSize, width, bars }) => {
  const f = useCurrentFrame();
  const n = lines.length;
  const per = loop / n;
  const t = (((f + offset) % loop) + loop) % loop;
  const k = Math.floor(t / per);
  const local = t - k * per;
  const typeEnd = per * 0.62;
  const resolveAt = per * 0.66;
  const shiftAt = per * 0.8;
  const resolveP = ramp(local, resolveAt, resolveAt + 8);
  const shift = ramp(local, shiftAt, per - 1, inOut);
  const items = [];
  for (let r = rows - 1; r >= -1; r--) {
    // r = rows-1 is the pending row; r = -1 is the row leaving at the top.
    const idx = (((k - (rows - 1 - r)) % n) + n) % n;
    const y = (r - shift) * rowH;
    const pending = r === rows - 1;
    const op = r === -1 ? 0 : r === 0 ? 1 - shift : 1;
    items.push({ idx, y, pending, op, key: `${k}-${r}` });
  }
  return (
    <div style={{ position: "relative", width, height: rows * rowH, overflow: "hidden" }}>
      {items.map(({ idx, y, pending, op, key }) => (
        <div key={key} style={{ position: "absolute", top: y, insetInlineStart: 0, width, height: rowH, opacity: op }}>
          {bars ? (
            <BarLine seed={idx} pending={pending} local={local} typeEnd={typeEnd} resolveP={pending ? resolveP : 1} fontSize={fontSize} width={width} />
          ) : (
            <TextLine text={lines[idx]} pending={pending} local={local} typeEnd={typeEnd} resolveP={pending ? resolveP : 1} fontSize={fontSize} />
          )}
        </div>
      ))}
    </div>
  );
};

const TextLine: React.FC<{ text: string; pending: boolean; local: number; typeEnd: number; resolveP: number; fontSize: number }> = ({
  text,
  pending,
  local,
  typeEnd,
  resolveP,
  fontSize,
}) => {
  const words = text.split(" ");
  const step = typeEnd / words.length;
  const shown = pending ? Math.min(words.length, Math.floor(local / step) + 1) : words.length;
  return (
    <div style={{ fontSize, lineHeight: 1.45, color: resolveColor(resolveP), textAlign: "start" }}>
      {words.slice(0, shown).map((w, i) => {
        const at = i * step;
        const p = pending ? ramp(local, at, at + ENTER) : 1;
        const newest = pending && i === shown - 1 && resolveP < 1;
        return (
          <span key={i} style={{ display: "inline-block", opacity: p, translate: `0 ${(1 - p) * 4}px`, marginInlineEnd: "0.28em" }}>
            {newest ? <GlowText>{w}</GlowText> : w}
          </span>
        );
      })}
    </div>
  );
};

/** Newest live word: tinted with the glow gradient until the next word arrives. */
export const GlowText: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <span style={{ background: `linear-gradient(90deg, ${B.glow[2]}, ${B.glow[1]})`, WebkitBackgroundClip: "text", color: "transparent" }}>{children}</span>
);

const BAR_W = [0.92, 0.74, 0.86, 0.62, 0.8];
const BarLine: React.FC<{ seed: number; pending: boolean; local: number; typeEnd: number; resolveP: number; fontSize: number; width: number }> = ({
  seed,
  pending,
  local,
  typeEnd,
  resolveP,
  fontSize,
  width,
}) => {
  const segs = [0.34, 0.22, 0.28].map((s, i) => s * BAR_W[(seed + i) % BAR_W.length]);
  const total = segs.reduce((a, b) => a + b, 0);
  const color = interpolateColors(resolveP, [0, 1], [B.line, B.muted]);
  let acc = 0;
  return (
    <div style={{ display: "flex", gap: fontSize * 0.4, alignItems: "center", height: fontSize * 1.4 }}>
      {segs.map((s, i) => {
        const at = (acc / total) * typeEnd;
        acc += s;
        const p = pending ? ramp(local, at, at + ENTER) : 1;
        return <div key={i} style={{ width: width * s, height: fontSize * 0.5, borderRadius: fontSize, background: color, opacity: p, translate: `0 ${(1 - p) * 4}px` }} />;
      })}
    </div>
  );
};

/** mm:ss style clock in mono, tabular figures, isolated LTR. */
export const Clock: React.FC<{ sec: number; size: number; color?: string }> = ({ sec, size, color = B.live }) => {
  const s = Math.max(0, Math.floor(sec));
  const pad = (n: number) => String(n).padStart(2, "0");
  return (
    <span style={{ fontFamily: mono, fontSize: size, color, direction: "ltr", unicodeBidi: "isolate", fontVariantNumeric: "tabular-nums" }}>
      {pad(Math.floor(s / 3600))}:{pad(Math.floor(s / 60) % 60)}:{pad(s % 60)}
    </span>
  );
};

/** Button press: scale 0.98 for 120 ms (4 frames). */
export const press = (f: number, at: number) => (f >= at && f < at + 4 ? 0.98 : 1);

/** Pill button. Primary = Forest fill; secondary = outline. */
export const Pill: React.FC<{ primary?: boolean; size: number; children: React.ReactNode; style?: React.CSSProperties }> = ({ primary, size, children, style }) => (
  <div
    style={{
      display: "inline-flex",
      alignItems: "center",
      gap: size * 0.45,
      padding: `${size * 0.45}px ${size * 1.1}px`,
      borderRadius: 999,
      fontFamily: rubik,
      fontWeight: 500,
      fontSize: size,
      whiteSpace: "nowrap",
      background: primary ? B.forest : B.card,
      color: primary ? "#fff" : B.forest,
      border: `2px solid ${primary ? B.forest : B.line}`,
      ...style,
    }}
  >
    {children}
  </div>
);
