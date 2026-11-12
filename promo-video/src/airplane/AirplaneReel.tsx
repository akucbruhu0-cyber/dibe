// DIBE reel: the creator's own on-camera hook → real screen recording of live, offline Hebrew transcription
// (airplane icon visible in the status bar) → AI summary → end card. Noto Sans Hebrew + the app's greens.
// ffmpeg pre-cuts the footage in public/airplane (see scratchpad build_edit.py) and writes src/airplane/edit.json;
// this layer adds karaoke captions, badges, scene transitions and the audio mix.
// Layout rule: badges in the top band, captions in the lower band (clear of Reels/TikTok UI), nothing else covers the app.
import React from "react";
import { AbsoluteFill, Img, OffthreadVideo, Sequence, interpolate, spring, staticFile, useCurrentFrame } from "remotion";
import { Audio } from "@remotion/media";
import { loadFont } from "@remotion/google-fonts/NotoSansHebrew";
import { C } from "../theme";
import edit from "./edit.json";

const { fontFamily } = loadFont("normal", { weights: ["700", "800", "900"], subsets: ["hebrew", "latin"] });

export const AIR_FPS = 30;
export const AIR_W = 1080;
export const AIR_H = 1920;
export const AIR_DUR = Math.ceil(edit.total * AIR_FPS);

const F = (s: number) => Math.round(s * AIR_FPS);
const clamp = { extrapolateLeft: "clamp", extrapolateRight: "clamp" } as const;
const useSec = () => useCurrentFrame() / AIR_FPS;
const pop = (frame: number, at: number, damping = 13) => spring({ frame: frame - F(at), fps: AIR_FPS, config: { damping, stiffness: 200 } });
const win = (t: number, s: number, e: number, f = 0.18) => interpolate(t, [s, s + f, e - f, e], [0, 1, 1, 0], clamp);
const stroke = (px: number): React.CSSProperties => ({ WebkitTextStroke: `${px}px #000`, paintOrder: "stroke fill", textShadow: "0 6px 18px rgba(0,0,0,0.5)" });

// ---------- captions: dark-green pill, every word dim until spoken, then it pops (keywords in the logo's orange) ----------
const Word: React.FC<{ w: (typeof edit.chunks)[number]["words"][number]; t: number; size: number }> = ({ w, t, size }) => {
  const frame = useCurrentFrame();
  const k = pop(frame, w.s - 0.03, 14);
  const active = t >= w.s && t < w.e + 0.12;
  return (
    <span style={{ display: "inline-block", fontSize: size, fontWeight: 900, color: w.hi ? C.orangeSoft : "#fff", opacity: 0.28 + 0.72 * Math.min(1, k),
      translate: `0px ${(1 - Math.min(1, k)) * 16}px`, scale: active ? 1.06 : 1 }}>
      {w.t}
    </span>
  );
};

const Captions: React.FC = () => {
  const frame = useCurrentFrame();
  const t = frame / AIR_FPS;
  const c = edit.chunks.find((x) => t >= x.s && t < x.e);
  if (!c || t >= edit.endStart) return null;
  const k = pop(frame, c.s, 16);
  const size = Math.max(62, Math.min(92, 92 - Math.max(0, c.t.length - 13) * 2.8));
  return (
    <div style={{ position: "absolute", left: 30, right: 30, top: 1290, height: 200, display: "flex", alignItems: "center", justifyContent: "center" }}>
      <div style={{ scale: 0.86 + 0.14 * Math.min(1, k), opacity: Math.min(1, k * 1.5), background: "rgba(20,90,30,0.95)", borderRadius: 60, padding: "12px 46px 16px",
        display: "flex", gap: size * 0.36, direction: "rtl", whiteSpace: "nowrap", boxShadow: "0 18px 46px rgba(0,0,0,0.38)", border: "3px solid rgba(155,227,164,0.35)" }}>
        {c.words.map((w, i) => <Word key={i} w={w} t={t} size={size} />)}
      </div>
    </div>
  );
};

// ---------- badges (top band, under the real status bar) ----------
const Chip: React.FC<{ s: number; e: number; x: number; w: number; bg: string; color?: string; children: React.ReactNode }> = ({ s, e, x, w, bg, color = "#fff", children }) => {
  const frame = useCurrentFrame();
  const o = win(frame / AIR_FPS, s, e);
  if (o === 0) return null;
  const k = Math.min(1, pop(frame, s));
  return (
    <div style={{ position: "absolute", top: 138, left: x, width: w, height: 84, display: "flex", alignItems: "center", justifyContent: "center", opacity: o, scale: 0.7 + 0.3 * k,
      background: bg, color, borderRadius: 999, fontSize: 40, fontWeight: 800, boxShadow: "0 10px 28px rgba(0,0,0,0.3)", whiteSpace: "nowrap" }}>
      {children}
    </div>
  );
};

// pulses around the airplane icon of the real status bar (footage px: 960x2142 → crop/scale 1.125)
const Ring: React.FC<{ s: number }> = ({ s }) => {
  const t = useSec();
  const p = (t - s) / 1.6;
  if (p < 0 || p > 1) return null;
  const r = interpolate(p, [0, 1], [26, 74], clamp);
  return <div style={{ position: "absolute", left: 864 - r, top: 84 - r, width: 2 * r, height: 2 * r, borderRadius: "50%", border: `5px solid ${C.orangeSoft}`, opacity: 1 - p }} />;
};

// ---------- scenes ----------
const FaceScene: React.FC = () => {
  const frame = useCurrentFrame();
  return <OffthreadVideo src={staticFile("airplane/face.mp4")} muted style={{ width: AIR_W, height: AIR_H, objectFit: "cover", scale: interpolate(frame, [0, F(edit.faceEnd + 0.2)], [1, 1.06], clamp) }} />;
};

const ScreenScene: React.FC = () => (
  <AbsoluteFill style={{ background: "#fff" }}>
    <OffthreadVideo src={staticFile("airplane/screen.mp4")} muted style={{ width: AIR_W, height: AIR_H }} />
  </AbsoluteFill>
);

const SummaryScene: React.FC = () => {
  const frame = useCurrentFrame();
  const slide = spring({ frame, fps: AIR_FPS, config: { damping: 18, stiffness: 140 } });
  return (
    <AbsoluteFill style={{ background: "#fff", opacity: interpolate(frame, [0, 6], [0, 1], clamp) }}>
      <Img src={staticFile("airplane/summary.png")} style={{ width: AIR_W, height: AIR_H, translate: `0px ${(1 - slide) * 90}px`, scale: 1 + 0.025 * (frame / 90) }} />
    </AbsoluteFill>
  );
};

const EndLine: React.FC<{ s: number; text: string; last: boolean }> = ({ s, text, last }) => {
  const k = pop(useCurrentFrame(), s, 12);
  return (
    <div style={{ ...(last ? {} : stroke(10)), scale: k, margin: "10px 0", color: last ? "#fff" : C.greenSoft, fontSize: last ? 112 : 136, fontWeight: 900, textAlign: "center",
      ...(last ? { background: C.green, padding: "14px 58px 18px", borderRadius: 999, boxShadow: "0 16px 40px rgba(60,181,74,0.45)" } : {}) }}>
      {last ? "⬇️ " : ""}{text}
    </div>
  );
};

const EndCard: React.FC = () => {
  const frame = useCurrentFrame();
  const t = frame / AIR_FPS;
  const lines = edit.chunks.filter((c) => c.s >= edit.endStart - 0.01);
  const logo = pop(frame, edit.endStart + 0.05, 14);
  return (
    <AbsoluteFill style={{ background: `linear-gradient(160deg, ${C.bg}, ${C.bg2})`, opacity: interpolate(t, [edit.endStart - 0.1, edit.endStart + 0.2], [0, 1], clamp) }}>
      <div style={{ position: "absolute", left: 40, right: 40, top: 360, display: "flex", flexDirection: "column", alignItems: "center" }}>
        <Img src={staticFile("logo.png")} style={{ width: 240, height: 240, borderRadius: 54, scale: logo, marginBottom: 44, boxShadow: "0 20px 60px rgba(60,181,74,0.45)" }} />
        {lines.map((c, i) => <EndLine key={i} s={c.s} text={c.t.replace(",", "")} last={i === lines.length - 1} />)}
      </div>
    </AbsoluteFill>
  );
};

export const AirplaneReel: React.FC = () => {
  const frame = useCurrentFrame();
  const t = frame / AIR_FPS;
  return (
    <AbsoluteFill style={{ fontFamily, direction: "rtl", background: C.bg }}>
      <Sequence from={0} durationInFrames={F(edit.faceEnd + 0.17)}><FaceScene /></Sequence>
      <Sequence from={F(edit.faceEnd)} durationInFrames={F(edit.summaryStart - edit.faceEnd) + 6}>
        <AbsoluteFill style={{ opacity: interpolate(t, [edit.faceEnd, edit.faceEnd + 0.17], [0, 1], clamp), scale: interpolate(t, [edit.faceEnd, edit.faceEnd + 0.3], [1.07, 1], clamp) }}><ScreenScene /></AbsoluteFill>
      </Sequence>
      <Sequence from={F(edit.summaryStart)}><SummaryScene /></Sequence>
      {/* everything below uses the GLOBAL frame (no Sequence): inside a Sequence useCurrentFrame() is local */}
      <Ring s={edit.faceEnd + 0.4} />
      <Chip s={edit.faceEnd + 0.15} e={edit.summaryStart} x={470} w={570} bg={C.greenDark}>✈️ מצב טיסה · בלי אינטרנט</Chip>
      <Chip s={edit.speedFrom} e={edit.speedTo} x={40} w={400} bg={C.orange} color={C.cardText}>⏩ מואץ ×{Math.round(edit.speed)}</Chip>
      <Chip s={edit.summaryStart + 0.2} e={edit.endStart} x={170} w={740} bg={C.greenDark}>✨ סיכום AI · הסיכום צריך אינטרנט</Chip>
      <Captions />
      {t >= edit.endStart - 0.1 && <EndCard />}
      <Audio src={staticFile("airplane/voice.wav")} />
      <Audio src={staticFile("airplane/music.wav")} volume={(f) => interpolate(f, [0, 15, AIR_DUR - 36, AIR_DUR], [0, 0.11, 0.11, 0], clamp)} />
    </AbsoluteFill>
  );
};
