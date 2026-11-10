import React from "react";
import { AbsoluteFill, Easing, interpolate, useCurrentFrame } from "remotion";
import { C } from "../theme";

export const SIZE = 720;
export const DUR = 120; // 4 s @ 30 fps, seamless loop

const clamp = { extrapolateLeft: "clamp", extrapolateRight: "clamp" } as const;
export const out = Easing.bezier(0.23, 1, 0.32, 1);
export const inOut = Easing.bezier(0.77, 0, 0.175, 1);

/** Loop phase 0..1 (frame 120 == frame 0). */
export const usePhase = () => useCurrentFrame() / DUR;

/** 0->1 over [a,b] of the loop phase. */
export const seg = (p: number, a: number, b: number, easing = out) =>
  interpolate(p, [a, b], [0, 1], { ...clamp, easing });

export const Stage: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <AbsoluteFill style={{ background: `radial-gradient(circle at 50% 45%, ${C.bg2} 0%, ${C.bg} 78%)` }}>
    <svg width={SIZE} height={SIZE} viewBox="0 0 720 720" style={{ position: "absolute", inset: 0 }}>
      {children}
    </svg>
  </AbsoluteFill>
);
