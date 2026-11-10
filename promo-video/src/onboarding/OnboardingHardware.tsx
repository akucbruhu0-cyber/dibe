import React from "react";
import { C } from "../theme";
import { Stage, seg, usePhase } from "./common";

export const OnboardingHardware: React.FC = () => {
  const p = usePhase();
  const TAU = Math.PI * 2;
  const cx = 360, cy = 270, s = 250;
  const cell = 46, gap = 14;
  const cores = Array.from({ length: 8 }, (_, i) => ({ r: Math.floor(i / 4), c: i % 4, fast: i < 4 }));
  const fade = 1 - seg(p, 0.9, 1);
  const slash = seg(p, 0.12, 0.3) * fade;
  return (
    <Stage>
      <defs>
        <filter id="blur"><feGaussianBlur stdDeviation="8" /></filter>
      </defs>
      <circle cx={cx} cy={cy} r={170} fill={C.green} opacity={0.12 + 0.05 * Math.sin(p * TAU * 2)} filter="url(#blur)" />
      {/* pins */}
      <g stroke={C.glassBorder} strokeWidth="5" strokeLinecap="round">
        {[-80, -27, 27, 80].map((o) => (<path key={o} d={`M${cx + o} ${cy - s / 2}v-26M${cx + o} ${cy + s / 2}v26M${cx - s / 2} ${cy + o}h-26M${cx + s / 2} ${cy + o}h26`} />))}
      </g>
      <rect x={cx - s / 2} y={cy - s / 2} width={s} height={s} rx="34" fill="rgba(255,255,255,0.07)" stroke={C.glassBorder} strokeWidth="3" />
      {cores.map(({ r, c, fast }, i) => {
        const x = cx - (4 * cell + 3 * gap) / 2 + c * (cell + gap);
        const y = cy - (2 * cell + gap) / 2 + r * (cell + gap) - 0;
        const lit = fast ? 0.65 + 0.35 * Math.sin(p * TAU * 2 - i * 0.9) : 0.12;
        return (
          <g key={i}>
            {fast && <rect x={x - 4} y={y - 4} width={cell + 8} height={cell + 8} rx="12" fill={C.green} opacity={lit * 0.5} filter="url(#blur)" />}
            <rect x={x} y={y} width={cell} height={cell} rx="9" fill={fast ? C.green : "rgba(255,255,255,0.4)"} opacity={fast ? 0.35 + lit * 0.65 : lit} />
          </g>
        );
      })}
      {/* RAM bars */}
      <g transform="translate(0 0)">
        {[0, 1, 2, 3, 4].map((i) => {
          const target = [0.9, 0.6, 1, 0.75, 0.5][i];
          const h = 90 * target * seg(p, 0.05 + i * 0.05, 0.35 + i * 0.05) * fade;
          const x = 258 + i * 48;
          return (
            <g key={i}>
              <rect x={x} y={610 - 90} width="30" height="90" rx="8" fill="rgba(255,255,255,0.06)" stroke={C.glassBorder} strokeWidth="2" />
              <rect x={x} y={610 - h} width="30" height={h} rx="8" fill={C.greenSoft} opacity="0.9" />
            </g>
          );
        })}
      </g>
      {/* cloud, crossed out */}
      <g transform="translate(600 100)" opacity={0.85 * (1 - 0.0) * (0.6 + 0.4 * (1 - slash))}>
        <path d="M-40 25h70a26 26 0 0 0 4-52a38 38 0 0 0-72-8a28 28 0 0 0-2 60z" fill="none" stroke={C.muted} strokeWidth="5" strokeLinejoin="round" opacity={1 - 0.6 * slash} />
        <path d="M-52 -42L58 52" stroke={C.red} strokeWidth="7" strokeLinecap="round" strokeDasharray="150" strokeDashoffset={150 * (1 - slash)} />
      </g>
    </Stage>
  );
};
