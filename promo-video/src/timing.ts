import type { Lang } from "./i18n";
import { T, TOTAL_SEC } from "./theme";

export type SceneId = "intro" | "live" | "models" | "ai" | "skills" | "outro";
export const SCENE_IDS: SceneId[] = ["intro", "live", "models", "ai", "skills", "outro"];

// Everything below is derived from the narration audio (word timestamps from a local Whisper pass).
// Scene starts are absolute seconds; cues are seconds relative to the scene start.
const starts: Record<Lang, Record<SceneId, number>> = {
  he: { intro: 0, live: 12.2, models: 18.2, ai: 25.8, skills: 38.4, outro: 46.8 },
  en: { intro: 0, live: 13.6, models: 20.2, ai: 27.3, skills: 39.4, outro: 47.6 },
};

/** Sequence `from` and duration in frames. A scene runs 10 frames into the next one for the cross-fade. */
export const seq = (lang: Lang, id: SceneId) => {
  const i = SCENE_IDS.indexOf(id);
  const start = starts[lang][id];
  const next = i === SCENE_IDS.length - 1 ? TOTAL_SEC : starts[lang][SCENE_IDS[i + 1]];
  return { from: T(start), dur: T(next) - T(start) + (i === SCENE_IDS.length - 1 ? 0 : 10) };
};

export const CUES = {
  he: {
    intro: { hookB: 1.3, hookOut: 2.4, name: 2.5, l1: 2.7, src: [4.2, 5.0, 5.6], l3: 6.3, under: 7.4, aud: [9.0, 9.6, 10.2, 10.9] },
    live: { a: 0.3, b: 2.9, c: 4.1, ready: 5.4 },
    models: { names: [1.9, 2.6, 3.6], badges: [4.4, 5.9, 6.5] },
    ai: { key: 0.2, clip: 2.4, prov: [2.8, 3.3, 3.9, 4.6], prompt: 6.8, attach: 8.2, summary: 9.6 },
    skills: { install: 2.8, chat: 5.2, steps: [6.3, 7.0] },
    outro: { name: 0.3, tag: 0.65, sub: 3.2, url: 3.6 },
  },
  en: {
    intro: { hookB: 1.31, hookOut: 2.5, name: 2.7, l1: 2.9, src: [4.4, 5.1, 6.0], l3: 7.0, under: 8.5, aud: [10.0, 10.7, 11.6, 12.5] },
    live: { a: 0.3, b: 2.6, c: 5.0, ready: 5.9 },
    models: { names: [1.8, 2.2, 3.1], badges: [4.3, 5.8, 6.4] },
    ai: { key: 0.2, clip: 2.7, prov: [2.9, 3.6, 4.3, 5.0], prompt: 7.0, attach: 8.5, summary: 9.8 },
    skills: { install: 2.9, chat: 4.9, steps: [6.3, 7.1] },
    outro: { name: 0.36, tag: 0.7, sub: 3.1, url: 3.5 },
  },
};

export type Caption = [start: number, end: number, text: string];

// Burned-in captions (most viewers watch muted). Absolute seconds, from the narration word timings.
export const CAPTIONS: Record<Lang, Caption[]> = {
  he: [
    [0.2, 2.2, "הקלטה נכנסת. סיכום יוצא."],
    [2.7, 4.2, "DIBE מתמלל הקלטות"],
    [4.2, 6.2, "מקובץ, לייב או מקישור"],
    [6.2, 8.6, "ומסכם לפי פרומפט מותאם אישית"],
    [8.9, 12.0, "לסטודנטים, אנשי עסקים, פגישות ועוד"],
    [12.8, 14.9, "מתמללים בלייב, בזמן שמדברים"],
    [15.3, 16.5, "או מקובץ שכבר יש לכם"],
    [16.5, 17.8, "או ישר מקישור"],
    [18.4, 20.1, "בוחרים את המודל שמתאים לכם"],
    [20.1, 22.6, "Whisper · Nemotron · Parakeet"],
    [22.6, 25.3, "הכול רץ על המחשב. בלי אינטרנט"],
    [25.9, 28.0, "מחברים בינה מלאכותית דרך API"],
    [28.6, 32.2, "Gemini · Claude · Ollama · OpenAI"],
    [32.7, 34.0, "כותבים פרומפט משלכם"],
    [34.0, 35.4, "מצרפים קבצים"],
    [35.4, 37.9, "ומקבלים סיכום בדיוק כמו שביקשתם"],
    [38.5, 41.2, "ועם סקילים, מתקינים את DIBE בלחיצה אחת"],
    [41.5, 43.5, "ל-Claude Code או ל-Codex"],
    [43.8, 46.3, "והסוכן מתמלל ומסכם בשבילכם"],
    [47.0, 48.8, "DIBE. תמלול פרטי."],
    [48.8, 51.7, "בלייב. חינם וקוד פתוח."],
  ],
  en: [
    [0.2, 2.3, "Recording in. Summary out."],
    [2.9, 4.3, "DIBE transcribes recordings"],
    [4.3, 6.9, "from a file, live, or a link"],
    [7.0, 9.6, "and summarizes with your own prompt"],
    [10.0, 13.3, "Students, business, meetings & more"],
    [14.1, 16.2, "Transcribe live, as you speak"],
    [16.2, 18.6, "Or from a file you already have"],
    [18.6, 19.9, "Or straight from a link"],
    [20.4, 21.8, "Pick the model that suits you"],
    [22.0, 24.2, "Whisper · Nemotron · Parakeet"],
    [24.4, 27.1, "Runs on your computer. No internet"],
    [27.5, 29.6, "Connect your AI through an API"],
    [30.1, 34.1, "Gemini · Claude · Ollama · OpenAI"],
    [34.5, 36.5, "Write your own prompt, attach files"],
    [36.7, 39.1, "and get the summary you asked for"],
    [39.7, 42.3, "With skills, install DIBE in one click"],
    [42.6, 44.2, "into Claude Code or Codex"],
    [44.3, 47.2, "your agent transcribes & summarizes"],
    [47.9, 50.3, "DIBE. Private transcription, live."],
    [50.7, 52.4, "Free and open source."],
  ],
};
