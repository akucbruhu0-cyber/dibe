import React from "react";
import { useCurrentFrame } from "remotion";
import { C, COL_W, COL_X } from "../theme";
import { useLang, useT } from "../i18n";
import { CUES, seq } from "../timing";
import { AppCard, Chip, ClipWindow, Glass, Headline, Icon, SceneFrame, SideBars, between, typed, useSpr } from "./shared";

const InstalledBadge: React.FC<{ delay: number; label: string; top: number }> = ({ delay, label, top }) => {
  const p = useSpr(delay, true);
  return (
    <div style={{ position: "absolute", top, left: COL_X + 90, opacity: Math.min(1, p * 2), scale: String(0.7 + 0.3 * p) }}>
      <Glass radius={999} style={{ padding: "10px 24px", display: "flex", alignItems: "center", gap: 10, fontSize: 30, fontWeight: 800, color: "#c4f5ca", background: "rgba(8,22,14,0.96)" }}>
        <Icon name="check" size={32} color={C.green} />
        {label}
      </Glass>
    </div>
  );
};

export const Skills: React.FC = () => {
  const frame = useCurrentFrame();
  const lang = useLang();
  const t = useT().skills;
  const cue = CUES[lang].skills;
  const dur = seq(lang, "skills").dur;
  const clipOp = between(frame, -1, cue.chat, 0.35);
  const chatP = useSpr(cue.chat);

  return (
    <SceneFrame dur={dur}>
      <SideBars energy={0.4} />
      <Headline eyebrow={t.eb} size={80}>{t.h}</Headline>

      <div style={{ opacity: clipOp }}>
        <ClipWindow src="clips/skills.mp4" from={0.2} dur={cue.chat - 0.2} width={780} aspect={1.408} top={290} trimStart={0.3} zoom={[1.0, 1.05]} origin="60% 55%" />
        <InstalledBadge delay={cue.install} label={t.installed} top={720} />
      </div>

      <div style={{ position: "absolute", top: 300, left: COL_X + 20, width: COL_W - 40, opacity: chatP, translate: `0 ${(1 - chatP) * 50}px` }}>
        <AppCard dark title="Claude Code · Codex">
          <div style={{ display: "flex", flexDirection: "column", gap: 18, minHeight: 330 }}>
            <div style={{ alignSelf: "flex-start", maxWidth: "92%", padding: "16px 24px", borderRadius: "24px 24px 24px 6px", background: "#1d3326", fontSize: 30, lineHeight: 1.35 }}>
              {typed(t.user, frame, cue.chat + 0.3, 34)}
            </div>
            {frame >= cue.steps[0] * 30 - 20 && (
              <div style={{ fontSize: 27, color: C.muted, display: "flex", gap: 10, alignItems: "center" }}>
                <Icon name="sparkle" size={28} color={C.orange} />
                {typed(t.step1, frame, cue.steps[0] - 0.7, 36)}
              </div>
            )}
            <div style={{ display: "flex", gap: 12, flexWrap: "wrap" }}>
              <Chip delay={cue.steps[0]} size={28} tone="green" icon="check">{t.step2}</Chip>
              <Chip delay={cue.steps[1]} size={28} tone="green" icon="check">{t.step3}</Chip>
            </div>
          </div>
        </AppCard>
      </div>
    </SceneFrame>
  );
};
