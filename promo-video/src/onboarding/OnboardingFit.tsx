import React from "react";
import { C } from "../theme";
import { Stage, seg, usePhase, inOut } from "./common";

export const OnboardingFit: React.FC = () => {
  const p = usePhase();
  const TAU = Math.PI * 2;
  const cx = 360, cy = 360, cw = 300, ch = 170; // memory container
  // big block: in .03-.17, shake .17-.42, out .42-.52
  const bigIn = seg(p, 0.03, 0.17);
  const bigOut = seg(p, 0.42, 0.52, inOut);
  const bigX = -260 + 260 * bigIn + (-220) * bigOut;
  const shakeEnv = seg(p, 0.17, 0.2) * (1 - seg(p, 0.34, 0.42));
  const shake = Math.sin(p * TAU * 22) * 9 * shakeEnv;
  const bigVis = 1 - seg(p, 0.46, 0.52);
  const red = Math.min(1, seg(p, 0.15, 0.2) * (1 - seg(p, 0.38, 0.46)));
  // small block: in .52-.64, glow+check .64-.84, fade .92-1 (empty at p=1)
  const smIn = seg(p, 0.52, 0.64);
  const fit = seg(p, 0.64, 0.72);
  const check = seg(p, 0.7, 0.8);
  const fadeEnd = 1 - seg(p, 0.92, 1);
  const smY = 150 * (1 - smIn);
  return (
    <Stage>
      <defs><filter id="blur"><feGaussianBlur stdDeviation="14" /></filter></defs>
      {/* container glow */}
      <rect x={cx - cw / 2} y={cy - ch / 2} width={cw} height={ch} rx="26" fill={C.green} opacity={0.5 * fit * fadeEnd} filter="url(#blur)" />
      <rect x={cx - cw / 2} y={cy - ch / 2} width={cw} height={ch} rx="26" fill="rgba(255,255,255,0.05)"
        stroke={fit > 0.01 ? C.green : red > 0.01 ? C.red : C.glassBorder} strokeOpacity={fit > 0.01 ? fadeEnd : 1} strokeWidth="5" strokeDasharray="16 10" />
      {/* big block (overflows) */}
      <g opacity={bigVis} transform={`translate(${bigX + shake} 0)`}>
        <rect x={cx - 210} y={cy - 70} width="420" height="140" rx="22" fill={C.red} opacity={0.18 + 0.12 * red} />
        <rect x={cx - 210} y={cy - 70} width="420" height="140" rx="22" fill="rgba(255,255,255,0.1)" stroke={red > 0.05 ? C.red : C.glassBorder} strokeWidth="4" />
      </g>
      {/* fitting block */}
      <g opacity={smIn * fadeEnd} transform={`translate(0 ${smY})`}>
        <rect x={cx - 110} y={cy - 55} width="220" height="110" rx="20" fill={C.green} opacity="0.9" />
        <rect x={cx - 110} y={cy - 55} width="220" height="110" rx="20" fill="none" stroke={C.greenSoft} strokeWidth="3" />
      </g>
      <path d="M322 362l28 28 50-60" fill="none" stroke="#fff" strokeWidth="14" strokeLinecap="round" strokeLinejoin="round"
        strokeDasharray="120" strokeDashoffset={120 * (1 - check)} opacity={fadeEnd} />
    </Stage>
  );
};
