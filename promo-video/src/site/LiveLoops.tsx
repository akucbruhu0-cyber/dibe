// Loops built on the rolling live transcript: SiteHero, SiteFeatLive, SiteFeatPhone, SitePrivacy.
import React from "react";
import { Freeze, interpolate, useCurrentFrame } from "remotion";
import { useLang, useT } from "../i18n";
import { Icon } from "../ui";
import { PhoneFrame } from "../gemini-key/parts";
import { B, Canvas, Clock, GlowText, LiveWave, RecDot, RollingTranscript, Surface, ramp, resolveColor, rubik } from "./kit";

const clamp = { extrapolateLeft: "clamp", extrapolateRight: "clamp" } as const;

/** Crossfades into frame 0 over the last `fade` frames, for state that can't cycle (a running clock). */
const Seam: React.FC<{ loop: number; fade?: number; children: React.ReactNode }> = ({ loop, fade = 8, children }) => {
  const f = useCurrentFrame();
  const k = interpolate(f, [loop - fade, loop], [0, 1], clamp);
  return (
    <span style={{ position: "relative", display: "inline-block" }}>
      <span style={{ opacity: 1 - k }}>{children}</span>
      {k > 0 && (
        <span style={{ position: "absolute", inset: 0, opacity: k }}>
          <Freeze frame={0}>{children}</Freeze>
        </span>
      )}
    </span>
  );
};

const Timer: React.FC<{ base: number; size: number }> = ({ base, size }) => {
  const f = useCurrentFrame();
  return <Clock sec={base + f / 30} size={size} />;
};

// ---------- SiteHero: 1440x1080, 8 s. Frame 150 = two finished lines, one pending, 00:00:12. ----------
export const HERO = { w: 1440, h: 1080, dur: 240 };
export const SiteHero: React.FC = () => {
  const lang = useLang();
  const t = useT().live;
  const rowH = 104;
  return (
    <Canvas>
      {/* one soft mint blob, static: only live things move */}
      <div style={{ position: "absolute", width: 900, height: 900, borderRadius: "50%", insetInlineEnd: -260, top: -320, background: `radial-gradient(circle, ${B.mint}, transparent 68%)` }} />
      <Surface radius={36} style={{ position: "absolute", left: 100, right: 100, top: 196, padding: "48px 64px 40px" }}>
        <div style={{ display: "flex", alignItems: "center", gap: 22 }}>
          <RecDot size={26} loop={HERO.dur} />
          <span style={{ fontFamily: rubik, fontWeight: 500, fontSize: 44 }}>{t.recording}</span>
          <span style={{ marginInlineStart: "auto" }}>
            <Seam loop={HERO.dur}>
              <Timer base={7} size={44} />
            </Seam>
          </span>
        </div>
        <div style={{ margin: "34px 0 30px" }}>
          <LiveWave loop={HERO.dur} width={1104} height={120} bars={50} />
        </div>
        <div style={{ height: 2, background: B.line, marginBottom: 30 }} />
        <RollingTranscript lines={t.lines} loop={HERO.dur} offset={50} rowH={rowH} fontSize={lang === "he" ? 48 : 44} width={1104} />
      </Surface>
    </Canvas>
  );
};

// ---------- SiteFeatLive: 720x720, 4 s. Words resolve grey → ink one by one, hold, reset. Poster 90. ----------
export const SiteFeatLive: React.FC = () => {
  const f = useCurrentFrame();
  const lang = useLang();
  const t = useT().live;
  const text = t.lines.join(" ");
  const words = text.split(" ");
  const back = ramp(f, 100, 118);
  return (
    <Canvas>
      <Surface style={{ position: "absolute", inset: 60, padding: "44px 48px" }}>
        <div style={{ display: "flex", alignItems: "center", gap: 16, marginBottom: 30 }}>
          <RecDot size={20} loop={120} />
          <LiveWave loop={120} width={300} height={44} bars={20} />
        </div>
        <div style={{ fontSize: lang === "he" ? 42 : 36, lineHeight: 1.5, fontWeight: 500 }}>
          {words.map((w, i) => {
            const at = 6 + (i * 74) / words.length;
            const p = ramp(f, at, at + 10) * (1 - back);
            const newest = f >= at && f < at + 10;
            return (
              <span key={i} style={{ color: resolveColor(p), marginInlineEnd: "0.28em", display: "inline-block" }}>
                {newest && p < 1 ? <GlowText>{w}</GlowText> : w}
              </span>
            );
          })}
        </div>
      </Surface>
    </Canvas>
  );
};

// ---------- SiteFeatPhone: 720x720, 5 s. The Android phone records, lines appear. Poster 90. ----------
export const SiteFeatPhone: React.FC = () => {
  const t = useT().live;
  const lang = useLang();
  return (
    <Canvas>
      <div style={{ position: "absolute", left: 150, bottom: 0 }}>
        <PhoneFrame w={420} h={640}>
          <div style={{ padding: "18px 30px", direction: lang === "he" ? "rtl" : "ltr" }}>
            <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
              <RecDot size={16} loop={150} />
              <span style={{ fontFamily: rubik, fontWeight: 500, fontSize: 26, color: B.ink }}>{t.recording}</span>
            </div>
            <div style={{ margin: "22px 0" }}>
              <LiveWave loop={150} width={316} height={70} bars={22} />
            </div>
            <RollingTranscript lines={t.lines} loop={150} offset={40} rowH={lang === "he" ? 104 : 124} fontSize={lang === "he" ? 27 : 25} width={316} />
          </div>
        </PhoneFrame>
      </div>
    </Canvas>
  );
};

// ---------- SitePrivacy: 900x900, 7 s. Airplane mode on, Wi-Fi off, transcript keeps writing; lock on the device. ----------
export const SitePrivacy: React.FC = () => {
  const t = useT().site;
  return (
    <Canvas>
      <div style={{ position: "absolute", left: 190, bottom: 0 }}>
        <PhoneFrame w={520} h={800}>
          <div style={{ padding: "8px 34px 0" }}>
            {/* network state row: the only words in this clip */}
            <div style={{ display: "flex", alignItems: "center", gap: 14, padding: "12px 20px", borderRadius: 999, background: B.mintSoft, border: `2px solid ${B.line}`, fontFamily: rubik, fontWeight: 500, fontSize: 28, color: B.ink }}>
              <Plane size={32} />
              <span>{t.airplane}</span>
              <span style={{ marginInlineStart: "auto", display: "flex", color: B.muted }}>
                <Icon name="wifi-off" size={32} color={B.muted} />
              </span>
            </div>
            <div style={{ display: "flex", alignItems: "center", gap: 14, margin: "34px 0 18px" }}>
              <RecDot size={18} loop={210} />
              <LiveWave loop={210} width={360} height={60} bars={24} />
            </div>
            <RollingTranscript lines={["a", "b", "c"]} bars loop={210} offset={30} rowH={66} rows={6} fontSize={34} width={440} />
          </div>
        </PhoneFrame>
      </div>
      {/* the lock sits on the device, still: privacy is a state, not an event */}
      <div style={{ position: "absolute", left: 640, top: 92, width: 96, height: 96, borderRadius: "50%", background: B.forest, display: "flex", alignItems: "center", justifyContent: "center" }}>
        <Icon name="lock" size={50} color="#fff" />
      </div>
    </Canvas>
  );
};

const Plane: React.FC<{ size: number }> = ({ size }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill={B.forest}>
    <path d="M21 16v-2l-8-5V3.5a1.5 1.5 0 0 0-3 0V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5z" />
  </svg>
);
