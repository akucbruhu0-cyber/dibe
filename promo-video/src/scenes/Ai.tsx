import React from "react";
import { useCurrentFrame } from "remotion";
import { C, COL_W, COL_X, T } from "../theme";
import { useLang, useT } from "../i18n";
import { CUES, seq } from "../timing";
import { Chip, ClipWindow, Glass, Headline, Icon, SceneFrame, SideBars, between, typed, useSpr } from "./shared";

/** Before the real clip arrives: an "API key" moment that sets up the idea. */
const KeyCard: React.FC<{ delay: number; until: number }> = ({ delay, until }) => {
  const frame = useCurrentFrame();
  const t = useT().ai;
  const p = useSpr(delay);
  const op = between(frame, delay - 0.2, until, 0.3);
  return (
    <div style={{ position: "absolute", top: 340, left: COL_X, width: COL_W, opacity: op * p, translate: `0 ${(1 - p) * 40}px` }}>
      <Glass radius={38} style={{ padding: 44 }}>
        <div style={{ display: "flex", alignItems: "center", gap: 18, fontSize: 40, fontWeight: 800, marginBottom: 22 }}>
          <div style={{ width: 70, height: 70, borderRadius: 20, background: `linear-gradient(135deg, ${C.green}, ${C.greenDark})`, display: "flex", alignItems: "center", justifyContent: "center", fontSize: 28, direction: "ltr" }}>API</div>
          {t.apiKey}
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 14, padding: "20px 24px", borderRadius: 18, background: "rgba(0,0,0,0.35)", border: `1px solid ${C.glassBorder}`, fontSize: 36, direction: "ltr", letterSpacing: 6 }}>
          <Icon name="key" size={36} color={C.orange} />
          <span>{typed("••••••••••••••", frame, delay + 0.5, 18)}</span>
          {frame >= T(delay + 1.9) && <span style={{ marginInlineStart: "auto" }}><Icon name="check" size={40} color={C.green} /></span>}
        </div>
      </Glass>
    </div>
  );
};

const FileChip: React.FC<{ name: string; delay: number; i: number }> = ({ name, delay, i }) => {
  const p = useSpr(delay, true);
  return (
    <div
      style={{
        opacity: Math.min(1, p * 2),
        translate: `0 ${(1 - p) * 120}px`,
        rotate: `${(1 - p) * (i - 1) * 8}deg`,
      }}
    >
      <Glass radius={20} style={{ padding: "12px 22px", display: "flex", alignItems: "center", gap: 10, fontSize: 28, fontWeight: 700, direction: "ltr" }}>
        <Icon name={name.endsWith("pdf") ? "pdf" : "file"} size={30} color={C.orange} />
        {name}
      </Glass>
    </div>
  );
};

export const Ai: React.FC = () => {
  const frame = useCurrentFrame();
  const lang = useLang();
  const t = useT().ai;
  const cue = CUES[lang].ai;
  const dur = seq(lang, "ai").dur;
  const sec = dur / 30;
  const h1 = between(frame, -1, cue.prompt - 0.2, 0.35);
  const h2 = between(frame, cue.prompt - 0.2, sec + 1, 0.35);
  const badge = useSpr(cue.summary + 0.7, true);

  return (
    <SceneFrame dur={dur}>
      <SideBars energy={0.4} />
      <Headline eyebrow={t.eb1} opacity={h1} size={78}>{t.h1}</Headline>
      <Headline eyebrow={t.eb2} opacity={h2} size={66}>{t.h2}</Headline>

      <KeyCard delay={cue.key} until={cue.clip + 0.2} />

      {/* real clip 1: the provider list (Gemini · Claude · Ollama · OpenAI-compatible) */}
      <ClipWindow src="clips/ai.mp4" from={cue.clip} dur={cue.prompt - cue.clip - 0.1} width={660} aspect={1.126} top={272} trimStart={0.5} zoom={[1.0, 1.04]} origin="75% 60%" />
      <div style={{ position: "absolute", top: 884, left: COL_X - 130, width: COL_W + 260, display: "flex", gap: 12, justifyContent: "center", opacity: between(frame, cue.prov[0] - 0.1, cue.prompt - 0.1, 0.3) }}>
        {t.providers.map((p, i) => (
          <Chip key={p} delay={cue.prov[i]} size={28} tone="dark" active={i === 0} icon={i === 0 ? "sparkle" : undefined}>{p}</Chip>
        ))}
      </div>

      {/* real clip 2: custom prompt + "attach files", then the summary */}
      <ClipWindow src="clips/prompt.mp4" from={cue.prompt} dur={cue.summary - cue.prompt - 0.1} width={700} aspect={1.283} top={290} rate={0.75} zoom={[1.0, 1.08]} origin="50% 60%" />
      <div style={{ position: "absolute", top: 864, left: COL_X - 100, width: COL_W + 200, display: "flex", gap: 12, justifyContent: "center", opacity: between(frame, cue.attach - 0.1, cue.summary - 0.1, 0.3) }}>
        {t.files.map((f, i) => (
          <FileChip key={f} name={f} delay={cue.attach + i * 0.28} i={i} />
        ))}
      </div>

      <ClipWindow src="clips/summary.mp4" from={cue.summary} dur={sec - cue.summary - 0.2} width={780} aspect={1.385} top={290} rate={0.7} zoom={[1.0, 1.05]} origin="50% 20%" />
      <div style={{ position: "absolute", top: 884, left: 0, width: 1920, display: "flex", justifyContent: "center", opacity: Math.min(1, badge * 2), scale: String(0.8 + 0.2 * badge) }}>
        <Chip delay={cue.summary + 0.7} tone="orange" size={32} icon="sparkle">{t.summary}</Chip>
      </div>
    </SceneFrame>
  );
};
