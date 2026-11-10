import React from "react";
import { COL_W, COL_X } from "../theme";
import { useLang, useT } from "../i18n";
import { CUES, seq } from "../timing";
import { C } from "../theme";
import { Chip, ClipWindow, Glass, Headline, SceneFrame, SideBars, useSpr } from "./shared";

const NAMES = ["Whisper", "Nemotron", "Parakeet TDT v3"];

const NamePill: React.FC<{ name: string; delay: number; featured?: boolean }> = ({ name, delay, featured }) => {
  const p = useSpr(delay, true);
  return (
    <Glass
      radius={999}
      style={{
        padding: "12px 28px",
        fontSize: 34,
        fontWeight: 800,
        direction: "ltr",
        border: `2px solid ${featured ? C.orange : C.glassBorder}`,
        scale: String(0.7 + 0.3 * p),
        opacity: Math.min(1, p * 1.6),
        whiteSpace: "nowrap",
      }}
    >
      {name}
    </Glass>
  );
};

export const Models: React.FC = () => {
  const lang = useLang();
  const t = useT().models;
  const cue = CUES[lang].models;
  const dur = seq(lang, "models").dur;

  return (
    <SceneFrame dur={dur}>
      <SideBars energy={0.5} />
      <Headline eyebrow={t.eb} size={80}>{t.h}</Headline>

      {/* badges sit above the window: they say *why* a local model matters */}
      <div style={{ position: "absolute", top: 206, left: COL_X - 40, width: COL_W + 80, display: "flex", gap: 12, justifyContent: "center" }}>
        <Chip delay={cue.badges[0]} tone="green" size={28} icon="lock">{t.badges[0]}</Chip>
        <Chip delay={cue.badges[1]} tone="green" size={28} icon="wifi-off">{t.badges[1]}</Chip>
        <Chip delay={cue.badges[2]} tone="green" size={28} icon="bolt">{t.badges[2]}</Chip>
      </div>

      <ClipWindow src="clips/models.mp4" from={0.15} dur={dur / 30 - 0.4} width={780} aspect={1.408} top={290} trimStart={1.0} zoom={[1.0, 1.05]} origin="60% 20%" />

      <div style={{ position: "absolute", top: 880, left: COL_X - 100, width: COL_W + 200, display: "flex", gap: 14, justifyContent: "center" }}>
        {NAMES.map((n, i) => (
          <NamePill key={n} name={n} delay={cue.names[i]} featured={i === 0} />
        ))}
      </div>
    </SceneFrame>
  );
};
