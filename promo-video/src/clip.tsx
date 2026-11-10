import React from "react";
import { Sequence, interpolate, staticFile, useCurrentFrame } from "remotion";
import { Video } from "@remotion/media";
import { FPS, T } from "./theme";
import { ease, useSpr } from "./ui";

/**
 * A real screen-recording clip in a floating window: springs in, plays, and fades out.
 * `from`/`dur`/`trimStart` are seconds; the clip file already has a held last frame, so `dur` may exceed it.
 */
export const ClipWindow: React.FC<{
  src: string;
  from: number;
  dur: number;
  width: number;
  aspect: number; // width / height of the clip
  top: number;
  trimStart?: number;
  rate?: number;
  zoom?: [number, number];
  origin?: string;
}> = ({ src, from, dur, width, aspect, top, trimStart = 0, rate = 1, zoom = [1, 1.06], origin = "50% 50%" }) => (
  <Sequence from={T(from)} durationInFrames={T(dur)} layout="none">
    <ClipInner src={src} dur={dur} width={width} aspect={aspect} top={top} trimStart={trimStart} rate={rate} zoom={zoom} origin={origin} />
  </Sequence>
);

const ClipInner: React.FC<{
  src: string;
  dur: number;
  width: number;
  aspect: number;
  top: number;
  trimStart: number;
  rate: number;
  zoom: [number, number];
  origin: string;
}> = ({ src, dur, width, aspect, top, trimStart, rate, zoom, origin }) => {
  const frame = useCurrentFrame();
  const p = useSpr(0);
  const out = interpolate(frame, [T(dur) - 8, T(dur)], [1, 0], { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  const height = width / aspect;
  const z = interpolate(frame, [0, T(dur)], zoom, { easing: ease, extrapolateRight: "clamp" });
  return (
    <div
      style={{
        position: "absolute",
        top,
        left: (1920 - width) / 2,
        width,
        height,
        borderRadius: 28,
        overflow: "hidden",
        background: "#fff",
        boxShadow: "0 60px 140px rgba(0,0,0,0.65), 0 0 0 1px rgba(255,255,255,0.14)",
        opacity: Math.min(1, p * 1.5) * out,
        translate: `0 ${(1 - p) * 40}px`,
        scale: String(0.94 + 0.06 * p),
      }}
    >
      <div style={{ width: "100%", height: "100%", scale: String(z), transformOrigin: origin }}>
        <Video
          src={staticFile(src)}
          muted
          trimBefore={Math.round(trimStart * FPS)}
          playbackRate={rate}
          style={{ width: "100%", height: "100%", objectFit: "cover" }}
        />
      </div>
    </div>
  );
};
