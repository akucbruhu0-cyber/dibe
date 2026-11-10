import React from "react";
import { AbsoluteFill, Easing, interpolate, spring, useCurrentFrame, useVideoConfig } from "remotion";
import { loadFont } from "@remotion/google-fonts/Rubik";
import { C, COL_W, COL_X, FPS, SPRING, SPRING_BOUNCY, T, W } from "./theme";
import { useDir, useLang } from "./i18n";
import { CAPTIONS } from "./timing";

export const { fontFamily } = loadFont("normal", {
  weights: ["400", "500", "700", "800"],
  subsets: ["hebrew", "latin"],
});

const clamp = { extrapolateLeft: "clamp", extrapolateRight: "clamp" } as const;
export const ease = Easing.bezier(0.16, 1, 0.3, 1);

/** 0→1 spring from `delaySec` (critically damped by default; `bouncy` only for thrown objects). */
export const useSpr = (delaySec = 0, bouncy = false) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  return spring({ frame: frame - T(delaySec), fps, config: bouncy ? SPRING_BOUNCY : SPRING });
};

/** 0→1 eased window between two moments (seconds, scene-relative). */
export const win = (frame: number, a: number, b: number) =>
  interpolate(frame, [T(a), T(b)], [0, 1], { ...clamp, easing: ease });

/** 1 while inside [a, b] seconds, with short fades at both ends. */
export const between = (frame: number, a: number, b: number, fade = 0.3) =>
  interpolate(frame, [T(a), T(a + fade), T(b - fade), T(b)], [0, 1, 1, 0], clamp);

export const typed = (text: string, frame: number, startSec: number, cps = 30) =>
  text.slice(0, Math.max(0, Math.floor(((frame - T(startSec)) / FPS) * cps)));

/** Display type is tightened as it grows (Apple optical sizing); Hebrew only gets a hint of it. */
export const useTrack = (scale = 1) => (useLang() === "he" ? -0.01 * scale : -0.03 * scale) + "em";

/** Scene shell: soft cross-fade with a tiny scale/blur so cuts feel like camera movement, plus a slow push-in. */
export const SceneFrame: React.FC<{ dur: number; children: React.ReactNode }> = ({ dur, children }) => {
  const frame = useCurrentFrame();
  const dir = useDir();
  const enter = interpolate(frame, [0, 12], [0, 1], { ...clamp, easing: ease });
  const exit = interpolate(frame, [dur - 10, dur], [0, 1], clamp);
  const scale = interpolate(enter, [0, 1], [1.035, 1]) * interpolate(exit, [0, 1], [1, 0.975]) * interpolate(frame, [0, dur], [1, 1.02]);
  return (
    <AbsoluteFill
      style={{
        opacity: enter * (1 - exit),
        scale: String(scale),
        fontFamily,
        direction: dir,
        filter: exit > 0 ? `blur(${exit * 5}px)` : undefined,
      }}
    >
      {children}
    </AbsoluteFill>
  );
};

export const Background: React.FC = () => {
  const frame = useCurrentFrame();
  return (
    <AbsoluteFill style={{ background: `radial-gradient(1400px 950px at 50% 40%, ${C.bg2}, ${C.bg} 72%)` }}>
      <div
        style={{
          position: "absolute",
          width: 1000,
          height: 1000,
          borderRadius: "50%",
          left: -260 + Math.sin(frame / 120) * 90,
          top: -440 + Math.cos(frame / 150) * 40,
          background: `radial-gradient(circle, ${C.green}2e, transparent 65%)`,
        }}
      />
      <div
        style={{
          position: "absolute",
          width: 900,
          height: 900,
          borderRadius: "50%",
          right: -300 + Math.cos(frame / 130) * 70,
          bottom: -440 + Math.sin(frame / 110) * 40,
          background: `radial-gradient(circle, ${C.orange}1f, transparent 65%)`,
        }}
      />
      <AbsoluteFill
        style={{
          backgroundImage: "radial-gradient(rgba(255,255,255,0.05) 1.5px, transparent 1.5px)",
          backgroundSize: "56px 56px",
        }}
      />
    </AbsoluteFill>
  );
};

/** Decorative audio bars at both sides — pure ambience, cropped away in vertical. */
export const SideBars: React.FC<{ energy?: number }> = ({ energy = 1 }) => {
  const frame = useCurrentFrame();
  const bars = 18;
  const group = (side: "l" | "r") =>
    Array.from({ length: bars }, (_, i) => {
      const h = 28 + (Math.sin(frame / 6 + i * 0.75 + (side === "l" ? 0 : 2.2)) * 0.5 + 0.5) * 230 * energy * (1 - (i / bars) * 0.55);
      const idx = side === "l" ? i : bars - 1 - i;
      return (
        <div
          key={i}
          style={{
            width: 12,
            height: h,
            borderRadius: 6,
            background: `linear-gradient(${C.green}, ${C.orange})`,
            opacity: 0.1 + (idx / bars) * 0.26,
          }}
        />
      );
    });
  const wrap: React.CSSProperties = { position: "absolute", top: 0, bottom: 0, width: 440, display: "flex", alignItems: "center", justifyContent: "space-between" };
  return (
    <>
      <div style={{ ...wrap, left: 50 }}>{group("l")}</div>
      <div style={{ ...wrap, right: 50 }}>{group("r")}</div>
    </>
  );
};

/** Title block: small eyebrow over a large, tightly-tracked headline. */
export const Headline: React.FC<{
  eyebrow?: string;
  children: React.ReactNode;
  top?: number;
  size?: number;
  opacity?: number;
  delay?: number;
}> = ({ eyebrow, children, top = 56, size = 84, opacity = 1, delay = 0.1 }) => {
  const p = useSpr(delay);
  const track = useTrack();
  return (
    <div
      style={{
        position: "absolute",
        top,
        left: COL_X - 60,
        width: COL_W + 120,
        textAlign: "center",
        opacity: p * opacity,
        translate: `0 ${(1 - p) * 26}px`,
      }}
    >
      {eyebrow && <div style={{ color: C.orange, fontSize: 30, fontWeight: 500, marginBottom: 4 }}>{eyebrow}</div>}
      <div style={{ color: C.text, fontSize: size, fontWeight: 800, lineHeight: 1.05, letterSpacing: track }}>{children}</div>
    </div>
  );
};

/** Dark frosted material for overlays (Apple "glass": blur + hairline border + top highlight). */
export const Glass: React.FC<{ children: React.ReactNode; style?: React.CSSProperties; radius?: number }> = ({ children, style, radius = 28 }) => (
  <div
    style={{
      background: "linear-gradient(180deg, rgba(255,255,255,0.12), rgba(255,255,255,0.05))",
      border: `1px solid ${C.glassBorder}`,
      borderRadius: radius,
      backdropFilter: "blur(28px) saturate(170%)",
      boxShadow: "0 24px 60px rgba(0,0,0,0.45), inset 0 1px 0 rgba(255,255,255,0.18)",
      color: C.text,
      ...style,
    }}
  >
    {children}
  </div>
);

/** App-style light window (used for the animated mock UIs). */
export const AppCard: React.FC<{ width?: number; title?: string; children: React.ReactNode; style?: React.CSSProperties; dark?: boolean }> = ({
  width = COL_W - 40,
  title,
  children,
  style,
  dark,
}) => {
  const dir = useDir();
  return (
    <div
      style={{
        width,
        borderRadius: 30,
        background: dark ? "#0b1410" : C.card,
        color: dark ? C.text : C.cardText,
        boxShadow: "0 50px 120px rgba(0,0,0,0.6), 0 0 0 1px rgba(255,255,255,0.1)",
        overflow: "hidden",
        direction: dir,
        ...style,
      }}
    >
      <div
        style={{
          height: 54,
          display: "flex",
          alignItems: "center",
          gap: 9,
          padding: "0 20px",
          background: dark ? "#13201a" : C.cardSoft,
          borderBottom: `1px solid ${dark ? "#1f2f26" : C.border}`,
          direction: "ltr",
        }}
      >
        {["#ff5f57", "#febc2e", "#28c840"].map((c) => (
          <div key={c} style={{ width: 14, height: 14, borderRadius: 7, background: c }} />
        ))}
        {title && (
          <div style={{ flex: 1, textAlign: "center", fontSize: 22, fontWeight: 500, color: dark ? C.muted : C.cardMuted, marginRight: 50, direction: dir }}>{title}</div>
        )}
      </div>
      <div style={{ padding: 28 }}>{children}</div>
    </div>
  );
};

export type IconName = "file" | "mic" | "link" | "sparkle" | "check" | "key" | "terminal" | "lock" | "wifi-off" | "bolt" | "clip" | "pdf";

const PATHS: Record<IconName, React.ReactNode> = {
  file: (<><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z" /><path d="M14 3v5h5" /></>),
  pdf: (<><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z" /><path d="M14 3v5h5M8 14h5M8 17h8" /></>),
  mic: (<><rect x="9" y="3" width="6" height="12" rx="3" /><path d="M5 11a7 7 0 0 0 14 0M12 18v3" /></>),
  link: (<><path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1" /><path d="M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1" /></>),
  sparkle: <path d="M12 3l2 6 6 2-6 2-2 6-2-6-6-2 6-2z" />,
  check: <path d="M5 12.5l4.5 4.5L19 7.5" />,
  key: (<><circle cx="8" cy="15" r="4" /><path d="M11 12l9-9M16 7l3 3" /></>),
  terminal: (<><path d="M4 6l6 6-6 6" /><path d="M13 19h7" /></>),
  lock: (<><rect x="5" y="11" width="14" height="10" rx="2" /><path d="M8 11V8a4 4 0 0 1 8 0v3" /></>),
  "wifi-off": (<><path d="M2 2l20 20" /><path d="M5 12a10 10 0 0 1 4-2.5M12 19h.01M8.5 15.5a5 5 0 0 1 6 0M16 9.2A10 10 0 0 1 19 12" /></>),
  bolt: <path d="M13 2L4 14h7l-1 8 9-12h-7z" />,
  clip: <path d="M21 11l-8.5 8.5a5 5 0 0 1-7-7L14 4a3.5 3.5 0 0 1 5 5l-8.5 8.5a2 2 0 0 1-3-3L15 7" />,
};

export const Icon: React.FC<{ name: IconName; size?: number; color?: string }> = ({ name, size = 32, color = "currentColor" }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
    {PATHS[name]}
  </svg>
);

export const Chip: React.FC<{
  children: React.ReactNode;
  delay?: number;
  size?: number;
  tone?: "dark" | "green" | "orange" | "light";
  icon?: IconName;
  active?: boolean;
}> = ({ children, delay = 0, size = 36, tone = "dark", icon, active }) => {
  const p = useSpr(delay, true);
  const tones = {
    dark: { bg: "rgba(255,255,255,0.09)", fg: C.text, bd: "rgba(255,255,255,0.18)" },
    green: { bg: `${C.green}30`, fg: "#c4f5ca", bd: `${C.green}88` },
    orange: { bg: `${C.orange}2e`, fg: C.orangeSoft, bd: `${C.orange}99` },
    light: { bg: C.cardSoft, fg: C.cardText, bd: C.border },
  }[tone];
  return (
    <div
      style={{
        display: "inline-flex",
        alignItems: "center",
        gap: 12,
        padding: "11px 24px",
        borderRadius: 999,
        fontSize: size,
        fontWeight: 700,
        background: tones.bg,
        color: tones.fg,
        border: `2px solid ${active ? C.orange : tones.bd}`,
        backdropFilter: "blur(16px)",
        scale: String(0.7 + 0.3 * p),
        opacity: Math.min(1, p * 1.6),
        whiteSpace: "nowrap",
      }}
    >
      {icon && <Icon name={icon} size={size * 0.85} />}
      {children}
    </div>
  );
};

export const Wave: React.FC<{ active?: number; width?: number; height?: number; color?: string }> = ({ active = 1, width = 640, height = 90, color = C.green }) => {
  const frame = useCurrentFrame();
  const n = 46;
  return (
    <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", width, height, direction: "ltr" }}>
      {Array.from({ length: n }, (_, i) => {
        const a = (Math.sin(frame / 4 + i * 0.6) * 0.5 + 0.5) * (Math.sin(frame / 11 + i * 0.21) * 0.5 + 0.55);
        return <div key={i} style={{ width: 7, height: 8 + a * (height - 8) * active, borderRadius: 4, background: color, opacity: 0.55 + a * 0.45 }} />;
      })}
    </div>
  );
};

/** Burned-in captions for sound-off viewing; follows the narration clock, not the scene clock. */
export const Captions: React.FC = () => {
  const frame = useCurrentFrame();
  const lang = useLang();
  const sec = frame / FPS;
  const list = CAPTIONS[lang];
  const idx = list.findIndex(([a, b]) => sec >= a - 0.05 && sec < b + 0.15);
  if (idx < 0) return null;
  const [a, b, text] = list[idx];
  const inP = interpolate(sec, [a - 0.05, a + 0.2], [0, 1], { ...clamp, easing: ease });
  const outP = interpolate(sec, [b - 0.1, b + 0.15], [1, 0], clamp);
  return (
    <div style={{ position: "absolute", bottom: 46, left: 0, width: W, display: "flex", justifyContent: "center", direction: lang === "he" ? "rtl" : "ltr" }}>
      <div
        style={{
          maxWidth: 760,
          padding: "10px 30px",
          borderRadius: 22,
          background: "rgba(8,16,12,0.62)",
          border: `1px solid ${C.glassBorder}`,
          backdropFilter: "blur(20px)",
          color: C.text,
          fontFamily,
          fontSize: 38,
          fontWeight: 700,
          textAlign: "center",
          lineHeight: 1.25,
          opacity: inP * outP,
          translate: `0 ${(1 - inP) * 14}px`,
        }}
      >
        {text}
      </div>
    </div>
  );
};

/** Optional guides for checking the vertical crop (9:16 = pink, 1:1 = blue). */
export const Guides: React.FC = () => (
  <AbsoluteFill style={{ pointerEvents: "none" }}>
    <div style={{ position: "absolute", left: (W - 608) / 2, width: 608, top: 0, bottom: 0, border: "3px dashed #ff3b6b", background: "rgba(255,59,107,0.04)" }} />
    <div style={{ position: "absolute", left: (W - 1080) / 2, width: 1080, top: 0, bottom: 0, border: "3px dashed #4cc9f0" }} />
  </AbsoluteFill>
);
