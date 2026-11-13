// Feature-card loops (720x720): SiteFeatSources, SiteFeatAi, SiteFeatChart, SiteFeatExport.
import React from "react";
import { useCurrentFrame } from "remotion";
import { useLang, useT } from "../i18n";
import { Icon, type IconName } from "../ui";
import { B, Canvas, Glow, RollingTranscript, SETTLE, SparkTrio, Surface, enter, inOut, mono, ramp, rubik } from "./kit";

/** Fade everything back to the frame-0 state over [a, b] so the loop closes. */
const useReset = (a: number, b: number) => 1 - ramp(useCurrentFrame(), a, b);

// ---------- SiteFeatSources: 4 s, no text. File, mic, link take turns feeding one transcript. Poster 60. ----------
const SOURCES: IconName[] = ["file", "mic", "link"];
export const SiteFeatSources: React.FC = () => {
  const f = useCurrentFrame();
  const per = 40;
  const k = Math.floor(f / per) % 3;
  const local = f % per;
  return (
    <Canvas>
      <div style={{ position: "absolute", top: 70, left: 0, right: 0, display: "flex", justifyContent: "center", gap: 48, direction: "ltr" }}>
        {SOURCES.map((name, i) => {
          const active = i === k ? Math.sin(Math.PI * ramp(local, 0, per)) : 0;
          return (
            <div
              key={name}
              style={{
                width: 132,
                height: 132,
                borderRadius: 36,
                background: active > 0.05 ? B.mint : B.card,
                border: `2px solid ${active > 0.05 ? B.forest : B.line}`,
                display: "flex",
                alignItems: "center",
                justifyContent: "center",
                scale: String(1 + 0.03 * active),
              }}
            >
              <Icon name={name} size={60} color={B.forest} />
            </div>
          );
        })}
      </div>
      {/* the dot travelling from the active source into the transcript */}
      {(() => {
        const p = ramp(local, 2, 22, inOut);
        const x0 = 360 + (k - 1) * 180;
        const x = x0 + (360 - x0) * p;
        const y = 210 + (330 - 210) * p;
        return <div style={{ position: "absolute", left: x - 9, top: y - 9, width: 18, height: 18, borderRadius: 9, background: B.forest, opacity: Math.sin(Math.PI * Math.min(1, p * 1.05)) }} />;
      })()}
      <Surface style={{ position: "absolute", left: 90, right: 90, top: 340, padding: "40px 44px" }}>
        <RollingTranscript lines={["a", "b", "c"]} bars loop={120} offset={-14} rowH={74} rows={3} fontSize={40} width={452} />
      </Surface>
    </Canvas>
  );
};

// ---------- SiteFeatAi: 6 s. Prompt typed → glow behind the summary while it writes → glow fades 900 ms → still. ----------
export const SiteFeatAi: React.FC = () => {
  const f = useCurrentFrame();
  const lang = useLang();
  const t = useT().site;
  const reset = useReset(162, 179);
  const typedN = Math.floor(ramp(f, 6, 48, (x) => x) * t.prompt.length);
  const done = 126;
  const glow = f < 56 ? 0 : ramp(f, 56, 66) * (1 - ramp(f, done, done + SETTLE));
  const fs = lang === "he" ? 30 : 28;
  return (
    <Canvas>
      <div style={{ opacity: reset }}>
        <Surface radius={999} style={{ position: "absolute", left: 56, right: 56, top: 60, height: 96, display: "flex", alignItems: "center", gap: 18, padding: "0 32px" }}>
          <SparkTrio size={40} />
          <span style={{ fontSize: fs + 2, color: B.ink }}>{t.prompt.slice(0, typedN)}</span>
          {f < 52 && <span style={{ width: 3, height: 40, background: B.forest, marginInlineStart: -12 }} />}
        </Surface>
        <div style={{ position: "absolute", left: 56, right: 56, top: 200, ...enter(f, 56) }}>
          <Glow on={glow}>
            <Surface style={{ padding: "34px 38px", minHeight: 400 }}>
              <div style={{ fontFamily: rubik, fontWeight: 700, fontSize: 36, marginBottom: 22, display: "flex", alignItems: "center", gap: 12 }}>
                {f < done ? t.summarizing : t.summaryTitle}
              </div>
              {t.bullets.map((b, i) => (
                <div key={i} style={{ display: "flex", gap: 14, fontSize: fs, lineHeight: 1.55, marginBottom: 14, ...enter(f, 70 + i * 18) }}>
                  <span style={{ width: 10, height: 10, borderRadius: 5, background: B.forest, marginTop: fs * 0.62, flexShrink: 0 }} />
                  <span>{b}</span>
                </div>
              ))}
            </Surface>
          </Glow>
        </div>
      </div>
    </Canvas>
  );
};

// ---------- SiteFeatChart: 5 s. Question bubble, answer with a bar chart drawing in. ----------
const VALUES = [12, 18, 9];
export const SiteFeatChart: React.FC = () => {
  const f = useCurrentFrame();
  const lang = useLang();
  const t = useT().site;
  const reset = useReset(134, 149);
  const done = 96;
  const glow = f < 26 ? 0 : ramp(f, 26, 36) * (1 - ramp(f, done, done + SETTLE));
  const max = Math.max(...VALUES);
  const fs = lang === "he" ? 28 : 26;
  return (
    <Canvas>
      <div style={{ opacity: reset }}>
        {/* the question is the user's: it sits at the inline-end, filled Mint */}
        <div style={{ position: "absolute", insetInlineEnd: 56, top: 56, maxWidth: 520, padding: "20px 28px", borderRadius: 22, borderEndEndRadius: 8, background: B.mint, fontSize: fs + 2, ...enter(f, 4) }}>
          {t.question}
        </div>
        <div style={{ position: "absolute", left: 56, right: 56, top: 190, ...enter(f, 26) }}>
          <Glow on={glow}>
            <Surface style={{ padding: "30px 36px" }}>
              <div style={{ display: "flex", alignItems: "center", gap: 12, fontSize: fs, color: B.muted, marginBottom: 26 }}>
                <SparkTrio size={30} />
                {t.answer}
              </div>
              <div style={{ display: "flex", alignItems: "flex-end", gap: 34, height: 250, borderBottom: `2px solid ${B.line}`, padding: "0 10px" }}>
                {VALUES.map((v, i) => {
                  const p = ramp(f, 40 + i * 12, 40 + i * 12 + 22);
                  return (
                    <div key={i} style={{ flex: 1, display: "flex", flexDirection: "column", alignItems: "center", gap: 8 }}>
                      <span style={{ fontFamily: mono, fontSize: 26, color: B.ink, opacity: p }}>{v}</span>
                      <div style={{ width: "100%", height: (200 * v) / max, borderRadius: "12px 12px 4px 4px", background: i === 1 ? B.forest : B.mint, transformOrigin: "bottom", scale: `1 ${p}` }} />
                    </div>
                  );
                })}
              </div>
              <div style={{ display: "flex", gap: 34, padding: "12px 10px 0" }}>
                {t.topics.map((x, i) => (
                  <span key={i} style={{ flex: 1, textAlign: "center", fontSize: fs - 4, color: B.muted, opacity: ramp(f, 40 + i * 12, 52 + i * 12) }}>{x}</span>
                ))}
              </div>
            </Surface>
          </Glow>
        </div>
      </div>
    </Canvas>
  );
};

// ---------- SiteFeatExport: 4 s, no words (format tags only). One summary splits into PDF, Word, SRT and folds back. ----------
const FORMATS = ["PDF", "DOCX", "SRT"];
export const SiteFeatExport: React.FC = () => {
  const f = useCurrentFrame();
  const out = ramp(f, 14, 40, inOut) * (1 - ramp(f, 100, 119, inOut));
  return (
    <Canvas>
      {FORMATS.map((name, i) => {
        const dx = (i - 1) * 200 * out;
        const dy = 230 * out;
        return (
          <div
            key={name}
            style={{
              position: "absolute",
              left: 360 - 80 + dx,
              top: 170 + dy,
              width: 160,
              height: 200,
              borderRadius: 22,
              background: B.card,
              border: `2px solid ${B.line}`,
              opacity: Math.min(1, out * 3),
              display: "flex",
              flexDirection: "column",
              alignItems: "center",
              justifyContent: "center",
              gap: 16,
              direction: "ltr",
            }}
          >
            <Icon name="pdf" size={60} color={B.forest} />
            <span style={{ fontFamily: mono, fontWeight: 500, fontSize: 28, color: B.forest, padding: "4px 14px", borderRadius: 999, background: B.mintSoft }}>{name}</span>
          </div>
        );
      })}
      {/* the summary itself, still: it is finished content */}
      <Surface style={{ position: "absolute", left: 230, top: 90 - 40 * out, width: 260, height: 320, padding: "34px 30px", display: "flex", flexDirection: "column", gap: 18, direction: "ltr" }}>
        <div style={{ width: "60%", height: 20, borderRadius: 10, background: B.forest }} />
        {[0.95, 0.8, 0.9, 0.7, 0.85, 0.6].map((w, i) => (
          <div key={i} style={{ width: `${w * 100}%`, height: 14, borderRadius: 7, background: B.line }} />
        ))}
      </Surface>
    </Canvas>
  );
};
