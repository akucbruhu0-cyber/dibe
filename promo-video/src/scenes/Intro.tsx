import React from "react";
import { AbsoluteFill, Img, interpolate, staticFile, useCurrentFrame } from "remotion";
import { C, W } from "../theme";
import { useLang, useT } from "../i18n";
import { CUES, seq } from "../timing";
import { Chip, Col, SceneFrame, SideBars, Wave, useSpr, useTrack, win } from "./shared";

const rise = (frame: number, at: number) => {
  const p = win(frame, at, at + 0.55);
  return { opacity: p, translate: `0 ${(1 - p) * 30}px` };
};

export const Intro: React.FC = () => {
  const frame = useCurrentFrame();
  const lang = useLang();
  const t = useT().intro;
  const cue = CUES[lang].intro;
  const track = useTrack();
  const k = lang === "he" ? 1 : 0.82; // English copy is wider — keep it inside the column

  // Hook (first 2.4s): two huge lines + live waveform. Frame 0 already shows the first line.
  const hookA = useSpr(0, true);
  const hookB = useSpr(cue.hookB, true);
  const hookOut = win(frame, cue.hookOut - 0.35, cue.hookOut);

  // Logo + name settle in after the hook.
  const logo = useSpr(cue.name, true);
  const nameP = win(frame, cue.name, cue.name + 0.5);
  const underline = win(frame, cue.under, cue.under + 0.6);

  return (
    <SceneFrame dur={seq(lang, "intro").dur}>
      <SideBars energy={0.9} />

      <AbsoluteFill style={{ opacity: 1 - hookOut, translate: `0 ${-hookOut * 70}px`, scale: String(1 - hookOut * 0.08) }}>
        <div style={{ position: "absolute", top: 250, left: 0, width: W, display: "flex", justifyContent: "center", opacity: 0.9 }}>
          <Wave width={760} height={150} active={1.1} />
        </div>
        <div
          style={{
            position: "absolute",
            top: 330,
            left: 0,
            width: W,
            textAlign: "center",
            fontSize: lang === "he" ? 150 : 130,
            fontWeight: 800,
            letterSpacing: track,
            lineHeight: 1.05,
            color: C.text,
          }}
        >
          <div style={{ opacity: Math.min(1, hookA * 2), scale: String(0.9 + 0.1 * hookA) }}>{t.hook[0]}</div>
          <div
            style={{
              opacity: Math.min(1, hookB * 2),
              scale: String(0.9 + 0.1 * hookB),
              background: `linear-gradient(90deg, ${C.orange}, ${C.orangeSoft})`,
              WebkitBackgroundClip: "text",
              color: "transparent",
            }}
          >
            {t.hook[1]}
          </div>
        </div>
      </AbsoluteFill>

      <Img
        src={staticFile("logo.png")}
        style={{ position: "absolute", left: (W - 120) / 2, top: 36, width: 120, height: 120, scale: String(logo), opacity: Math.min(1, logo * 2) }}
      />
      <div
        style={{
          position: "absolute",
          top: 150,
          width: W,
          textAlign: "center",
          fontSize: 124,
          fontWeight: 800,
          letterSpacing: "0.04em",
          direction: "ltr",
          opacity: nameP,
          translate: `0 ${(1 - nameP) * 24}px`,
          background: `linear-gradient(90deg, ${C.text}, ${C.orangeSoft})`,
          WebkitBackgroundClip: "text",
          color: "transparent",
        }}
      >
        DIBE
      </div>

      <Col top={310} gap={22}>
        <div style={{ ...rise(frame, cue.l1), color: C.text, fontSize: 88 * k, fontWeight: 800, letterSpacing: track, textAlign: "center", lineHeight: 1.1 }}>
          {t.l1}
        </div>
        <div style={{ display: "flex", gap: 14, justifyContent: "center", flexWrap: "wrap" }}>
          <Chip delay={cue.src[0]} size={34 * k + 4} tone="green" icon="file">{t.src[0]}</Chip>
          <Chip delay={cue.src[1]} size={34 * k + 4} tone="green" icon="mic">{t.src[1]}</Chip>
          <Chip delay={cue.src[2]} size={34 * k + 4} tone="green" icon="link">{t.src[2]}</Chip>
        </div>
        <div style={{ ...rise(frame, cue.l3), textAlign: "center", marginTop: 10 }}>
          <div style={{ color: C.muted, fontSize: 46 * k, fontWeight: 500 }}>{t.l3a}</div>
          <div style={{ color: C.orange, fontSize: 66 * k, fontWeight: 800, lineHeight: 1.15, whiteSpace: "nowrap", letterSpacing: track }}>{t.l3b}</div>
          <div
            style={{
              height: 7,
              borderRadius: 4,
              margin: "6px auto 0",
              width: `${underline * 100}%`,
              background: `linear-gradient(90deg, ${C.orange}, ${C.orangeSoft})`,
            }}
          />
        </div>
        <div style={{ display: "flex", gap: 12, justifyContent: "center", flexWrap: "wrap", marginTop: 18 }}>
          {t.aud.map((a, i) => (
            <Chip key={a} delay={cue.aud[i]} size={32} tone="dark">{a}</Chip>
          ))}
        </div>
      </Col>
    </SceneFrame>
  );
};
