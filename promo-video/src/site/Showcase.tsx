// Showcase scenes (1920x1080, play once, scrubbed by scroll): SiteShow1/2/3. Plus SiteWordmark.
import React from "react";
import { AbsoluteFill, Img, staticFile, useCurrentFrame } from "remotion";
import { useLang, useT } from "../i18n";
import { Icon } from "../ui";
import { SPARKLES, GRADIENTS, MIC_BODY, MIC_STAND, GOLD, PEACH, Sparkle } from "../onboarding/OnboardingWelcome";
import { B, Canvas, Clock, Glow, GlowText, LiveWave, Pill, RecDot, SETTLE, SparkTrio, Surface, enter, inOut, mono, press, ramp, resolveColor, rubik } from "./kit";

/** Desktop app window: title bar (traffic lights stay LTR) + body. */
const Window: React.FC<{ w: number; h: number; title: string; children: React.ReactNode }> = ({ w, h, title, children }) => (
  <Surface radius={36} style={{ position: "absolute", left: (1920 - w) / 2, top: (1080 - h) / 2, width: w, height: h, overflow: "hidden" }}>
    <div style={{ height: 76, display: "flex", alignItems: "center", padding: "0 30px", gap: 12, background: B.mintSoft, borderBottom: `2px solid ${B.line}`, direction: "ltr" }}>
      {["#C9D9CD", "#C9D9CD", "#C9D9CD"].map((c, i) => <div key={i} style={{ width: 18, height: 18, borderRadius: 9, background: c }} />)}
      <div style={{ flex: 1, textAlign: "center", fontFamily: rubik, fontWeight: 500, fontSize: 30, color: B.muted, marginRight: 80 }}>{title}</div>
    </div>
    {children}
  </Surface>
);

// ---------- SiteShow1: 12 s. Record → live words → stop → summary appears. Poster at 85% (frame 306). ----------
export const SiteShow1: React.FC = () => {
  const f = useCurrentFrame();
  const lang = useLang();
  const live = useT().live;
  const t = useT().site;
  const recAt = 20;
  const stopAt = 206;
  const sumAt = 226;
  const done = 296;
  const recording = f >= recAt && f < stopAt;
  const waveOn = f < stopAt ? ramp(f, recAt, recAt + 8) : 1 - ramp(f, stopAt, stopAt + 10);
  const lines = [...live.lines, live.tail];
  const words = lines.map((l) => l.split(" "));
  const total = words.reduce((n, w) => n + w.length, 0);
  const wordAt = (gi: number) => 34 + (gi * 160) / total; // all words land between 34 and 194
  const starts = words.map((_, i) => words.slice(0, i).reduce((n, w) => n + w.length, 0));
  const glow = f < sumAt + 4 ? 0 : ramp(f, sumAt + 4, sumAt + 14) * (1 - ramp(f, done, done + SETTLE));
  const fs = lang === "he" ? 38 : 34;
  return (
    <Canvas>
      <Window w={1660} h={800} title="DIBE">
        <div style={{ display: "flex", height: 724 }}>
          {/* transcript column */}
          <div style={{ flex: 1, padding: "40px 52px" }}>
            <div style={{ display: "flex", alignItems: "center", gap: 20, height: 70 }}>
              {f < recAt + 4 ? (
                <Pill primary size={30} style={{ scale: String(press(f, recAt)) }}>
                  <span style={{ width: 18, height: 18, borderRadius: 9, background: "#fff" }} />
                  {t.record}
                </Pill>
              ) : recording ? (
                <>
                  <RecDot size={24} loop={42} />
                  <span style={{ fontFamily: rubik, fontWeight: 500, fontSize: 36 }}>{live.recording}</span>
                  <Clock sec={(f - recAt) / 30} size={36} />
                  <Pill size={28} style={{ marginInlineStart: "auto", scale: String(press(f, stopAt - 4)) }}>{t.stop}</Pill>
                </>
              ) : (
                <>
                  <Icon name="check" size={40} color={B.forest} />
                  <span style={{ fontFamily: rubik, fontWeight: 500, fontSize: 36, ...enter(f, stopAt) }}>{t.saved}</span>
                  <Clock sec={(stopAt - recAt) / 30} size={36} color={B.muted} />
                </>
              )}
            </div>
            <div style={{ margin: "26px 0 24px" }}>
              <LiveWave loop={84} width={1000} height={100} bars={46} active={waveOn} color={waveOn > 0.05 ? B.forest : B.line} />
            </div>
            <div style={{ height: 2, background: B.line, marginBottom: 26 }} />
            {words.map((ws, li) => {
              const first = starts[li];
              const lineEnd = li < lines.length - 1 ? wordAt(starts[li + 1]) : stopAt; // a line is finished once the next one starts
              return (
                <div key={li} style={{ fontSize: fs, lineHeight: 1.5, marginBottom: 14 }}>
                  {ws.map((w, wi) => {
                    const at = wordAt(first + wi);
                    if (f < at) return null;
                    return (
                      <span key={wi} style={{ display: "inline-block", marginInlineEnd: "0.28em", color: resolveColor(ramp(f, lineEnd, lineEnd + 8)), ...enter(f, at) }}>
                        {f < at + 8 && f < stopAt ? <GlowText>{w}</GlowText> : w}
                      </span>
                    );
                  })}
                </div>
              );
            })}
          </div>
          {/* summary column */}
          <div style={{ width: 600, borderInlineStart: `2px solid ${B.line}`, background: B.paper, padding: "40px 40px" }}>
            {f < sumAt + 4 ? (
              <div style={{ opacity: f < stopAt ? 0.45 : 1 }}>
                <Pill size={30} style={{ scale: String(press(f, sumAt)) }}>
                  <SparkTrio size={34} />
                  {t.summarize}
                </Pill>
              </div>
            ) : (
              <Glow on={glow} style={enter(f, sumAt + 4)}>
                <Surface style={{ padding: "30px 32px", minHeight: 520 }}>
                  <div style={{ fontFamily: rubik, fontWeight: 700, fontSize: 36, marginBottom: 22 }}>{f < done ? t.summarizing : t.summaryTitle}</div>
                  {t.bullets.map((b, i) => (
                    <div key={i} style={{ display: "flex", gap: 14, fontSize: lang === "he" ? 31 : 28, lineHeight: 1.55, marginBottom: 16, ...enter(f, sumAt + 20 + i * 22) }}>
                      <span style={{ width: 10, height: 10, borderRadius: 5, background: B.forest, marginTop: 20, flexShrink: 0 }} />
                      <span>{b}</span>
                    </div>
                  ))}
                </Surface>
              </Glow>
            )}
          </div>
        </div>
      </Window>
    </Canvas>
  );
};

// ---------- SiteShow2: 8 s. Model list, one is picked, it downloads, ready. Poster last frame. ----------
const MODELS = ["Whisper", "Nemotron", "Parakeet TDT v3"];
export const SiteShow2: React.FC = () => {
  const f = useCurrentFrame();
  const lang = useLang();
  const t = useT();
  const pick = 2;
  const pickAt = 30;
  const prog = ramp(f, 44, 176, inOut);
  const readyAt = 180;
  return (
    <Canvas>
      <Window w={1400} h={620} title={t.site.models}>
        <div style={{ padding: "44px 56px", display: "flex", flexDirection: "column", gap: 26 }}>
          {MODELS.map((name, i) => {
            const picked = i === pick && f >= pickAt;
            return (
              <div
                key={name}
                style={{
                  display: "flex",
                  alignItems: "center",
                  gap: 24,
                  padding: "30px 36px",
                  borderRadius: 22,
                  border: `2px solid ${picked ? B.forest : B.line}`,
                  background: picked ? B.mintSoft : B.card,
                  ...enter(f, i * 5),
                }}
              >
                <span style={{ fontFamily: rubik, fontWeight: 700, fontSize: 40, direction: "ltr", unicodeBidi: "isolate" }}>{name}</span>
                <div style={{ display: "flex", gap: 10 }}>
                  {t.models.badges.slice(0, i === 2 ? 3 : 2).map((b) => (
                    <span key={b} style={{ fontFamily: rubik, fontSize: 24, padding: "6px 16px", borderRadius: 999, background: B.mintSoft, color: B.forest, border: `1.5px solid ${B.line}` }}>{b}</span>
                  ))}
                </div>
                <div style={{ marginInlineStart: "auto", width: 340, display: "flex", justifyContent: "flex-end" }}>
                  {i !== pick || f < pickAt + 10 ? (
                    <Pill size={28} primary={i === pick} style={{ scale: String(i === pick ? press(f, pickAt) : 1) }}>{t.site.download}</Pill>
                  ) : f < readyAt ? (
                    <div style={{ width: 340, ...enter(f, pickAt + 10) }}>
                      <div style={{ display: "flex", justifyContent: "space-between", fontSize: 26, color: B.muted, marginBottom: 10 }}>
                        <span>{t.site.downloading}</span>
                        <span style={{ fontFamily: mono, direction: "ltr" }}>{Math.round(prog * 100)}%</span>
                      </div>
                      <div style={{ height: 14, borderRadius: 7, background: B.line, overflow: "hidden" }}>
                        <div style={{ height: "100%", width: "100%", background: B.forest, borderRadius: 7, transformOrigin: lang === "he" ? "right" : "left", scale: `${prog} 1` }} />
                      </div>
                    </div>
                  ) : (
                    <div style={{ display: "flex", alignItems: "center", gap: 12, fontFamily: rubik, fontWeight: 700, fontSize: 34, color: B.forest, ...enter(f, readyAt) }}>
                      <Icon name="check" size={44} color={B.forest} />
                      {t.site.ready}
                    </div>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      </Window>
    </Canvas>
  );
};

// ---------- SiteShow3: 10 s. One question → an answer with a timeline and a table. Poster last frame. ----------
const STAMPS = ["00:01:10", "00:12:40", "00:31:05"];
export const SiteShow3: React.FC = () => {
  const f = useCurrentFrame();
  const lang = useLang();
  const t = useT().site;
  const q = t.question3;
  const typedN = Math.floor(ramp(f, 8, 56, (x) => x) * q.length);
  const sent = 64;
  const ansAt = 80;
  const done = 220;
  const glow = f < ansAt ? 0 : ramp(f, ansAt, ansAt + 10) * (1 - ramp(f, done, done + SETTLE));
  const line = ramp(f, 100, 150, inOut);
  const fs = lang === "he" ? 32 : 30;
  return (
    <Canvas>
      <Window w={1500} h={980} title="DIBE">
        <div style={{ position: "relative", height: 904, padding: "34px 56px" }}>
          {f >= sent && (
            <div style={{ display: "flex", justifyContent: "flex-end", ...enter(f, sent) }}>
              <div style={{ padding: "20px 30px", borderRadius: 22, borderEndEndRadius: 8, background: B.mint, fontSize: fs + 2 }}>{q}</div>
            </div>
          )}
          {f >= ansAt && (
            <Glow on={glow} style={{ marginTop: 20, width: 1180, ...enter(f, ansAt) }}>
              <Surface style={{ padding: "32px 40px" }}>
                <div style={{ display: "flex", alignItems: "center", gap: 14, fontSize: fs, color: B.muted, marginBottom: 34 }}>
                  <SparkTrio size={34} />
                  {t.answer3}
                </div>
                {/* timeline: the line draws, then the three moments land on it */}
                <div style={{ position: "relative", height: 120, margin: "0 30px" }}>
                  <div style={{ position: "absolute", top: 18, left: 0, right: 0, height: 4, borderRadius: 2, background: B.line, transformOrigin: lang === "he" ? "right" : "left", scale: `${line} 1` }} />
                  {STAMPS.map((s, i) => {
                    const pos = [0.06, 0.45, 0.88][i];
                    return (
                      <div key={s} style={{ position: "absolute", insetInlineStart: `${pos * 100}%`, top: 0, translate: lang === "he" ? "50% 0" : "-50% 0", display: "flex", flexDirection: "column", alignItems: "center", gap: 10, ...enter(f, 120 + i * 14) }}>
                        <div style={{ width: 40, height: 40, borderRadius: 20, background: B.card, border: `6px solid ${B.forest}` }} />
                        <span style={{ fontFamily: mono, fontSize: 24, color: B.muted, direction: "ltr" }}>{s}</span>
                        <span style={{ fontFamily: rubik, fontWeight: 500, fontSize: 28, whiteSpace: "nowrap" }}>{t.topics[i]}</span>
                      </div>
                    );
                  })}
                </div>
                {/* table */}
                <div style={{ marginTop: 26, border: `2px solid ${B.line}`, borderRadius: 12, overflow: "hidden", fontSize: fs - 2 }}>
                  <div style={{ display: "flex", background: B.mintSoft, fontFamily: rubik, fontWeight: 500, ...enter(f, 160) }}>
                    <span style={{ flex: 1, padding: "14px 24px" }}>{t.colTopic}</span>
                    <span style={{ width: 260, padding: "14px 24px" }}>{t.colTime}</span>
                  </div>
                  {t.topics.map((x, i) => (
                    <div key={x} style={{ display: "flex", borderTop: `2px solid ${B.line}`, ...enter(f, 172 + i * 12) }}>
                      <span style={{ flex: 1, padding: "14px 24px" }}>{x}</span>
                      <span style={{ width: 260, padding: "14px 24px", fontFamily: mono, direction: "ltr", textAlign: lang === "he" ? "right" : "left" }}>{STAMPS[i]}</span>
                    </div>
                  ))}
                </div>
              </Surface>
            </Glow>
          )}
          {/* composer at the bottom */}
          <Surface radius={999} style={{ position: "absolute", left: 56, right: 56, bottom: 30, height: 84, display: "flex", alignItems: "center", padding: "0 34px", fontSize: fs }}>
            <span>{f < sent ? q.slice(0, typedN) : ""}</span>
            {f < sent && <span style={{ width: 3, height: 40, background: B.forest, marginInlineStart: 4 }} />}
          </Surface>
        </div>
      </Window>
    </Canvas>
  );
};

// ---------- SiteWordmark: 256x256, transparent, 1.2 s one-shot. The three logo stars twinkle 1 / 0.5 / 0.3. ----------
export const SiteWordmark: React.FC = () => {
  const f = useCurrentFrame();
  const starts = [2, 8, 14]; // big, mid, small
  const order = ["g2", "g1", "g3"];
  return (
    <AbsoluteFill>
      <Img src={staticFile("logo-disc.png")} style={{ position: "absolute", left: 10.75, top: 10.75, width: 234.5, height: 234.5, borderRadius: "50%" }} />
      <svg viewBox="0 0 512 512" width={256} height={256} style={{ position: "absolute", inset: 0 }}>
        <defs>
          {Object.entries(GRADIENTS).map(([id, [x1, x2]]) => (
            <linearGradient key={id} id={id} x1={x1} y1="0" x2={x2} y2="0" gradientUnits="userSpaceOnUse">
              <stop offset="0" stopColor={GOLD} />
              <stop offset="1" stopColor={PEACH} />
            </linearGradient>
          ))}
        </defs>
        <path d={MIC_STAND} fill="#fff" stroke="#fff" strokeWidth={7} />
        <path d={MIC_BODY} fill="#fff" />
        {SPARKLES.map((s) => <Sparkle key={s.cls} s={{ ...s, start: starts[order.indexOf(s.cls)] }} f={f} />)}
      </svg>
    </AbsoluteFill>
  );
};
