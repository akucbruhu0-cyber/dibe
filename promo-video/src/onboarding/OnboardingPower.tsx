import React from "react";
import { C } from "../theme";
import { Stage, seg, usePhase, inOut } from "./common";

/** `laptop` is the desktop app's version: same loop, a laptop instead of a phone. */
export const OnboardingPower: React.FC<{ device?: "phone" | "laptop" }> = ({ device = "phone" }) => {
  const p = usePhase();
  const TAU = Math.PI * 2;
  // drain 100% -> 28% over p .08..80, refill .86..1 (so p=1 == p=0)
  const drain = seg(p, 0.08, 0.8, (x) => x);
  const refill = seg(p, 0.86, 1, inOut);
  const level = 1 - 0.72 * drain * (1 - refill) - 0 * refill;
  const heat = drain * (1 - refill);
  // sound wave inside phone: integer cycles per loop -> seamless
  const pts: string[] = [];
  for (let i = 0; i <= 60; i++) {
    const x = i / 60;
    const env = Math.sin(Math.PI * x);
    const y = env * (Math.sin(x * TAU * 3 - p * TAU * 2) * 0.7 + Math.sin(x * TAU * 5 + p * TAU * 3) * 0.3);
    pts.push(`${i ? "L" : "M"}${(250 + x * 150).toFixed(1)} ${(360 + y * 70).toFixed(1)}`);
  }
  const d = pts.join(" ");
  const bh = 280, by = 220;
  const bx = device === "laptop" ? 524 : 496;
  return (
    <Stage>
      <defs>
        <radialGradient id="heat"><stop offset="0" stopColor={C.orange} stopOpacity="0.75" /><stop offset="1" stopColor={C.orange} stopOpacity="0" /></radialGradient>
        <filter id="blur"><feGaussianBlur stdDeviation="6" /></filter>
        <linearGradient id="bat" x1="0" y1="1" x2="0" y2="0"><stop offset="0" stopColor={C.greenDark} /><stop offset="1" stopColor={C.green} /></linearGradient>
      </defs>
      <circle cx="320" cy="360" r={230} fill="url(#heat)" opacity={heat * 0.8} />
      {device === "phone" ? (
        <>
          <rect x="210" y="190" width="220" height="340" rx="38" fill="rgba(255,255,255,0.06)" stroke={C.glassBorder} strokeWidth="3" />
          <rect x="290" y="205" width="60" height="9" rx="4.5" fill="rgba(255,255,255,0.18)" />
        </>
      ) : (
        <>
          {/* screen, then the keyboard deck */}
          <rect x="150" y="205" width="340" height="240" rx="20" fill="rgba(255,255,255,0.06)" stroke={C.glassBorder} strokeWidth="3" />
          <path d="M112 456h416l-26 26H138z" fill="rgba(255,255,255,0.08)" stroke={C.glassBorder} strokeWidth="3" strokeLinejoin="round" />
          <rect x="290" y="456" width="60" height="7" rx="3.5" fill="rgba(255,255,255,0.18)" />
        </>
      )}
      {/* chip glyph */}
      <g transform="translate(320 275)" stroke={C.greenSoft} strokeWidth="3" fill="none" strokeLinecap="round">
        <rect x="-26" y="-26" width="52" height="52" rx="10" />
        <rect x="-10" y="-10" width="20" height="20" rx="4" fill={C.green} stroke="none" opacity={0.7 + 0.3 * Math.sin(p * TAU * 4)} />
        {[-14, 0, 14].map((o) => (<g key={o}><path d={`M${o} -26v-10M${o} 26v10M-26 ${o}h-10M26 ${o}h10`} /></g>))}
      </g>
      <path d={d} fill="none" stroke={C.green} strokeWidth="14" opacity="0.35" filter="url(#blur)" strokeLinecap="round" />
      <path d={d} fill="none" stroke={C.greenSoft} strokeWidth="5" strokeLinecap="round" strokeLinejoin="round" />
      {/* battery */}
      <rect x={bx} y={by} width="76" height={bh} rx="18" fill="rgba(255,255,255,0.06)" stroke={C.glassBorder} strokeWidth="3" />
      <rect x={bx + 24} y={by - 14} width="28" height="12" rx="4" fill={C.glassBorder} />
      <clipPath id="bc"><rect x={bx + 10} y={by + 10} width="56" height={bh - 20} rx="10" /></clipPath>
      <g clipPath="url(#bc)">
        <rect x={bx + 10} y={by + 10 + (bh - 20) * (1 - level)} width="56" height={(bh - 20) * level + 1} fill={heat > 0.55 ? C.orange : "url(#bat)"} opacity={heat > 0.55 ? 0.9 : 1} />
      </g>
      <rect x={bx + 10} y={by + 10 + (bh - 20) * (1 - level)} width="56" height={(bh - 20) * level} rx="10" fill={C.orange} opacity={heat * 0.8} clipPath="url(#bc)" />
    </Stage>
  );
};
