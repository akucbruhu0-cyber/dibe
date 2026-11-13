import React from "react";
import { AbsoluteFill, Img, interpolate, spring, staticFile, useCurrentFrame, useVideoConfig } from "remotion";
import { C, SPRING_BOUNCY } from "../theme";
import { SIZE, inOut, out } from "./common";

// 5 s seamless loop. Frame 0 is the logo at rest, so it doubles as the poster and the
// VideoView takes over from the poster without a jump.
export const WELCOME_DUR = 150;

const TAU = Math.PI * 2;
const clamp = { extrapolateLeft: "clamp", extrapolateRight: "clamp" } as const;
const frac = (x: number) => x - Math.floor(x);

// Logo geometry (logo.svg is 512 units, the disc is r=234.5 at 256,256).
const K = 0.72;
const CX = 360;
const CY = 345;
const DISC_R = 234.5 * K;
export const PEACH = "#ffb49a";
export const GOLD = "#ffd35b";

// The three sparkles from logo.svg, with their visual centres for twinkling in place.
export const SPARKLES = [
  {
    cx: 327, cy: 243, cls: "g2", start: 50,
    d: "M258.637 243.187 C258.542 231.533 292.438 227.836 301.251 218.617 C304.107 215.628 306.16 213.191 307.868 209.375 C311.501 201.257 316.402 174.192 327.129 174.326 C337.477 174.456 342.329 201.927 346.12 209.534 C348.05 213.408 351.09 216.715 354.102 219.762 C361.732 227.479 395.655 230.414 395.862 242.408 C396.045 252.964 372.295 257.34 364.596 260.396 C360.261 262.117 356.103 264.597 352.843 267.915 C350.112 270.696 348.007 273.598 346.422 277.166 C343.057 284.738 337.644 311.71 327.956 311.858 C317.108 312.024 312.095 285.38 308.4 277.236 C306.781 273.669 304.401 270.63 301.711 267.872 C292.338 258.265 258.741 255.958 258.637 243.187 Z",
  },
  {
    cx: 395.9, cy: 173.6, cls: "g1", start: 44,
    d: "M361.559 173.787 C361.378 168.084 378.837 165.529 382.783 161.556 C384.631 159.695 386.279 157.154 387.233 154.709 C388.798 150.697 390.184 139.416 395.849 139.338 C401.849 139.255 403.477 156.288 408.401 161.01 C413.189 165.6 430.317 167.633 430.231 173.389 C430.139 179.591 413.777 180.888 408.842 186.028 C407.307 187.627 405.539 189.971 404.718 192.034 C403.194 195.859 401.586 207.849 396.202 207.877 C389.754 207.911 388.217 190.738 383.902 186.461 C378.892 181.496 361.746 179.654 361.559 173.787 Z",
  },
  {
    cx: 434.4, cy: 211.6, cls: "g3", start: 38,
    d: "M417.945 210.447 C419.344 210.295 420.25 209.272 421.536 208.824 C431.358 205.405 428.965 204.592 432.712 195.872 L434.289 195.076 C437.455 196.468 437.276 202.64 441.059 206.192 C442.698 207.731 450.526 209.537 450.83 210.834 C450.924 211.236 450.583 212.164 450.577 212.645 C447.104 213.941 440.31 216.468 438.685 219.801 C437.693 221.837 437.506 224.328 436.402 226.429 C435.475 228.194 434.584 228.267 432.907 227.354 C427.463 215.598 430.954 217.834 417.951 212.816 Z",
  },
];
export const GRADIENTS = { g1: [361.558, 430.232], g2: [258.638, 395.864], g3: [417.944, 450.848] };
export const MIC_STAND =
  "M265.041 332.038 C265.086 357.726 265.042 383.415 265.117 409.104 C265.139 416.808 267.691 432.158 256.11 432.366 C244.16 432.58 246.787 416.946 246.761 409.255 C246.674 383.516 246.756 357.774 246.852 332.036 C240.281 331.042 233.845 329.98 227.482 327.997 C203.164 320.418 181.494 301.813 171.037 278.483 C165.99 267.224 162.66 255.174 162.306 242.777 C162.242 240.52 161.983 238.038 162.472 235.815 C163.296 232.073 167.431 229.398 171.033 229.319 C175.333 229.225 179.106 231.99 180.241 236.187 C180.76 238.106 180.466 240.393 180.543 242.366 C180.648 245.036 180.993 247.755 181.4 250.399 C182.719 258.953 185.86 267.235 189.938 274.872 C205.296 303.636 240.499 319.274 272.148 311.932 C296.176 306.358 316.602 289.475 325.823 266.458 C328.559 259.629 330.192 252.522 331.013 245.22 C331.344 242.277 330.698 238.776 331.628 235.955 C332.972 231.883 336.493 229.092 340.859 229.195 C344.717 229.286 348.606 232.072 349.503 235.936 C349.998 238.065 349.779 240.34 349.726 242.507 C349.418 254.966 346.057 267.152 340.965 278.465 C330.357 302.029 308.827 320.152 284.417 328.027 C278.082 330.071 271.602 331.073 265.041 332.038 Z";
export const MIC_BODY =
  "M204.169 142.27 C204.169 114.303 226.84 91.632 254.807 91.632 C281.034 91.632 302.811 111.571 305.395 137.117 C305.428 137.441 305.445 137.784 305.445 138.145 L305.445 142.27 L305.445 240.149 C305.445 268.116 282.773 290.787 254.807 290.787 C226.84 290.787 204.169 268.116 204.169 240.149 Z";

// A unit 4-point star for the twinkles around the logo.
const STAR = "M0 -1 C0.12 -0.12 0.12 -0.12 1 0 C0.12 0.12 0.12 0.12 0 1 C-0.12 0.12 -0.12 0.12 -1 0 C-0.12 -0.12 -0.12 -0.12 0 -1 Z";
const TWINKLES = [
  { x: 168, y: 170, r: 22, phase: 0.1, color: GOLD },
  { x: 575, y: 135, r: 17, phase: 0.45, color: "#ffffff" },
  { x: 600, y: 470, r: 20, phase: 0.7, color: PEACH },
  { x: 130, y: 430, r: 16, phase: 0.3, color: "#ffffff" },
  { x: 250, y: 585, r: 15, phase: 0.85, color: GOLD },
];

const DOT_COLORS = [C.greenSoft, GOLD, PEACH, C.green, "#ffffff"];
const DOTS: [number, number, number][] = [
  [90, 120, 7], [215, 62, 5], [520, 78, 6], [650, 160, 8], [55, 300, 5], [672, 300, 6], [100, 560, 8],
  [225, 655, 6], [505, 660, 7], [635, 575, 5], [38, 455, 6], [690, 440, 5], [360, 36, 5], [380, 690, 6],
];

/** 1 at the middle of [start, start+len], 0 outside: a single bump. */
const bump = (f: number, start: number, len: number) => {
  const x = interpolate(f, [start, start + len], [0, 1], clamp);
  return Math.sin(Math.PI * x);
};

export const Sparkle: React.FC<{ s: (typeof SPARKLES)[number]; f: number }> = ({ s, f }) => {
  // Main twinkle after the landing, a softer echo later in the loop. A quarter turn of a
  // 4-point star ends where it started.
  const scale = 1 + 0.42 * bump(f, s.start, 16) + 0.18 * bump(f, s.start + 62, 14);
  const turn = 90 * interpolate(f, [s.start, s.start + 16], [0, 1], { ...clamp, easing: inOut });
  return (
    <path
      d={s.d}
      fill={`url(#${s.cls})`}
      stroke="#f7931e"
      strokeWidth={4}
      transform={`translate(${s.cx} ${s.cy}) rotate(${turn}) scale(${scale}) translate(${-s.cx} ${-s.cy})`}
    />
  );
};

export const OnboardingWelcome: React.FC = () => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const p = f / WELCOME_DUR;

  // Hop: crouch (10-20), jump (20-34), land on a bouncy spring, then a happy wiggle.
  const crouch = interpolate(f, [10, 20], [0, 1], { ...clamp, easing: inOut });
  const air = interpolate(f, [20, 34], [0, 1], clamp);
  const lift = f > 20 && f < 34 ? 4 * air * (1 - air) : 0;
  const land = f >= 34 ? 1 - spring({ frame: f - 34, fps, config: SPRING_BOUNCY }) : 0;
  const squash =
    f < 20 ? crouch : f < 34 ? interpolate(air, [0, 0.3, 1], [1, -0.5, 0], { easing: out }) : land;
  const w = interpolate(f, [44, 86], [0, 1], clamp);
  const wiggle = 7 * Math.sin(TAU * 2.5 * w) * (1 - w) ** 2;
  const glow = 0.3 + 0.35 * Math.max(0, land) + 0.15 * lift;

  return (
    <AbsoluteFill style={{ background: `radial-gradient(circle at 50% 45%, ${C.bg2} 0%, ${C.bg} 78%)` }}>
      <svg width={SIZE} height={SIZE} viewBox="0 0 720 720" style={{ position: "absolute", inset: 0 }}>
        <defs>
          <radialGradient id="glow">
            <stop offset="0.55" stopColor={C.green} stopOpacity="0.55" />
            <stop offset="1" stopColor={C.green} stopOpacity="0" />
          </radialGradient>
          <filter id="soft"><feGaussianBlur stdDeviation="7" /></filter>
          <filter id="shadow"><feGaussianBlur stdDeviation="9" /></filter>
        </defs>

        {DOTS.map(([x, y, r], i) => (
          <circle
            key={i}
            cx={x + 6 * Math.sin(TAU * (2 * p + i * 0.37))}
            cy={y + 10 * Math.sin(TAU * (p + i * 0.21))}
            r={r}
            fill={DOT_COLORS[i % DOT_COLORS.length]}
            opacity={0.32 + 0.22 * Math.sin(TAU * (p + i * 0.13))}
          />
        ))}

        <circle cx={CX} cy={CY} r={DISC_R * 1.45} fill="url(#glow)" opacity={glow} />

        {/* Sound waves: three arcs each side, two waves per loop, integer cycles -> seamless. */}
        {[0, 1, 2].map((i) => {
          const q = frac(2 * p + i / 3);
          const r = DISC_R + 22 + 120 * q;
          const a = (38 * Math.PI) / 180;
          const opacity = 0.85 * Math.sin(Math.PI * q) ** 1.5;
          const dx = r * Math.cos(a);
          const dy = r * Math.sin(a);
          const d = `M${CX + dx} ${CY - dy} A${r} ${r} 0 0 1 ${CX + dx} ${CY + dy} M${CX - dx} ${CY - dy} A${r} ${r} 0 0 0 ${CX - dx} ${CY + dy}`;
          return (
            <g key={i} opacity={opacity} fill="none" strokeLinecap="round">
              <path d={d} stroke={C.green} strokeWidth={16} opacity={0.45} filter="url(#soft)" />
              <path d={d} stroke={C.greenSoft} strokeWidth={7 - 3 * q} />
            </g>
          );
        })}

        <ellipse
          cx={CX}
          cy={CY + DISC_R + 26}
          rx={DISC_R * 0.72 * (1 - 0.35 * lift) * (1 + 0.08 * Math.max(0, squash))}
          ry={13}
          fill="#000"
          opacity={0.5 * (1 - 0.5 * lift)}
          filter="url(#shadow)"
        />

        {TWINKLES.map((t, i) => {
          const s = Math.max(0, Math.sin(TAU * (p + t.phase))) ** 4;
          return (
            <path
              key={i}
              d={STAR}
              fill={t.color}
              opacity={s}
              transform={`translate(${t.x} ${t.y}) rotate(${90 * p + i * 20}) scale(${t.r * (0.4 + 0.6 * s)})`}
            />
          );
        })}
      </svg>

      <div
        style={{
          position: "absolute",
          width: 512 * K,
          height: 512 * K,
          left: CX - 256 * K,
          top: CY - 256 * K,
          transformOrigin: "50% 95.8%",
          translate: `0px ${-42 * lift}px`,
          rotate: `${wiggle}deg`,
          scale: `${1 + 0.07 * squash} ${1 - 0.09 * squash}`,
        }}
      >
        <Img
          src={staticFile("logo-disc.png")}
          style={{ position: "absolute", left: 21.504 * K, top: 21.504 * K, width: DISC_R * 2, height: DISC_R * 2, borderRadius: "50%" }}
        />
        <svg viewBox="0 0 512 512" width={512 * K} height={512 * K} style={{ position: "absolute", inset: 0 }}>
          <defs>
            {Object.entries(GRADIENTS).map(([id, [x1, x2]]) => (
              <linearGradient key={id} id={id} x1={x1} y1="0" x2={x2} y2="0" gradientUnits="userSpaceOnUse">
                <stop offset="0" stopColor={GOLD} />
                <stop offset="1" stopColor={PEACH} />
              </linearGradient>
            ))}
          </defs>
          <path d={MIC_STAND} fill="#fff" stroke="#fff" strokeWidth={7} />
          <path d={MIC_BODY} fill="#fff" />
          {SPARKLES.map((s) => <Sparkle key={s.cls} s={s} f={f} />)}
        </svg>
      </div>
    </AbsoluteFill>
  );
};
