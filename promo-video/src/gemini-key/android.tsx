// The Android app's AI card (`AiSettingsFields` in AiSettingsSection.kt), rebuilt from the Compose
// code and its Hebrew strings: no device was available to screenshot. 1dp ≈ D px on the phone frame.
import React from "react";
import { interpolate, useCurrentFrame } from "remotion";
import { K, ease, fontFamily, mono, ramp } from "./parts";

const D = 2.3;
const dp = (n: number) => n * D;
const clamp = { extrapolateLeft: "clamp", extrapolateRight: "clamp" } as const;

/** Wraps a child and rings it from frame [at] on, sized to the child. */
type Span = [from: number, to: number];

const Fill: React.FC<{ on?: Span; radius?: number; children: React.ReactNode; style?: React.CSSProperties }> = ({ on, radius = dp(10), children, style }) => {
  const f = useCurrentFrame();
  const since = on && f >= on[0] && f < on[1] ? f - on[0] : -1;
  return (
    <div style={{ position: "relative", ...style }}>
      {children}
      {since >= 0 && (
        <div
          style={{
            position: "absolute",
            inset: -10,
            borderRadius: radius + 8,
            border: `5px solid ${K.ring}`,
            boxShadow: "0 0 0 8px rgba(60,181,74,0.22), 0 0 40px rgba(60,181,74,0.55)",
            opacity: interpolate(since, [0, 6], [0, 1], clamp),
            transform: `scale(${interpolate(since, [0, 10], [1.2, 1], { ...clamp, easing: ease }) * (1 + 0.03 * Math.sin(since / 6))})`,
          }}
        />
      )}
    </div>
  );
};
/** Material icons the card uses (outlined set), as paths on a 24-unit grid. */
const ICON = {
  cloud: "M19.35 10.04C18.67 6.59 15.64 4 12 4 9.11 4 6.6 5.64 5.35 8.04 2.34 8.36 0 10.91 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96zM14 13v4h-4v-4H7l5-5 5 5h-3z",
  eye: "M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zM12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z",
  refresh: "M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z",
  arrow: "M7 10l5 5 5-5z",
  check: "M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z",
};
const Icon: React.FC<{ d: string; size?: number; color?: string }> = ({ d, size = 24, color = K.muted }) => (
  <svg width={dp(size)} height={dp(size)} viewBox="0 0 24 24" style={{ flexShrink: 0 }}>
    <path d={d} fill={color} />
  </svg>
);

const PLATFORMS = ["Claude", "OpenAI", "Gemini", "Ollama"];

const Pill: React.FC<{ pickAt: number; ringAt?: Span }> = ({ pickAt, ringAt }) => {
  const f = useCurrentFrame();
  const index = interpolate(f, [pickAt, pickAt + 14], [0, 2], { ...clamp, easing: ease });
  const seg = 100 / PLATFORMS.length;
  return (
    <Fill on={ringAt} radius={dp(28)}>
      <div style={{ position: "relative", height: dp(56), borderRadius: 999, background: K.card, border: `${dp(1)}px solid #BFE3CB`, padding: dp(3) }}>
        <div style={{ position: "absolute", top: dp(3), bottom: dp(3), right: `calc(${index * seg}% + ${dp(3) * (1 - index / 3)}px)`, width: `calc(${seg}% - ${dp(1.5)}px)`, borderRadius: 999, background: K.pillDark }} />
        <div style={{ position: "relative", display: "flex", height: "100%" }}>
          {PLATFORMS.map((name, i) => {
            const active = Math.abs(index - i) < 0.5;
            return (
              <div key={name} style={{ flex: 1, display: "flex", alignItems: "center", justifyContent: "center", fontSize: dp(14), fontWeight: 700, color: active ? "#fff" : K.onPrimary }}>
                {name}
              </div>
            );
          })}
        </div>
      </div>
    </Fill>
  );
};

/** An M3 outlined field: label on the border, supporting line under it. */
const Field: React.FC<{ label: string; value: React.ReactNode; supporting: string; focused?: boolean; trailing?: React.ReactNode; ringAt?: Span }> = ({
  label,
  value,
  supporting,
  focused,
  trailing,
  ringAt,
}) => (
  <div>
    <Fill on={ringAt} radius={dp(4)}>
      <div
        style={{
          position: "relative",
          height: dp(56),
          borderRadius: dp(4),
          border: focused ? `${dp(2)}px solid ${K.strong}` : `${dp(1)}px solid #84918A`,
          display: "flex",
          alignItems: "center",
          padding: `0 ${dp(16)}px`,
          gap: dp(8),
        }}>
        <div style={{ position: "absolute", top: -dp(9), right: dp(12), padding: `0 ${dp(4)}px`, background: K.card, fontSize: dp(12), color: focused ? K.strong : K.muted }}>{label}</div>
        <div style={{ flex: 1, fontSize: dp(16), color: K.text, overflow: "hidden", whiteSpace: "nowrap" }}>{value}</div>
        {trailing}
      </div>
    </Fill>
    <div style={{ fontSize: dp(12), color: K.muted, padding: `${dp(4)}px ${dp(16)}px 0`, fontFamily: supporting.startsWith("gemini") ? mono : fontFamily, direction: supporting.startsWith("gemini") ? "ltr" : "rtl", textAlign: "right" }}>
      {supporting}
    </div>
  </div>
);

const Badge: React.FC = () => (
  <span style={{ padding: `${dp(2)}px ${dp(6)}px`, borderRadius: dp(6), background: K.primary, color: K.onPrimary, fontSize: dp(11), fontWeight: 600 }}>חינמי</span>
);

const MODELS: [string, string][] = [
  ["Gemini 3.8 Flash", "gemini-3.8-flash"],
  ["Gemini 3.6 Flash", "gemini-3.6-flash"],
  ["Gemini 3.5 Flash-Lite", "gemini-3.5-flash-lite"],
  ["Gemini 3.1 Flash-Lite", "gemini-3.1-flash-lite"],
  ["Gemini 3 Flash Preview", "gemini-3-flash-preview"],
];

/** The model dropdown as `ModelPicker` draws it: check, name over id, the free-tier badge. */
const Menu: React.FC<{ picked: number; ringAt?: Span }> = ({ picked, ringAt }) => (
  <div style={{ position: "absolute", top: dp(64), right: 0, left: 0, background: K.card, borderRadius: dp(4), boxShadow: "0 18px 50px rgba(0,0,0,0.22)", padding: `${dp(8)}px 0`, zIndex: 5 }}>
    {MODELS.map(([name, id], i) => (
      <Fill key={id} on={i === MODELS.length - 1 ? ringAt : undefined} radius={dp(6)}>
        <div style={{ display: "flex", alignItems: "center", gap: dp(12), padding: `${dp(10)}px ${dp(16)}px`, background: i === MODELS.length - 1 ? "rgba(220,244,227,0.55)" : "transparent" }}>
          <span style={{ width: dp(24) }}>{i === picked && <Icon d={ICON.check} color={K.text} />}</span>
          <div style={{ flex: 1, minWidth: 0, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>
            <div style={{ fontSize: dp(16), color: K.text }}>{name}</div>
            <div style={{ fontSize: dp(12), color: K.muted, fontFamily: mono, direction: "ltr", textAlign: "right" }}>{id}</div>
          </div>
          <Badge />
        </div>
      </Fill>
    ))}
  </div>
);

export type AndroidTimes = {
  pickAt: number;
  pasteAt: number;
  menuAt?: number;
  chooseAt?: number;
  testAt?: number;
  /** Scrolls the screen up by this many px over frames 0–20, as a user would to reach the model. */
  scroll?: number;
  ring?: { pill?: Span; key?: Span; model?: Span; menu?: Span; test?: Span };
};

/** Settings → "סיכום AI": the card the user fills in, driven by the frames in [t]. */
export const AndroidAiCard: React.FC<{ t: AndroidTimes; width: number }> = ({ t, width }) => {
  const f = useCurrentFrame();
  const pasted = f >= t.pasteAt;
  const menuOpen = t.menuAt !== undefined && f >= t.menuAt && (t.chooseAt === undefined || f < t.chooseAt + 10);
  const chosen = t.chooseAt !== undefined && f >= t.chooseAt;
  const testing = t.testAt !== undefined && f >= t.testAt && f < t.testAt + 30;
  const ok = t.testAt !== undefined && f >= t.testAt + 30;
  const [modelName, modelId] = chosen ? MODELS[4] : MODELS[0];
  return (
    <div style={{ width, direction: "rtl", fontFamily, color: K.text, padding: `${dp(8)}px ${dp(16)}px`, transform: `translateY(${-(t.scroll ?? 0) * ramp(f, 0, 20)}px)` }}>
      <div style={{ fontSize: dp(22), fontWeight: 700, padding: `${dp(8)}px 0 ${dp(18)}px` }}>הגדרות</div>
      <div style={{ fontSize: dp(14), fontWeight: 700, color: K.strong, padding: `0 ${dp(16)}px ${dp(8)}px` }}>סיכום AI</div>
      <div style={{ background: K.card, borderRadius: dp(16), border: `1px solid ${K.border}`, boxShadow: "0 10px 30px rgba(22,60,36,0.08)", padding: dp(16), display: "flex", flexDirection: "column", gap: dp(16) }}>
        <div style={{ display: "flex", gap: dp(12), alignItems: "flex-start" }}>
          <Icon d={ICON.cloud} size={20} />
          <span style={{ fontSize: dp(12), color: K.muted, lineHeight: 1.45 }}>סיכומים ושאלות עוברים דרך ספק ה-AI שבחרתם, עם המפתח שלכם. זו היכולת היחידה ששולחת את התמלול אל מחוץ לטלפון.</span>
        </div>
        <Pill pickAt={t.pickAt} ringAt={t.ring?.pill} />
        <Field
          label="מפתח API"
          value={<span style={{ letterSpacing: dp(2), opacity: ramp(f, t.pasteAt, t.pasteAt + 6) }}>{pasted ? "•".repeat(22) : ""}</span>}
          supporting="אפשר ליצור מפתח ב-aistudio.google.com"
          focused={f >= t.pasteAt - 12 && f < t.pasteAt + 40}
          trailing={<Icon d={ICON.eye} />}
          ringAt={t.ring?.key}
        />
        <div style={{ position: "relative", display: "flex", gap: dp(4), alignItems: "flex-start" }}>
          <div style={{ flex: 1 }}>
            <Field label="מודל" value={modelName} supporting={modelId} focused={menuOpen} trailing={<Icon d={ICON.arrow} />} ringAt={t.ring?.model} />
          </div>
          <div style={{ width: dp(48), height: dp(56), display: "flex", alignItems: "center", justifyContent: "center", color: K.muted }}><Icon d={ICON.refresh} /></div>
          {menuOpen && <Menu picked={chosen ? 4 : 0} ringAt={t.ring?.menu} />}
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: dp(12) }}>
          <Fill on={t.ring?.test} radius={999}>
            <div style={{ padding: `${dp(10)}px ${dp(24)}px`, borderRadius: 999, border: `${dp(1)}px solid #BFE3CB`, color: K.onPrimary, fontSize: dp(14), fontWeight: 600 }}>בדיקת חיבור</div>
          </Fill>
          {testing && <span style={{ fontSize: dp(12), color: K.muted }}>⏳ בודק…</span>}
          {ok && <span style={{ display: "flex", alignItems: "center", gap: dp(8), fontSize: dp(12), color: K.text }}><Icon d={ICON.check} size={18} color={K.strong} /> עובד · 412 אלפיות שנייה</span>}
        </div>
      </div>
    </div>
  );
};
