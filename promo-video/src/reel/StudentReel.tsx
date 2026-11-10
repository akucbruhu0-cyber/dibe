// Student reel: hook title, retention cues, highlight pops and Hebrew captions,
// laid over the CapCut timeline (draft "1109", 43.03s). Times are timeline seconds.
// Rule: never cover the app screen — text sits on the face bubble or on empty white space.
// withVideo=false renders a transparent overlay to drop on top of the CapCut timeline.
import React from "react";
import { AbsoluteFill, OffthreadVideo, interpolate, spring, staticFile, useCurrentFrame, useVideoConfig } from "remotion";
import { loadFont } from "@remotion/google-fonts/NotoSansHebrew";
import { C } from "../theme";

const { fontFamily } = loadFont("normal", { weights: ["500", "700", "800", "900"], subsets: ["hebrew", "latin"] });

export const REEL_W = 1080;
export const REEL_H = 2408;
export const REEL_FPS = 30;
export const REEL_DUR = Math.round(43.033 * REEL_FPS);

// Where the face bubble is (measured on the timeline, px of 1080x2408).
// Before 19.5s it sits bottom-center and the white space above it is empty;
// from 19.5s it sits bottom-left and the right side is full of app UI.
const SWITCH = 19.5;
type Box = { x: number; y: number; w: number; h: number };
const POP_BOX = (t: number): Box => (t < SWITCH ? { x: 60, y: 960, w: 960, h: 470 } : { x: 10, y: 1450, w: 590, h: 370 });
const CAP_BOX = (t: number): Box => (t < SWITCH ? { x: 60, y: 1660, w: 960, h: 260 } : { x: 10, y: 1825, w: 590, h: 200 });

const clamp = { extrapolateLeft: "clamp", extrapolateRight: "clamp" } as const;
const KEY = ["בחינם", "טיסה", "במחשב", "AI", "עוקב", "המרצים", "סיכום", "בעברית", "התמלול", "לטלפון"];

// Speech islands (from silence detection + per-island transcription). "|" splits caption chunks.
const LINES: [number, number, string][] = [
  [0.0, 1.98, "כשהעליתי את|תוכנת התמלול|לפני כמה ימים"],
  [2.48, 3.8, "שאלו אותי|אם זה גם לטלפון"],
  [4.07, 8.36, "אז ממש בקרוב|אולי גרסה לטלפון|תשימו עוקב|זה יעלה|זה יהיה בחינם"],
  [8.79, 9.72, "זה עובד, תסתכלו"],
  [10.18, 11.9, "על מצב טיסה|זה ממשיך לעבוד"],
  [12.42, 13.2, "אתם רואים?"],
  [13.45, 14.6, "אתם רואים? אחלה 👌"],
  [17.2, 19.02, "יש בזה את|כל האופציות"],
  [19.23, 21.06, "שיש גם במחשב"],
  [21.44, 22.4, "אפשר ממש להסתכל"],
  [22.85, 24.5, "זה אחד המרצים"],
  [25.0, 28.39, "יש פה סיכום ב-AI|ה-AI כן עובד|עם אינטרנט, שתדעו"],
  [28.92, 31.77, "זה יהיה זמין עוד מעט|בעזרת השם גם בעברית"],
  [33.08, 34.14, "ממש יש פה אופציות"],
  [35.55, 36.3, "תראו"],
  [36.62, 37.6, "כמה דברים יפים"],
  [38.91, 40.68, "אז תהיו על זה|שימו עוקב, תסתכלו"],
  [41.07, 43.03, "בעזרת השם|בקרוב זה עולה"],
];

// Each chunk gets a slice of its island proportional to its length, and holds until the next one (max +0.6s).
const CHUNKS = (() => {
  const out: { s: number; e: number; t: string }[] = [];
  LINES.forEach(([s, e, text]) => {
    const parts = text.split("|");
    const total = parts.reduce((n, p) => n + p.length, 0);
    let t = s;
    parts.forEach((p) => {
      const d = ((e - s) * p.length) / total;
      out.push({ s: t, e: t + d, t: p });
      t += d;
    });
  });
  return out.map((c, i) => ({ ...c, e: Math.min(out[i + 1]?.s ?? 43.03, c.e + 0.6) }));
})();

type Pop = { s: number; e: number; icon: string; title: string; sub?: string };
const POPS: Pop[] = [
  { s: 7.4, e: 9.7, icon: "💸", title: "בחינם" },
  { s: 10.1, e: 13.4, icon: "✈️", title: "עובד במצב טיסה", sub: "התמלול בלי אינטרנט" },
  { s: 17.2, e: 19.5, icon: "💻 = 📱", title: "כל מה שיש במחשב" },
  { s: 19.5, e: 22.4, icon: "💻 = 📱", title: "כל מה שיש במחשב" },
  { s: 22.85, e: 25.0, icon: "🎓", title: "הרצאה שלמה, מתומללת" },
  { s: 25.0, e: 28.6, icon: "✨", title: "סיכום AI", sub: "(הסיכום צריך אינטרנט)" },
];

const useSec = () => useCurrentFrame() / REEL_FPS;
const fadeWin = (t: number, s: number, e: number, f = 0.18) =>
  interpolate(t, [s, s + f, e - f, e], [0, 1, 1, 0], clamp);
const useSpring = (s: number, damping = 14) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  return spring({ frame: frame - Math.round(s * fps), fps, config: { damping, stiffness: 180 } });
};

const stroke = (px: number): React.CSSProperties => ({
  WebkitTextStroke: `${px}px #000`,
  paintOrder: "stroke fill",
  textShadow: "0 6px 18px rgba(0,0,0,0.55)",
});

// Centers its children inside a box, so nothing can drift onto the app UI.
const InBox: React.FC<{ b: Box; opacity?: number; children: React.ReactNode }> = ({ b, opacity = 1, children }) => (
  <div style={{ position: "absolute", left: b.x, top: b.y, width: b.w, height: b.h, display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", opacity }}>
    {children}
  </div>
);

const Keyworded: React.FC<{ text: string; color: string }> = ({ text, color }) => (
  <>
    {text.split(" ").map((w, i) => (
      <span key={i} style={{ color: KEY.some((k) => w.includes(k)) ? color : undefined }}>
        {w}{" "}
      </span>
    ))}
  </>
);

// 0–3.2s: the hook, in the empty white space above the face. Audience call-out + outcome.
const Hook: React.FC = () => {
  const t = useSec();
  const a = useSpring(0.05);
  const b = useSpring(0.35);
  if (t > 3.4) return null;
  return (
    <InBox b={{ x: 40, y: 940, w: 1000, h: 640 }} opacity={interpolate(t, [3.0, 3.4], [1, 0], clamp)}>
      <div style={{ transform: `scale(${a})`, background: C.green, color: "#fff", fontSize: 56, fontWeight: 800, padding: "12px 40px", borderRadius: 999, boxShadow: "0 10px 30px rgba(0,0,0,0.3)" }}>
        🎓 מושלם לסטודנטים
      </div>
      <div style={{ ...stroke(14), transform: `scale(${b})`, marginTop: 26, color: "#fff", fontSize: 108, fontWeight: 900, lineHeight: 1.1, textAlign: "center" }}>
        תפסיקו לסכם
        <br />
        <span style={{ color: C.orangeSoft }}>הרצאות ביד ✋</span>
      </div>
      <div style={{ ...stroke(8), opacity: b, marginTop: 18, color: "#fff", fontSize: 50, fontWeight: 700 }}>
        תמלול בטלפון · בחינם · בלי אינטרנט
      </div>
    </InBox>
  );
};

// 3.2–6.8s: retention + save cue, early, as asked.
const WatchSave: React.FC = () => {
  const t = useSec();
  const s = useSpring(3.2);
  const o = fadeWin(t, 3.2, 6.8, 0.25);
  if (o === 0) return null;
  return (
    <InBox b={POP_BOX(t)} opacity={o}>
      <div style={{ transform: `scale(${s})`, background: "#fff", color: C.cardText, fontSize: 58, fontWeight: 800, padding: "22px 44px", borderRadius: 36, boxShadow: "0 16px 44px rgba(0,0,0,0.35)", textAlign: "center", lineHeight: 1.3 }}>
        👀 תראו עד הסוף
        <br />
        <span style={{ color: C.green }}>🔖 ותשמרו לתקופת המבחנים</span>
      </div>
    </InBox>
  );
};

const PopCard: React.FC<{ p: Pop }> = ({ p }) => {
  const t = useSec();
  const s = useSpring(p.s, 12);
  const o = fadeWin(t, p.s, p.e);
  if (o === 0) return null;
  const narrow = p.s >= SWITCH;
  return (
    <InBox b={POP_BOX(p.s)} opacity={o}>
      <div style={{ transform: `scale(${0.6 + 0.4 * s}) rotate(${(1 - s) * -4}deg)`, background: "#fff", borderRadius: 36, padding: narrow ? "18px 30px" : "24px 48px", boxShadow: "0 18px 50px rgba(0,0,0,0.38)", borderBottom: `10px solid ${C.green}`, textAlign: "center", maxWidth: POP_BOX(p.s).w - 20 }}>
        <div style={{ fontSize: narrow ? 72 : 92, lineHeight: 1.1 }}>{p.icon}</div>
        <div style={{ fontSize: narrow ? 54 : 74, fontWeight: 800, color: C.cardText, lineHeight: 1.2 }}>{p.title}</div>
        {p.sub && <div style={{ fontSize: narrow ? 34 : 42, fontWeight: 500, color: C.cardMuted, marginTop: 4 }}>{p.sub}</div>}
      </div>
    </InBox>
  );
};

// 38.9s → end: the ask, over the face. Follow (it's not out yet) + save.
const Cta: React.FC = () => {
  const t = useSec();
  const a = useSpring(38.9, 12);
  const b = useSpring(39.5, 12);
  if (t < 38.9) return null;
  return (
    <InBox b={POP_BOX(t)}>
      <div style={{ transform: `scale(${a})`, background: C.green, color: "#fff", fontSize: 64, fontWeight: 900, padding: "18px 36px", borderRadius: 36, textAlign: "center", lineHeight: 1.2, boxShadow: "0 18px 50px rgba(0,0,0,0.38)" }}>
        ➕ עקבו
        <br />
        <span style={{ fontSize: 38, fontWeight: 700 }}>ותהיו הראשונים לקבל אותה</span>
      </div>
      <div style={{ transform: `scale(${b})`, marginTop: 20, background: "#fff", color: C.cardText, fontSize: 40, fontWeight: 800, padding: "14px 30px", borderRadius: 999, boxShadow: "0 14px 40px rgba(0,0,0,0.3)" }}>
        🔖 שמרו לתקופת המבחנים
      </div>
    </InBox>
  );
};

const Captions: React.FC = () => {
  const t = useSec();
  const c = CHUNKS.find((x) => t >= x.s && t < x.e);
  const s = useSpring(c?.s ?? 0, 16);
  if (!c) return null;
  return (
    <InBox b={CAP_BOX(t)}>
      <div style={{ ...stroke(14), transform: `scale(${0.85 + 0.15 * s})`, color: "#fff", fontSize: t < SWITCH ? 84 : 66, fontWeight: 900, textAlign: "center", lineHeight: 1.15 }}>
        <Keyworded text={c.t} color={C.orangeSoft} />
      </div>
    </InBox>
  );
};

export const StudentReel: React.FC<{ withVideo: boolean }> = ({ withVideo }) => (
  <AbsoluteFill style={{ fontFamily, direction: "rtl" }}>
    {withVideo && <OffthreadVideo src={staticFile("reel/base.mp4")} />}
    {POPS.map((p) => <PopCard key={p.s} p={p} />)}
    <Captions />
    <Hook />
    <WatchSave />
    <Cta />
  </AbsoluteFill>
);
