import React from "react";
import { interpolate, useCurrentFrame } from "remotion";
import { C, COL_W, COL_X, T } from "../theme";
import { useLang, useT } from "../i18n";
import { CUES, seq } from "../timing";
import { AppCard, Chip, Icon, SceneFrame, SideBars, Wave, between, typed, useSpr, useTrack, win } from "./shared";

const pad = (n: number) => String(n).padStart(2, "0");

const Floaters: React.FC<{ from: number }> = ({ from }) => {
  const frame = useCurrentFrame();
  const items = [
    { label: "MP3", x: 190, y: 380, d: 0 },
    { label: "MP4", x: 330, y: 650, d: 0.2 },
    { label: "WAV", x: 1500, y: 400, d: 0.35 },
    { label: "YouTube", x: 1450, y: 700, d: 0.5 },
  ];
  return (
    <>
      {items.map((it, i) => (
        <div key={it.label} style={{ position: "absolute", left: it.x, top: it.y + Math.sin(frame / 18 + i) * 14, direction: "ltr", opacity: 0.8 * win(frame, from + it.d, from + it.d + 0.4) }}>
          <Chip delay={from + it.d} size={30} tone="dark">{it.label}</Chip>
        </div>
      ))}
    </>
  );
};

/** One word that swaps (live → file → link) with a vertical spring, like a rolling counter. */
const RollingWord: React.FC<{ words: string[]; at: number[] }> = ({ words, at }) => {
  const frame = useCurrentFrame();
  const track = useTrack();
  return (
    <div style={{ position: "relative", height: 96, width: COL_W, textAlign: "center", overflow: "hidden" }}>
      {words.map((w, i) => {
        // the old word leaves first (0.25s), then the new one rises in — never two words at once
        const inP = i === 0 ? 1 : win(frame, at[i] + 0.12, at[i] + 0.4);
        const outP = i === words.length - 1 ? 0 : win(frame, at[i + 1] - 0.05, at[i + 1] + 0.18);
        return (
          <div
            key={w}
            style={{
              position: "absolute",
              inset: 0,
              fontSize: 84,
              fontWeight: 800,
              letterSpacing: track,
              lineHeight: "96px",
              color: C.orange,
              opacity: inP * (1 - outP),
              translate: `0 ${(1 - inP) * 40 - outP * 40}px`,
            }}
          >
            {w}
          </div>
        );
      })}
    </div>
  );
};

export const Live: React.FC = () => {
  const frame = useCurrentFrame();
  const lang = useLang();
  const t = useT().live;
  const cue = CUES[lang].live;
  const dur = seq(lang, "live").dur / 30;
  // short, almost non-overlapping fades so two cards never ghost over each other
  const aOp = between(frame, -1, cue.b + 0.12, 0.14);
  const bOp = between(frame, cue.b, cue.c + 0.12, 0.14);
  const cOp = between(frame, cue.c, dur + 1, 0.14);
  const seconds = Math.floor(frame / 30);

  const cardIn = useSpr(0.05);
  const dropIn = useSpr(cue.b + 0.1, true);
  const urlIn = useSpr(cue.c + 0.1);
  const progB = win(frame, cue.b + 0.5, cue.c - 0.15);
  const progC = win(frame, cue.c + 0.6, cue.ready - 0.1);
  const done = win(frame, cue.ready, cue.ready + 0.3);
  const track = useTrack();

  return (
    <SceneFrame dur={seq(lang, "live").dur}>
      <SideBars energy={frame < T(cue.b) ? 1 : 0.35} />
      <Floaters from={cue.b} />

      <div style={{ position: "absolute", top: 70, left: COL_X - 60, width: COL_W + 120, textAlign: "center", opacity: useSpr(0.05) }}>
        <div style={{ color: C.text, fontSize: 64, fontWeight: 800, letterSpacing: track }}>{t.prefix}</div>
        <RollingWord words={t.words} at={[0, cue.b, cue.c]} />
      </div>

      <div style={{ position: "absolute", top: 300, left: COL_X + 20, width: COL_W - 40, height: 640, opacity: cardIn, translate: `0 ${(1 - cardIn) * 40}px` }}>
        {/* A — live recording */}
        <div style={{ position: "absolute", opacity: aOp, width: "100%", scale: String(1 - (1 - aOp) * 0.04) }}>
          <AppCard title="DIBE">
            <div style={{ display: "flex", alignItems: "center", gap: 14, fontSize: 30, fontWeight: 700 }}>
              <div style={{ width: 20, height: 20, borderRadius: 10, background: C.red, opacity: 0.55 + 0.45 * Math.sin(frame / 5) }} />
              {t.recording}
              <span style={{ marginInlineStart: "auto", color: C.cardMuted, direction: "ltr", fontVariantNumeric: "tabular-nums" }}>00:{pad(seconds)}</span>
            </div>
            <div style={{ margin: "20px 0", display: "flex", justifyContent: "center" }}>
              <Wave width={620} height={86} />
            </div>
            <div style={{ display: "flex", flexDirection: "column", gap: 14, minHeight: 250 }}>
              {t.lines.map((line, i) => {
                const start = cue.a + 0.15 + i * 0.7;
                if (frame < T(start)) return null;
                return (
                  <div key={i} style={{ display: "flex", gap: 16, fontSize: 29, lineHeight: 1.3 }}>
                    <span style={{ color: C.blue, fontWeight: 700, direction: "ltr", fontVariantNumeric: "tabular-nums" }}>00:{pad(i * 4 + 1)}</span>
                    <span>{typed(line, frame, start, 52)}</span>
                  </div>
                );
              })}
              {frame >= T(cue.a + 2.2) && <div style={{ fontSize: 29, color: "#a0a0a0", lineHeight: 1.3 }}>{typed(t.tail, frame, cue.a + 2.2, 26)}▍</div>}
            </div>
          </AppCard>
        </div>

        {/* B — a file dropped in */}
        <div style={{ position: "absolute", opacity: bOp, width: "100%", scale: String(0.96 + 0.04 * bOp) }}>
          <AppCard title={t.importTitle}>
            <div style={{ border: `3px dashed ${C.border}`, borderRadius: 22, padding: 28, minHeight: 250, display: "flex", alignItems: "center", justifyContent: "center" }}>
              <div
                style={{
                  display: "flex",
                  alignItems: "center",
                  gap: 16,
                  padding: "20px 30px",
                  borderRadius: 18,
                  background: C.cardSoft,
                  fontSize: 34,
                  fontWeight: 700,
                  translate: `0 ${interpolate(dropIn, [0, 1], [-160, 0])}px`,
                  opacity: Math.min(1, dropIn * 2),
                }}
              >
                <Icon name="file" size={40} color={C.blue} />
                <span style={{ direction: "ltr" }}>{t.file}</span>
              </div>
            </div>
            <Progress label={t.transcribing} p={progB} />
          </AppCard>
        </div>

        {/* C — a link pasted */}
        <div style={{ position: "absolute", opacity: cOp, width: "100%", scale: String(0.96 + 0.04 * cOp) }}>
          <AppCard title={t.importTitle}>
            <div style={{ border: `3px dashed ${C.border}`, borderRadius: 22, padding: 28, minHeight: 250, display: "flex", alignItems: "center", justifyContent: "center" }}>
              <div
                style={{
                  display: "flex",
                  alignItems: "center",
                  gap: 16,
                  padding: "20px 30px",
                  borderRadius: 18,
                  background: C.cardSoft,
                  fontSize: 32,
                  opacity: urlIn,
                  translate: `0 ${(1 - urlIn) * 30}px`,
                }}
              >
                <Icon name="link" size={40} color={C.blue} />
                <span style={{ direction: "ltr", color: C.cardText }}>{typed(t.link, frame, cue.c + 0.2, 26)}</span>
              </div>
            </div>
            <div style={{ position: "relative", height: 96, marginTop: 24 }}>
              <div style={{ position: "absolute", width: "100%", opacity: 1 - done }}>
                <Progress label={t.transcribing} p={progC} bare />
              </div>
              <div style={{ position: "absolute", width: "100%", opacity: done, display: "flex", alignItems: "center", gap: 14, fontSize: 36, fontWeight: 800, color: C.greenDark, translate: `0 ${(1 - done) * 14}px` }}>
                <Icon name="check" size={46} color={C.green} />
                {t.ready}
              </div>
            </div>
          </AppCard>
        </div>
      </div>
    </SceneFrame>
  );
};

const Progress: React.FC<{ label: string; p: number; bare?: boolean }> = ({ label, p, bare }) => (
  <div style={{ marginTop: bare ? 0 : 24 }}>
    <div style={{ display: "flex", justifyContent: "space-between", fontSize: 26, fontWeight: 700, marginBottom: 12 }}>
      <span>{label}</span>
      <span style={{ direction: "ltr" }}>{Math.round(p * 100)}%</span>
    </div>
    <div style={{ height: 14, borderRadius: 7, background: C.cardSoft, overflow: "hidden" }}>
      <div style={{ height: "100%", width: `${p * 100}%`, background: `linear-gradient(90deg, ${C.green}, ${C.orange})`, float: "inline-start" }} />
    </div>
  </div>
);
