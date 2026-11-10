// Design tokens + timeline constants. Essential content lives in a centered column
// (COL_W) so the 16:9 video can be re-framed to 9:16 / 4:5 by cropping the middle.
export const W = 1920;
export const H = 1080;
export const FPS = 30;
export const COL_W = 760;
export const COL_X = (W - COL_W) / 2;
export const TOTAL_SEC = 54;
export const TOTAL = TOTAL_SEC * FPS;

export const T = (sec: number) => Math.round(sec * FPS);

export const C = {
  bg: "#040a07",
  bg2: "#0c2616",
  green: "#3cb54a",
  greenDark: "#145a1e",
  greenSoft: "#9be3a4",
  orange: "#ffb347",
  orangeSoft: "#ffd27a",
  text: "#f5f7f6",
  muted: "rgba(245,247,246,0.62)",
  glass: "rgba(255,255,255,0.07)",
  glassBorder: "rgba(255,255,255,0.14)",
  card: "#ffffff",
  cardText: "#1a1c1f",
  cardMuted: "#6e6e6e",
  cardSoft: "#f4f4f4",
  border: "#e6e6e6",
  red: "#e5484d",
  blue: "#2563eb",
};

// Apple-style springs: critical damping (no overshoot) for UI, light bounce only for "thrown" objects.
export const SPRING = { damping: 28, stiffness: 200 };
export const SPRING_BOUNCY = { damping: 15, stiffness: 190 };
