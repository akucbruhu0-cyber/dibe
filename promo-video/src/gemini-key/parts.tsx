// Building blocks for the "free Gemini key" tutorial: real screenshots with a zoom and a
// highlight ring, captions, and the browser / phone frames they sit in.
import React from "react";
import { Easing, Img, interpolate, spring, staticFile, useCurrentFrame, useVideoConfig } from "remotion";
import { loadFont } from "@remotion/google-fonts/NotoSansHebrew";

export const { fontFamily } = loadFont("normal", { weights: ["400", "500", "700", "800"], subsets: ["hebrew", "latin"] });
export const mono = "ui-monospace, Menlo, monospace";

/** The apps' own pastel tokens (desktop globals.css / Android Color.kt). */
export const K = {
  bg: "#EEF7F0",
  bg2: "#DCF4E3",
  text: "#16201A",
  muted: "#627067",
  card: "#FFFFFF",
  border: "#DCE8DF",
  primary: "#DCF4E3",
  onPrimary: "#1D5233",
  strong: "#26794A",
  pillDark: "#1C2721",
  ring: "#3CB54A",
  studio: "#1A1A1A",
};

const clamp = { extrapolateLeft: "clamp", extrapolateRight: "clamp" } as const;
export const ease = Easing.bezier(0.23, 1, 0.32, 1);

/** 0→1 between frames a and b. */
export const ramp = (f: number, a: number, b: number) => interpolate(f, [a, b], [0, 1], { ...clamp, easing: ease });

export const useIn = (delay = 0) => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  return spring({ frame: f - delay, fps, config: { damping: 28, stiffness: 200 } });
};

export type Rect = { x: number; y: number; w: number; h: number };
export type ShotSrc = { file: string; w: number; h: number; bg: string };

export const SHOTS = {
  keys: { file: "gemini-key/shots/02-api-keys.jpg", w: 1512, h: 800, bg: K.studio },
  dialog: { file: "gemini-key/shots/03-create-key-dialog.jpg", w: 1512, h: 800, bg: K.studio },
  platform: { file: "gemini-key/shots/04-desktop-platform.jpg", w: 1200, h: 635, bg: K.bg },
  pasted: { file: "gemini-key/shots/06-desktop-key-pasted.jpg", w: 1200, h: 635, bg: K.bg },
  tested: { file: "gemini-key/shots/08-desktop-test-ok.jpg", w: 1200, h: 635, bg: K.bg },
} satisfies Record<string, ShotSrc>;

/**
 * A screenshot in a fixed box, zoomed by [zoom] toward [focus] (image pixels) and kept inside its
 * edges. [rings] are image-pixel rects that get a pulsing highlight once their [at] frame is reached.
 */
export const Shot: React.FC<{
  src: ShotSrc;
  box: { w: number; h: number };
  focus: { x: number; y: number };
  zoom: number;
  rings?: { rect: Rect; at: number }[];
  children?: (map: (r: Rect) => Rect) => React.ReactNode;
}> = ({ src, box, focus, zoom, rings = [], children }) => {
  const f = useCurrentFrame();
  const s = (box.w / src.w) * zoom;
  const place = (size: number, view: number, at: number) => {
    const scaled = size * s;
    if (scaled <= view) return (view - scaled) / 2;
    return Math.min(0, Math.max(view - scaled, view / 2 - at * s));
  };
  const tx = place(src.w, box.w, focus.x);
  const ty = place(src.h, box.h, focus.y);
  const map = (r: Rect): Rect => ({ x: tx + r.x * s, y: ty + r.y * s, w: r.w * s, h: r.h * s });
  return (
    <div style={{ position: "relative", width: box.w, height: box.h, overflow: "hidden", background: src.bg }}>
      <Img src={staticFile(src.file)} style={{ position: "absolute", left: tx, top: ty, width: src.w * s, height: src.h * s }} />
      {rings.map(({ rect, at }, i) => f >= at && <Ring key={i} rect={map(rect)} since={f - at} />)}
      {children?.(map)}
    </div>
  );
};

/** A green rounded outline that pops in, then breathes. */
export const Ring: React.FC<{ rect: Rect; since: number; radius?: number }> = ({ rect, since, radius = 14 }) => {
  const pop = interpolate(since, [0, 10], [1.35, 1], { ...clamp, easing: ease });
  const alpha = interpolate(since, [0, 6], [0, 1], clamp);
  const breathe = 1 + 0.035 * Math.sin(since / 6);
  const pad = 8;
  return (
    <div
      style={{
        position: "absolute",
        left: rect.x - pad,
        top: rect.y - pad,
        width: rect.w + pad * 2,
        height: rect.h + pad * 2,
        borderRadius: radius,
        border: `5px solid ${K.ring}`,
        boxShadow: `0 0 0 8px rgba(60,181,74,0.22), 0 0 40px rgba(60,181,74,0.55)`,
        transform: `scale(${pop * breathe})`,
        opacity: alpha,
      }}
    />
  );
};

/** Step pill, title and one line of detail, right-aligned (RTL). */
export const Caption: React.FC<{ step?: string; title: string; sub?: string; size: number; width: number }> = ({ step, title, sub, size, width }) => {
  const a = useIn(0);
  const b = useIn(5);
  return (
    <div style={{ width, direction: "rtl", fontFamily, color: K.text }}>
      {step && (
        <div
          style={{
            display: "inline-block",
            padding: `${size * 0.12}px ${size * 0.38}px`,
            borderRadius: 999,
            background: K.pillDark,
            color: "#fff",
            fontSize: size * 0.36,
            fontWeight: 700,
            opacity: a,
            transform: `translateY(${(1 - a) * 20}px)`,
          }}>
          {step}
        </div>
      )}
      <div style={{ fontSize: size, fontWeight: 800, lineHeight: 1.15, marginTop: size * 0.25, opacity: a, transform: `translateY(${(1 - a) * 24}px)` }}>{title}</div>
      {sub && (
        <div style={{ fontSize: size * 0.48, fontWeight: 500, color: K.muted, lineHeight: 1.4, marginTop: size * 0.2, opacity: b, transform: `translateY(${(1 - b) * 24}px)` }}>
          {sub}
        </div>
      )}
    </div>
  );
};

/** A desktop browser window: traffic lights and an address bar that can type its URL. */
export const BrowserFrame: React.FC<{ url: string; typeFrom?: number; w: number; children: React.ReactNode }> = ({ url, typeFrom, w, children }) => {
  const f = useCurrentFrame();
  const shown = typeFrom === undefined ? url : url.slice(0, Math.max(0, Math.floor((f - typeFrom) / 1.6)));
  return (
    <div style={{ width: w, borderRadius: 18, overflow: "hidden", background: "#2a2a2a", boxShadow: "0 30px 80px rgba(22,32,26,0.28)" }}>
      <div style={{ height: 52, display: "flex", alignItems: "center", gap: 10, padding: "0 18px", direction: "ltr" }}>
        {["#ff5f57", "#febc2e", "#28c840"].map((c) => (
          <div key={c} style={{ width: 14, height: 14, borderRadius: 7, background: c }} />
        ))}
        <div style={{ marginLeft: 24, flex: 1, height: 32, borderRadius: 16, background: "#3a3a3a", color: "#e8e8e8", fontFamily: mono, fontSize: 18, display: "flex", alignItems: "center", padding: "0 16px" }}>
          🔒&nbsp;&nbsp;{shown}
          {typeFrom !== undefined && shown.length < url.length && <span style={{ opacity: f % 20 < 10 ? 1 : 0 }}>|</span>}
        </div>
      </div>
      {children}
    </div>
  );
};

/** An app window without a URL: the DIBE desktop app. */
export const AppFrame: React.FC<{ w: number; children: React.ReactNode }> = ({ w, children }) => (
  <div style={{ width: w, borderRadius: 18, overflow: "hidden", boxShadow: "0 30px 80px rgba(22,32,26,0.28)", border: `1px solid ${K.border}` }}>{children}</div>
);

/** The top of a phone, rising from the bottom edge; [url] adds a mobile browser bar. */
export const PhoneFrame: React.FC<{ w: number; h: number; url?: string; dark?: boolean; children: React.ReactNode }> = ({ w, h, url, dark, children }) => (
  <div style={{ width: w, height: h, borderRadius: "84px 84px 0 0", background: "#0d0f0e", padding: "22px 22px 0", boxShadow: "0 30px 90px rgba(22,32,26,0.30)" }}>
    <div style={{ width: "100%", height: "100%", borderRadius: "64px 64px 0 0", overflow: "hidden", background: dark ? K.studio : K.bg, position: "relative" }}>
      <div style={{ height: 70, display: "flex", alignItems: "center", justifyContent: "space-between", padding: "0 56px", fontFamily, fontSize: 30, fontWeight: 700, color: dark || url ? "#eee" : K.text, background: url ? "#202124" : "transparent", direction: "ltr" }}>
        <span>9:41</span>
        <span>●●● ▮</span>
      </div>
      {url && (
        <div style={{ height: 96, background: "#202124", display: "flex", alignItems: "center", padding: "0 28px" }}>
          <div style={{ flex: 1, height: 64, borderRadius: 32, background: "#303134", color: "#e8eaed", fontFamily: mono, fontSize: 28, display: "flex", alignItems: "center", padding: "0 28px", direction: "ltr" }}>
            🔒&nbsp;&nbsp;{url}
          </div>
        </div>
      )}
      {children}
    </div>
  </div>
);

/** A small dark chip, e.g. "הועתק". */
export const Toast: React.FC<{ text: string; at: number; style?: React.CSSProperties; size?: number }> = ({ text, at, style, size = 28 }) => {
  const f = useCurrentFrame();
  const a = ramp(f, at, at + 8);
  if (f < at) return null;
  return (
    <div
      style={{
        position: "absolute",
        padding: `${size * 0.45}px ${size * 0.9}px`,
        borderRadius: 999,
        background: K.pillDark,
        color: "#fff",
        fontFamily,
        fontWeight: 700,
        fontSize: size,
        direction: "rtl",
        opacity: a,
        transform: `translateY(${(1 - a) * 16}px)`,
        boxShadow: "0 12px 30px rgba(0,0,0,0.3)",
        ...style,
      }}>
      {text}
    </div>
  );
};

/** Cross-fades between children by frame: [[fromFrame, node], ...]. */
export const Swap: React.FC<{ at: [number, React.ReactNode][] }> = ({ at }) => {
  const f = useCurrentFrame();
  return (
    <div style={{ position: "relative" }}>
      {at.map(([from, node], i) => {
        const next = at[i + 1]?.[0];
        const gone = next === undefined ? 0 : ramp(f, next, next + 8);
        const o = i === 0 ? 1 - gone : Math.min(ramp(f, from, from + 8), 1 - gone);
        if (o <= 0 && i > 0) return null;
        return (
          <div key={i} style={{ position: i === 0 ? "relative" : "absolute", inset: 0, opacity: o }}>
            {node}
          </div>
        );
      })}
    </div>
  );
};
