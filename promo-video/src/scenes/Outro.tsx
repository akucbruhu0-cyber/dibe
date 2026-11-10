import React from "react";
import { AbsoluteFill, Img, staticFile, useCurrentFrame } from "remotion";
import { C, W } from "../theme";
import { useLang, useT } from "../i18n";
import { CUES, seq } from "../timing";
import { Chip, SceneFrame, SideBars, Wave, useSpr, useTrack, win } from "./shared";

export const Outro: React.FC = () => {
  const frame = useCurrentFrame();
  const lang = useLang();
  const t = useT().outro;
  const cue = CUES[lang].outro;
  const track = useTrack();
  const k = lang === "he" ? 1 : 0.86;
  const logo = useSpr(0.05, true);
  const tag = win(frame, cue.tag, cue.tag + 0.6);

  return (
    <SceneFrame dur={seq(lang, "outro").dur}>
      <SideBars energy={0.8} />
      <AbsoluteFill style={{ background: `radial-gradient(540px 540px at 50% 330px, ${C.green}48, transparent 70%)` }} />
      <Img src={staticFile("logo.png")} style={{ position: "absolute", left: (W - 270) / 2, top: 150, width: 270, height: 270, scale: String(logo) }} />
      <div
        style={{
          position: "absolute",
          top: 440,
          width: W,
          textAlign: "center",
          fontSize: 150,
          fontWeight: 800,
          letterSpacing: "0.04em",
          direction: "ltr",
          opacity: win(frame, cue.name, cue.name + 0.5),
          background: `linear-gradient(90deg, ${C.text}, ${C.orangeSoft})`,
          WebkitBackgroundClip: "text",
          color: "transparent",
        }}
      >
        DIBE
      </div>
      <div
        style={{
          position: "absolute",
          top: 640,
          width: W,
          textAlign: "center",
          color: C.text,
          fontSize: 64 * k,
          fontWeight: 700,
          letterSpacing: track,
          opacity: tag,
          translate: `0 ${(1 - tag) * 24}px`,
        }}
      >
        {t.tag}
      </div>
      <div style={{ position: "absolute", top: 750, width: W, display: "flex", justifyContent: "center" }}>
        <Chip delay={cue.sub} tone="orange" size={36}>{t.sub}</Chip>
      </div>
      <div style={{ position: "absolute", top: 860, width: W, textAlign: "center", color: C.muted, fontSize: 30, direction: "ltr", opacity: win(frame, cue.url, cue.url + 0.5) }}>
        github.com/akucbruhu0-cyber/dibe
      </div>
      <div style={{ position: "absolute", top: 940, width: W, display: "flex", justifyContent: "center", opacity: 0.55 * win(frame, cue.url, cue.url + 0.8) }}>
        <Wave width={420} height={50} active={0.9} />
      </div>
    </SceneFrame>
  );
};
