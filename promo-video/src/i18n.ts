import { createContext, useContext } from "react";

export type Lang = "he" | "en";

export const LangContext = createContext<Lang>("he");
export const useLang = () => useContext(LangContext);
export const useDir = () => (useLang() === "he" ? "rtl" : "ltr");

// On-screen copy. The spoken narration is in narration.json (what was sent to Gemini TTS).
const he = {
  intro: {
    hook: ["הקלטה נכנסת.", "סיכום יוצא."],
    l1: "מתמלל הקלטות",
    src: ["מקובץ", "לייב", "מקישור"],
    l3a: "ומסכם אותן לפי",
    l3b: "פרומפט מותאם אישית",
    aud: ["סטודנטים", "אנשי עסקים", "פגישות", "ועוד…"],
  },
  live: {
    prefix: "מתמללים",
    words: ["בלייב", "מקובץ", "מקישור"],
    recording: "מקליט",
    lines: ["בשיעור היום נדבר על מבנה התא.", "נתחיל בקרום התא ובתפקידים שלו.", "הוא בנוי משכבה כפולה של שומנים."],
    tail: "ואחר כך נעבור למיטוכונדריה",
    importTitle: "ייבוא",
    file: "הרצאה.mp4",
    link: "youtube.com/watch?v=…",
    transcribing: "מתמלל…",
    ready: "התמלול מוכן",
  },
  models: {
    h: "בוחרים מודל",
    eb: "מתמלל ישר על המחשב",
    badges: ["פרטי", "בלי אינטרנט", "מואץ GPU"],
  },
  ai: {
    h1: "מחברים בינה מלאכותית",
    eb1: "דרך API",
    h2: "הפרומפט שלך. הקבצים שלך.",
    eb2: "ומקבלים סיכום בדיוק כמו שביקשת",
    apiKey: "מפתח API",
    providers: ["Gemini", "Claude", "Ollama", "OpenAI-compatible"],
    files: ["שקפים.pdf", "הערות.txt", "לוח.jpg"],
    attach: "צרף קבצים",
    summary: "סיכום מוכן",
  },
  skills: {
    h: "סקילים לסוכני AI",
    eb: "Claude Code · Codex",
    installed: "הסקיל הותקן",
    user: "תמלל את הקלטת הפגישה ושלח לי סיכום",
    step1: "מתחבר ל-API המקומי של DIBE…",
    step2: "תומלל",
    step3: "סוכם",
  },
  outro: {
    tag: "תמלול פרטי. בלייב.",
    sub: "חינם · קוד פתוח",
  },
  site: {
    prompt: "נקודות מפתח ושאלות למבחן",
    summarizing: "מסכם…",
    summaryTitle: "סיכום",
    bullets: ["קרום התא בנוי משכבה כפולה של שומנים.", "הקרום קובע מה נכנס לתא ומה יוצא ממנו.", "שאלה למבחן: ממה בנוי קרום התא?"],
    record: "הקלטה",
    stop: "עצירה",
    saved: "ההקלטה נשמרה",
    summarize: "סכמו עם AI",
    question: "כמה זמן דיברו על כל נושא?",
    answer: "לפי התמלול, בדקות:",
    topics: ["מבנה התא", "קרום התא", "מיטוכונדריה"],
    models: "מודלים",
    download: "הורדה",
    downloading: "מוריד…",
    ready: "מוכן",
    question3: "מה נאמר בשיעור, ומתי?",
    answer3: "שלושה נושאים, לפי הסדר:",
    colTopic: "נושא",
    colTime: "זמן",
    airplane: "מצב טיסה",
  },
};

const en: typeof he = {
  intro: {
    hook: ["Recording in.", "Summary out."],
    l1: "transcribes recordings",
    src: ["From a file", "Live", "From a link"],
    l3a: "and summarizes them with",
    l3b: "your own custom prompt",
    aud: ["Students", "Business", "Meetings", "and more…"],
  },
  live: {
    prefix: "Transcribe",
    words: ["live", "from a file", "from a link"],
    recording: "Recording",
    lines: ["Today we'll talk about the structure of the cell.", "We'll start with the cell membrane and its roles.", "It is built from a double layer of lipids."],
    tail: "and then we'll move on to the mitochondria",
    importTitle: "Import",
    file: "lecture.mp4",
    link: "youtube.com/watch?v=…",
    transcribing: "Transcribing…",
    ready: "Transcript ready",
  },
  models: {
    h: "Pick a model",
    eb: "transcribes right on your computer",
    badges: ["Private", "Offline", "GPU accelerated"],
  },
  ai: {
    h1: "Connect your AI",
    eb1: "via API",
    h2: "Your prompt. Your files.",
    eb2: "and get the summary you asked for",
    apiKey: "API key",
    providers: ["Gemini", "Claude", "Ollama", "OpenAI-compatible"],
    files: ["slides.pdf", "notes.txt", "board.jpg"],
    attach: "Attach files",
    summary: "Summary ready",
  },
  skills: {
    h: "Skills for AI agents",
    eb: "Claude Code · Codex",
    installed: "Skill installed",
    user: "Transcribe the meeting recording and send me a summary",
    step1: "Connecting to DIBE's local API…",
    step2: "Transcribed",
    step3: "Summarized",
  },
  outro: {
    tag: "Private transcription. Live.",
    sub: "Free · Open source",
  },
  site: {
    prompt: "Key points and exam questions",
    summarizing: "Summarizing…",
    summaryTitle: "Summary",
    bullets: ["The cell membrane is a double layer of lipids.", "It controls what enters and leaves the cell.", "Exam question: what is the membrane made of?"],
    record: "Record",
    stop: "Stop",
    saved: "Recording saved",
    summarize: "Summarize with AI",
    question: "How long was each topic discussed?",
    answer: "From the transcript, in minutes:",
    topics: ["Cell structure", "Membrane", "Mitochondria"],
    models: "Models",
    download: "Download",
    downloading: "Downloading…",
    ready: "Ready",
    question3: "What was covered, and when?",
    answer3: "Three topics, in order:",
    colTopic: "Topic",
    colTime: "Time",
    airplane: "Airplane mode",
  },
};

export const STRINGS = { he, en };
export const useT = () => STRINGS[useLang()];
