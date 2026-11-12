// "How do I get a free key?": Google AI Studio → free Gemini API key → DIBE → Gemini 3 Flash Preview.
// One timeline, two layouts: desktop (1920×1080, real DIBE screenshots) and phone (1080×1920,
// the Android card rebuilt from Compose). Facts checked 2026-11-12, see promo-video/public/gemini-key/FLOW.md.
import React from "react";
import { AbsoluteFill, Img, Sequence, staticFile, useCurrentFrame } from "remotion";
import { AndroidAiCard, type AndroidTimes } from "./android";
import { AppFrame, BrowserFrame, Caption, K, PhoneFrame, SHOTS, Shot, Swap, Toast, fontFamily, mono, ramp, useIn, type Rect, type ShotSrc } from "./parts";

export type Layout = "desktop" | "phone";
export const KEY_FPS = 30;

const SCENES = [
  ["title", 120],
  ["studio", 210],
  ["create", 240],
  ["copy", 210],
  ["paste", 270],
  ["model", 270],
  ["test", 210],
  ["outro", 270],
] as const;
export const KEY_DUR = SCENES.reduce((sum, [, dur]) => sum + dur, 0);

/** Where things are in the screenshots (image pixels). */
const R = {
  createApiKey: { x: 1305, y: 41, w: 122, h: 26 },
  createKey: { x: 869, y: 467, w: 82, h: 27 },
  copyIcon: { x: 1268, y: 202, w: 22, h: 22 },
  geminiOption: { x: 270, y: 384, w: 195, h: 34 },
  keyField: { x: 264, y: 360, w: 256, h: 36 },
  testButton: { x: 264, y: 538, w: 96, h: 36 },
  testResult: { x: 600, y: 558, w: 120, h: 20 },
} satisfies Record<string, Rect>;

// ---- stage: the screenshot box for each layout ----

const DESK = { w: 1300, h: 688 };
const PHONE = { w: 856, h: 1172 };
const CAPTION = { desktop: { size: 56, width: 1560 }, phone: { size: 70, width: 920 } };

const Page: React.FC<{ layout: Layout; step?: string; title: string; sub?: string; children: React.ReactNode }> = ({ layout, step, title, sub, children }) => {
  const enter = useIn(4);
  const phone = layout === "phone";
  return (
    <AbsoluteFill>
      <div style={{ position: "absolute", top: phone ? 110 : 44, left: 0, right: 0, display: "flex", justifyContent: "center" }}>
        <Caption step={step} title={title} sub={sub} {...CAPTION[layout]} />
      </div>
      <div
        style={{
          position: "absolute",
          top: phone ? 560 : 300,
          left: 0,
          right: 0,
          display: "flex",
          justifyContent: "center",
          opacity: enter,
          transform: `translateY(${(1 - enter) * 60}px)`,
        }}>
        {children}
      </div>
    </AbsoluteFill>
  );
};

/** A web page: in a desktop browser window, or in a phone's browser. */
const Web: React.FC<{ layout: Layout; url: string; typeFrom?: number; children: (box: { w: number; h: number }) => React.ReactNode }> = ({ layout, url, typeFrom, children }) =>
  layout === "desktop" ? (
    <BrowserFrame url={url} typeFrom={typeFrom} w={DESK.w}>
      {children(DESK)}
    </BrowserFrame>
  ) : (
    <PhoneFrame w={900} h={1360} url={url.replace(/\/.*/, "")} dark>
      {children(PHONE)}
    </PhoneFrame>
  );

/** Zoom that eases from [from] to [to] between frames a and b. */
const useZoom = (from: number, to: number, a: number, b: number) => {
  const f = useCurrentFrame();
  return from + (to - from) * ramp(f, a, b);
};

/** A white patch with new text over a screenshot field (the mock had no real key, so no live model list). */
const FieldText: React.FC<{ map: (r: Rect) => Rect; rect: Rect; text: string; scale: number }> = ({ map, rect, text, scale }) => {
  const r = map(rect);
  return (
    <div style={{ position: "absolute", left: r.x, top: r.y, width: r.w, height: r.h, background: "#fff", display: "flex", alignItems: "center", justifyContent: "flex-end", fontSize: 15 * scale, color: K.text, fontFamily }}>
      {text}
    </div>
  );
};

// ---- scenes ----

const Title: React.FC<{ layout: Layout }> = ({ layout }) => {
  const a = useIn(0);
  const b = useIn(8);
  const phone = layout === "phone";
  return (
    <AbsoluteFill style={{ alignItems: "center", justifyContent: "center", direction: "rtl", fontFamily, color: K.text, textAlign: "center", padding: 80 }}>
      <Img src={staticFile("logo-disc.png")} style={{ width: phone ? 220 : 170, opacity: a, transform: `scale(${0.8 + 0.2 * a})` }} />
      <div style={{ fontSize: phone ? 104 : 96, fontWeight: 800, marginTop: 36, lineHeight: 1.1, opacity: a }}>מחברים את Gemini בחינם</div>
      <div style={{ fontSize: phone ? 46 : 40, color: K.muted, marginTop: 28, lineHeight: 1.4, opacity: b }}>
        מפתח API מ-Google AI Studio{phone ? <br /> : " · "}6 צעדים, בערך 2 דקות
      </div>
    </AbsoluteFill>
  );
};

const Studio: React.FC<{ layout: Layout }> = ({ layout }) => {
  const f = useCurrentFrame();
  const phone = layout === "phone";
  const zoom = useZoom(phone ? 1.6 : 1, phone ? 2.2 : 1, 60, 160);
  return (
    <Page layout={layout} step="שלב 1 מתוך 6" title="נכנסים ל-Google AI Studio" sub="aistudio.google.com · מתחברים עם חשבון הגוגל שלכם">
      <div style={{ position: "relative" }}>
        <Web layout={layout} url="aistudio.google.com/apikey" typeFrom={phone ? undefined : 12}>
          {(box) => (
            <div style={{ opacity: phone ? 1 : ramp(f, 60, 72) }}>
              <Shot src={SHOTS.keys} box={box} focus={{ x: 300, y: 120 }} zoom={zoom} />
            </div>
          )}
        </Web>
        <Toast text="בכניסה הראשונה מאשרים את התנאים, וגוגל יוצרת לכם מפתח ראשון לבד" at={95} size={phone ? 32 : 28} style={{ bottom: phone ? 360 : 40, left: "50%", transform: "translateX(-50%)", whiteSpace: phone ? "normal" : "nowrap", width: phone ? 760 : undefined, textAlign: "center" }} />
      </div>
    </Page>
  );
};

const Create: React.FC<{ layout: Layout }> = ({ layout }) => {
  const phone = layout === "phone";
  const z1 = useZoom(phone ? 1.4 : 1, phone ? 2.6 : 1.3, 10, 50);
  const z2 = useZoom(phone ? 1.5 : 1.15, phone ? 2.4 : 1.5, 125, 165);
  return (
    <Page layout={layout} step="שלב 2 מתוך 6" title="יוצרים מפתח חדש" sub="לוחצים Create API key, ואז Create key">
      <Web layout={layout} url="aistudio.google.com/apikey">
        {(box) => (
          <Swap
            at={[
              [0, <Shot src={SHOTS.keys} box={box} focus={{ x: 1366, y: 54 }} zoom={z1} rings={[{ rect: R.createApiKey, at: 40 }]} />],
              [120, <Shot src={SHOTS.dialog} box={box} focus={{ x: 820, y: 430 }} zoom={z2} rings={[{ rect: R.createKey, at: 150 }]} />],
            ]}
          />
        )}
      </Web>
    </Page>
  );
};

const Copy: React.FC<{ layout: Layout }> = ({ layout }) => {
  const phone = layout === "phone";
  const zoom = useZoom(phone ? 1.4 : 1, phone ? 3 : 1.8, 10, 60);
  return (
    <Page layout={layout} step="שלב 3 מתוך 6" title="מעתיקים את המפתח" sub="סמל ההעתקה שליד המפתח. שומרים אותו בסוד, כמו סיסמה">
      <Web layout={layout} url="aistudio.google.com/apikey">
        {(box) => (
          <Shot src={SHOTS.keys} box={box} focus={{ x: 1250, y: 213 }} zoom={zoom} rings={[{ rect: R.copyIcon, at: 60 }]}>
            {(map) => {
              const r = map(R.copyIcon);
              return <Toast text="✓ המפתח הועתק" at={100} size={phone ? 34 : 30} style={{ left: r.x - (phone ? 320 : 260), top: r.y + r.h + 40 }} />;
            }}
          </Shot>
        )}
      </Web>
    </Page>
  );
};

/** DIBE's own settings: real desktop screenshots, or the rebuilt Android card. */
const Dibe: React.FC<{ layout: Layout; desktop: (box: { w: number; h: number }) => React.ReactNode; android: AndroidTimes }> = ({ layout, desktop, android }) =>
  layout === "desktop" ? (
    <AppFrame w={DESK.w}>{desktop({ w: DESK.w, h: DESK.h + 52 })}</AppFrame>
  ) : (
    <PhoneFrame w={900} h={1360}>
      <AndroidAiCard t={android} width={856} />
    </PhoneFrame>
  );

const dibeFocus = { x: 560, y: 330 };
const DIBE_ZOOM = 1.42;
const dibeScale = (box: { w: number }, src: ShotSrc) => (box.w / src.w) * DIBE_ZOOM;

const Paste: React.FC<{ layout: Layout }> = ({ layout }) => (
  <Page
    layout={layout}
    step="שלב 4 מתוך 6"
    title="מדביקים את המפתח ב-DIBE"
    sub={layout === "desktop" ? "הגדרות ← עוזר AI ← פלטפורמה: Gemini ← מפתח API של Gemini" : "הגדרות ← סיכום AI ← Gemini ← מפתח API"}>
    <Dibe
      layout={layout}
      android={{ pickAt: 45, pasteAt: 160, ring: { pill: [25, 120], key: [140, 270] } }}
      desktop={(box) => (
        <Swap
          at={[
            [0, <Shot src={SHOTS.platform} box={box} focus={dibeFocus} zoom={DIBE_ZOOM} rings={[{ rect: R.geminiOption, at: 30 }]} />],
            [130, <Shot src={SHOTS.pasted} box={box} focus={dibeFocus} zoom={DIBE_ZOOM} rings={[{ rect: R.keyField, at: 150 }]} />],
          ]}
        />
      )}
    />
  </Page>
);

const MODELS: [string, string][] = [
  ["Gemini 3.8 Flash", "gemini-3.8-flash"],
  ["Gemini 3.6 Flash", "gemini-3.6-flash"],
  ["Gemini 3.5 Flash-Lite", "gemini-3.5-flash-lite"],
  ["Gemini 3.1 Flash-Lite", "gemini-3.1-flash-lite"],
  ["Gemini 3 Flash Preview", "gemini-3-flash-preview"],
];

/** The desktop model list as `AiModelPicker` draws it, with the key's own models joined in. */
const DesktopList: React.FC<{ map: (r: Rect) => Rect; s: number; chooseAt: number }> = ({ map, s, chooseAt }) => {
  const f = useCurrentFrame();
  const open = ramp(f, 20, 30) * (1 - ramp(f, chooseAt + 15, chooseAt + 25));
  const panel = map({ x: 264, y: 120, w: 300, h: 296 });
  const row = 52 * s;
  return (
    <div style={{ position: "absolute", left: panel.x, top: panel.y, width: panel.w, background: "#fff", borderRadius: 12 * s, boxShadow: "0 20px 50px rgba(0,0,0,0.18)", border: `1px solid ${K.border}`, opacity: open, direction: "rtl", fontFamily, padding: `${6 * s}px 0` }}>
      {MODELS.map(([name, id], i) => {
        const target = i === MODELS.length - 1;
        const checked = target ? f >= chooseAt : i === 0 && f < chooseAt;
        const glow = target && f >= 60;
        return (
          <div key={id} style={{ height: row, display: "flex", alignItems: "center", gap: 10 * s, padding: `0 ${12 * s}px`, background: glow ? "rgba(220,244,227,0.8)" : "transparent", outline: glow ? `${3 * s}px solid ${K.ring}` : "none", borderRadius: 8 * s }}>
            <div style={{ flex: 1, textAlign: "right" }}>
              <div style={{ fontSize: 14 * s, fontWeight: 600, color: K.text }}>{name}</div>
              <div style={{ fontSize: 11 * s, color: K.muted, fontFamily: mono, direction: "ltr", textAlign: "right" }}>{id}</div>
            </div>
            <span style={{ fontSize: 10 * s, padding: `${2 * s}px ${7 * s}px`, borderRadius: 999, background: K.primary, color: K.onPrimary, border: "1px solid #BFE3CB" }}>חינמי</span>
            <span style={{ width: 14 * s, fontSize: 14 * s, color: K.text }}>{checked ? "✓" : ""}</span>
          </div>
        );
      })}
      <div style={{ borderTop: `1px solid ${K.border}`, marginTop: 4 * s, padding: `${6 * s}px ${12 * s}px 0`, fontSize: 10 * s, color: K.muted }}>אפשר גם להקליד כל מזהה מודל שהספק מציע.</div>
    </div>
  );
};

const Model: React.FC<{ layout: Layout }> = ({ layout }) => {
  const f = useCurrentFrame();
  const chooseAt = 160;
  return (
    <Page layout={layout} step="שלב 5 מתוך 6" title="בוחרים מודל: Gemini 3 Flash Preview" sub="מסומן 'חינמי'. הרשימה המלאה נטענת אחרי שמדביקים מפתח">
      <Dibe
        layout={layout}
        android={{ pickAt: -99, pasteAt: -99, scroll: 560, menuAt: 25, chooseAt, ring: { model: [0, 25], menu: [60, chooseAt + 10] } }}
        desktop={(box) => {
          const s = dibeScale(box, SHOTS.pasted);
          return (
            <Shot src={SHOTS.pasted} box={box} focus={dibeFocus} zoom={DIBE_ZOOM}>
              {(map) => (
                <>
                  {f >= chooseAt + 20 && <FieldText map={map} rect={{ x: 300, y: 423, w: 214, h: 29 }} text="gemini-3-flash-preview" scale={s} />}
                  <DesktopList map={map} s={s} chooseAt={chooseAt} />
                </>
              )}
            </Shot>
          );
        }}
      />
    </Page>
  );
};

const Test: React.FC<{ layout: Layout }> = ({ layout }) => {
  const f = useCurrentFrame();
  return (
    <Page layout={layout} step="שלב 6 מתוך 6" title="בודקים חיבור, וזהו" sub="'בדיקת חיבור' ← 'עובד'. מעכשיו כל הקלטה מקבלת סיכום">
      <Dibe
        layout={layout}
        android={{ pickAt: -99, pasteAt: -99, chooseAt: -99, testAt: 45, ring: { test: [20, 210] } }}
        desktop={(box) => {
          const s = dibeScale(box, SHOTS.tested);
          return (
            <Shot src={SHOTS.tested} box={box} focus={{ x: 560, y: 420 }} zoom={DIBE_ZOOM} rings={[{ rect: R.testButton, at: 20 }, { rect: R.testResult, at: 75 }]}>
              {(map) => (
                <>
                  <FieldText map={map} rect={{ x: 300, y: 388, w: 214, h: 28 }} text="gemini-3-flash-preview" scale={s} />
                  {f < 70 && <FieldText map={map} rect={{ x: 560, y: 560, w: 162, h: 18 }} text="" scale={s} />}
                </>
              )}
            </Shot>
          );
        }}
      />
    </Page>
  );
};

const FACTS = [
  ["✓", "המפתח חינמי לכל חשבון גוגל: בלי מנוי ובלי כרטיס אשראי"],
  ["✓", "במסלול החינמי יש מכסה יומית: כרגע כ-20 בקשות ביום ל-Gemini 3 Flash Preview"],
  ["ⓘ", "במסלול החינמי גוגל רשאית להשתמש בתוכן שנשלח כדי לשפר את המוצרים שלה"],
];

const Outro: React.FC<{ layout: Layout }> = ({ layout }) => {
  const phone = layout === "phone";
  const a = useIn(0);
  return (
    <AbsoluteFill style={{ alignItems: "center", justifyContent: "center", direction: "rtl", fontFamily, color: K.text, padding: phone ? 70 : 160 }}>
      <div style={{ fontSize: phone ? 76 : 78, fontWeight: 800, opacity: a, textAlign: "center" }}>מוכן. הסיכומים עובדים 🎉</div>
      <div style={{ marginTop: phone ? 70 : 54, display: "flex", flexDirection: "column", gap: phone ? 34 : 24, width: phone ? 940 : 1400 }}>
        {FACTS.map(([mark, text], i) => (
          <Fact key={text} mark={mark} text={text} delay={20 + i * 14} size={phone ? 40 : 36} />
        ))}
      </div>
    </AbsoluteFill>
  );
};

const Fact: React.FC<{ mark: string; text: string; delay: number; size: number }> = ({ mark, text, delay, size }) => {
  const a = useIn(delay);
  return (
    <div style={{ display: "flex", gap: size * 0.6, alignItems: "flex-start", background: K.card, border: `1px solid ${K.border}`, borderRadius: size * 0.7, padding: `${size * 0.6}px ${size * 0.8}px`, opacity: a, transform: `translateY(${(1 - a) * 30}px)`, boxShadow: "0 10px 30px rgba(22,60,36,0.07)" }}>
      <span style={{ fontSize: size, fontWeight: 800, color: mark === "✓" ? K.strong : K.muted }}>{mark}</span>
      <span style={{ fontSize: size, lineHeight: 1.4 }}>{text}</span>
    </div>
  );
};

const VIEWS: Record<(typeof SCENES)[number][0], React.FC<{ layout: Layout }>> = { title: Title, studio: Studio, create: Create, copy: Copy, paste: Paste, model: Model, test: Test, outro: Outro };

export const GeminiKey: React.FC<{ layout: Layout }> = ({ layout }) => {
  let from = 0;
  return (
    <AbsoluteFill style={{ background: `radial-gradient(circle at 50% 30%, #ffffff 0%, ${K.bg} 55%, ${K.bg2} 100%)` }}>
      {SCENES.map(([id, dur]) => {
        const View = VIEWS[id];
        const start = from;
        from += dur;
        return (
          <Sequence key={id} from={start} durationInFrames={dur} premountFor={KEY_FPS}>
            <View layout={layout} />
          </Sequence>
        );
      })}
    </AbsoluteFill>
  );
};
