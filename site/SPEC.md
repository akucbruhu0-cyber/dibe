# DIBE Website: Content + UX Spec (v1, DIBE 1.4.0)

Contract for the Remotion animator and the Webflow builder. Do not change section IDs or composition names without updating this file.
Sources: README.md, CHANGELOG.md, PRIVACY.md, dibe-brand-book.html, promo-video/src, GitHub release v1.4.0.

## 1. Goal, audience, CTA

- **Goal:** a visitor understands in 5 seconds what DIBE is (private transcription, live while recording) and downloads it in one click.
- **Audience:** (a) students recording lectures, (b) people in meetings and interviews, (c) privacy-minded people and developers (open source, local API, agent skill). Hebrew first, English second.
- **Single primary CTA:** "הורידו ל-<OS>" / "Download for <OS>" (auto-detected). One CTA type across the page; every other button is secondary (outline).
- **Secondary, never competing:** GitHub, Buy Me a Coffee, privacy page.
- **Voice (from brand book):** quiet, plain, concrete. "Record quietly. The smart part glows." No hype words, no "revolutionary", no "AI-powered" as a headline. Short sentences. No invented numbers, quotes or user counts.

## 2. Sitemap

| Path | Purpose |
|---|---|
| `/` (he, default, `dir=rtl`) | One-page landing |
| `/en/` | Same page, English, `dir=ltr`. Webflow Localization, not a copy |
| `/privacy` (+ `/en/privacy`) | Full text of PRIVACY.md (table of when the network is used). Link only from #privacy and footer |

Language switch: pill in the header (עב / EN), remembered in localStorage, never auto-redirects after the first choice. First visit follows `Accept-Language`.
Header (sticky, frosted glass): logo (inline-start), anchors Features / Download / FAQ, language pill, small download pill (appears after #hero leaves the viewport). No hamburger needed: at mobile width show only logo, language pill, download pill.

## 3. Sections (in order)

Design tokens come from the brand book, do not invent: Paper `#F5FAF6`, Mint `#CDEBD5`, Ink `#0F2418`, Forest `#1E6A2E` (primary button), Muted `#4D6656`, Spark `#FFB574` (AI star only), Live `#EE4F45` (recording dot only), glow gradient `#FFC37E #FF9FC2 #B8A4FF #86D2FF #93E6B0` (blur only, behind an active AI element). Dark: Paper `#0C1710`, Mint `#1D3A27`, Forest `#8FD9A2`, Ink `#E6F3E9`. Fonts: Rubik 500/700 (display), IBM Plex Sans Hebrew 400-600 (text), IBM Plex Mono (timecodes). Radius 12/22/36/pill. No hard shadows. Edge blur 56-72px at top and bottom of the viewport frame.

### 3.1 `#hero`
- **Purpose:** promise + CTA + the first moment of life.
- **HE**
  - Eyebrow: `תמלול פרטי. בלייב.`
  - H1: `הקלטה נכנסת. סיכום יוצא.`
  - Sub: `DIBE הופכת דיבור לטקסט על המחשב שלכם. מקליטים ורואים את התמלול מופיע בזמן אמת. בלי אינטרנט, ושום דבר לא יוצא מהמכשיר.`
  - CTA: `הורידו ל-macOS` (label from OS detection) + secondary `כל הגרסאות` (anchors to #download)
  - Micro line: `חינם · קוד פתוח · גרסה 1.4.0`
- **EN**
  - Eyebrow: `Private transcription. Live.`
  - H1: `Recording in. Summary out.`
  - Sub: `DIBE turns speech into text on your own computer. Record and watch the transcript appear live. No internet needed, and nothing leaves your device.`
  - CTA: `Download for macOS` + secondary `All versions`
  - Micro line: `Free · Open source · Version 1.4.0`
- **Layout:** two columns on desktop (text inline-start, animation zone `ANIM-HERO` inline-end), stacked on mobile with animation below the CTA. Left-aligned to the reading edge, never centered (brand rule). Mint mesh background, max two light blobs, grain 9%. Version number is injected from the release API, not hard-coded.

### 3.2 `#intro`
- **Purpose:** what it is in one breath, three sources.
- **HE:** H2 `מתמללת, ומסכמת לפי הפרומפט שלכם.` Body: `DIBE מתמללת הקלטות מקובץ, מהמיקרופון, מאודיו של המערכת או מקישור. אחר כך היא מסכמת אותן כמו שביקשתם: נקודות מפתח, שאלות למבחן, סיכום פגישה. המתמלל והמודל רצים אצלכם במחשב.` Chips (pill): `מקובץ` `לייב` `מקישור` · audience line: `סטודנטים · פגישות · ראיונות · ועוד`
- **EN:** H2 `Transcribes, then summarizes your way.` Body: `DIBE transcribes from a file, the microphone, your system audio or a link. Then it summarizes the way you ask: key points, exam questions, meeting notes. Transcription runs on your computer.` Chips: `From a file` `Live` `From a link` · `Students · Meetings · Interviews · and more`
- **Layout:** single narrow text column (max 62ch), chips row below. Chips are static labels, not buttons.

### 3.3 `#features` (six cards, bento grid 3x2 desktop, 1 column mobile)
Each card: line icon (SVG, never emoji), title, one sentence, optional tiny animation (`ANIM-F1..F3`, see section 4). Card order is the order of importance.

| # | HE title | HE body | EN title | EN body |
|---|---|---|---|---|
| 1 | תמלול בזמן אמת | משפטים שהסתיימו מופיעים תוך שנייה או כמה שניות. שורה אפורה מראה את המשפט שעוד נאמר. עוצרים, ממשיכים, ואפשר לערוך תוך כדי. | Live transcript | Finished sentences appear within a second or a few. A grey line shows the sentence still being spoken. Pause, resume, edit while it writes. |
| 2 | כל מקור, כמעט כל שפה | קובץ שמע או וידאו, מיקרופון, אודיו של המערכת, קישור מיוטיוב ואתרים נוספים. כמעט כל שפה, בלי אינטרנט. | Any source, almost any language | Audio or video files, the microphone, system audio, links from YouTube and more. Almost every language, offline. |
| 3 | סיכום לפי הפרומפט שלכם | כותבים מה אתם צריכים וה-AI מסכם. אפשר לצרף תמונות, PDF וקובצי טקסט. עובד עם Gemini, Claude, שרת תואם OpenAI או Ollama מקומי. | A summary on your terms | Write what you need and the AI summarizes. Attach images, PDFs and text files. Works with Gemini, Claude, any OpenAI-compatible server or local Ollama. |
| 4 | שואלים את ההקלטה | שאלה על ההקלטה מקבלת תשובה, ולפעמים תרשים, ציר זמן או טבלה כשזה מבהיר. המספרים בתרשים הם רק מה שנאמר. | Ask the recording | Ask a question about a recording and get an answer, sometimes with a chart, timeline or table when it helps. Charts use only numbers that were said. | 
| 5 | ייצוא שנראה נכון | SRT, VTT, TXT, HTML, JSON, Word ו-PDF מעוצב: כותרות, טבלאות, ויישור לשני הצדדים. אפשר לצרף את השאלות שנשאלו. | Exports that look right | SRT, VTT, TXT, HTML, JSON, Word and a designed PDF: real headings, tables, justified text. Add your follow-up questions if you like. |
| 6 | גם בטלפון | אפליקציית אנדרואיד שמקליטה ומתמללת על הטלפון עצמו, בלי אינטרנט. ההקלטות לא הולכות לאיבוד גם אם משהו קורס. | Also on your phone | An Android app that records and transcribes on the phone itself, offline. Recordings survive crashes. |

Rules: no feature without a CHANGELOG/README source. Card 5 mentions "designed PDF" because 1.4.0 shipped it. Do not add speaker diarization, translation or hotkey cards (see section 5).

### 3.4 `#showcase` (animation only)
- **Purpose:** feel the product, no reading. Three full-bleed pinned scroll scenes, each ~100vh, zero copy except an `aria-label` and a visually hidden caption (for screen readers and SEO). See `ANIM-SHOW-1..3`.
- **Layout:** edge blur top/bottom, scene scrubs with scroll (progress = scroll position) on desktop; on mobile it autoplays muted when 60% visible. Nothing else in the viewport but the scene and a thin progress dot row.

### 3.5 `#privacy`
- **Purpose:** the trust argument, concrete, with a proof the visitor can check.
- **HE:** H2 `התמלול נשאר אצלכם.` Body: `DIBE אין אנליטיקס, אין טלמטריה, אין חשבונות ואין בדיקות עדכון. התמלול רץ על המחשב, והשרת המקומי מאזין רק ל-127.0.0.1 מאחורי טוקן שמשתנה בכל הפעלה.` Three short facts (list): `בלי אינטרנט היא ממשיכה לעבוד.` / `היא פונה לרשת רק כשאתם מבקשים: הורדת מודל, קישור, ספק AI שהגדרתם.` / `אין שום דבר ברקע.` Link: `מה בדיוק נשלח ומתי` (to /privacy). Side note, small: `קוד פתוח ברישיון MIT, אפשר לבדוק.`
- **EN:** H2 `Your transcript stays with you.` Body: `DIBE has no analytics, no telemetry, no accounts and no update checks. Transcription runs on your computer, and the local server listens only on 127.0.0.1 behind a token that changes on every launch.` Facts: `Without internet it keeps working.` / `It uses the network only when you ask: a model download, a link, an AI provider you configured.` / `Nothing runs in the background.` Link: `Exactly what is sent, and when` · Small: `Open source under the MIT license. Check it yourself.`
- **Layout:** text inline-start, `ANIM-PRIVACY` inline-end. Honest limit stated once: if you pick a cloud AI provider, the transcript text goes to that provider (the /privacy page table says so; do not hide it).

### 3.6 `#download`
- **Purpose:** one click for most people, clear alternatives for the rest.
- **Block A (auto):** big card. Detects OS and CPU (see section 6). HE: `מותאם למחשב שלכם` + button `הורידו ל-macOS (Apple Silicon)` + file name and size in Mono (`DIBE-1.4.0-macos-arm64.zip · 38 MB`) + `לא בטוחים? ראו "כל הגרסאות"`. EN: `Picked for your computer` + `Download for macOS (Apple Silicon)`.
- **Block B: "כל הגרסאות" / "All versions"** (expandable list, open by default on mobile):

| Row (HE / EN) | Asset (release v1.4.0) | Pattern the builder matches | Status line |
|---|---|---|---|
| מק עם Apple Silicon / Mac, Apple Silicon | `DIBE-1.4.0-macos-arm64.zip` (38.2 MB) | `DIBE-*-macos-arm64.zip` | `נבדקה ומומלצת` / `Tested` |
| מק עם Intel / Mac, Intel | `DIBE-1.4.0-macos-intel.zip` (43.3 MB) | `DIBE-*-macos-intel.zip` | `לא נבדקה, בלי זיהוי דוברים` / `Untested, no speaker diarization` |
| Windows 10/11, 64-bit | `DIBE-1.4.0-windows-x64-setup.exe` (43.0 MB) | `DIBE-*-windows-x64-setup.exe` | `לא נבדקה` / `Untested` |
| אנדרואיד (64-bit) / Android (64-bit) | `dibe-android-arm64.apk` (11.6 MB) | `dibe-android-arm64.apk` (stable URL `/releases/latest/download/dibe-android-arm64.apk`) | `טלפון 64-ביט מ-2019 בערך, Snapdragon 855 ומעלה` / `64-bit phone from about 2019 (Snapdragon 855 or newer)` |

  Sizes are shown rounded and read from the API (`size` field). Asset base: `https://github.com/akucbruhu0-cyber/dibe/releases/latest`. Desktop asset names carry the version, so resolve them at runtime from `https://api.github.com/repos/akucbruhu0-cyber/dibe/releases/latest` by pattern, with a hard-coded fallback to the release page if the call fails.
- **Block C: first-launch help (accordion, 2 items).**
  - HE Mac: `DIBE לא חתומה אצל אפל, אז macOS חוסם את הפתיחה הראשונה. גוררים את DIBE.app ל-Applications, ואז: הגדרות מערכת ← פרטיות ואבטחה ← "פתח בכל זאת".` (copyable command, LTR: `xattr -dr com.apple.quarantine /Applications/DIBE.app`) HE Windows: `Windows עשויה להציג "Windows protected your PC". בוחרים More info ← Run anyway.` HE Android: `פותחים את הקובץ בטלפון ומאשרים התקנה מהמקור הזה.`
  - EN Mac: `DIBE is not notarized by Apple, so macOS blocks the first launch. Drag DIBE.app into Applications, then: System Settings → Privacy & Security → Open Anyway.` Windows: `Windows may show "Windows protected your PC". Choose More info → Run anyway.` Android: `Open the file on your phone and allow installing from this source.`
- **Honest line (under the cards):** HE `DIBE פותחה ונבדקה על מק עם Apple Silicon. את גרסאות Intel ו-Windows בונה מערכת אוטומטית ועדיין לא בדקנו אותן. אם משהו לא עובד, ספרו לנו.` (link "ספרו לנו" to GitHub issues) EN `DIBE is built and tested on Apple Silicon Macs. The Intel and Windows builds are produced automatically and not yet tested. If something breaks, tell us.`
- **Also:** `צריך מחשב חזק, כי התמלול רץ עליו.` / `You need a powerful computer, because transcription runs on it.` + `הקוד המלא ב-GitHub` / `Source on GitHub`. Fork credit line (small, required by honesty): `DIBE היא פיצול של Vibe מאת thewh1teagle, ברישיון MIT.` / `DIBE is a fork of Vibe by thewh1teagle, MIT license.`

### 3.7 `#support`
- **Purpose:** soft ask after value is clear. Not a banner.
- **HE:** H2 `DIBE חינמית, ותישאר כזו.` Body: `אם היא חסכה לכם זמן, אפשר לקנות לה קפה.` Button (secondary, outline): `קנו ל-DIBE קפה` → `https://buymeacoffee.com/dibe`. Small: `לא חובה. ההורדה לא תלויה בזה.`
- **EN:** H2 `DIBE is free, and stays free.` Body: `If it saved you time, you can buy it a coffee.` Button: `Buy DIBE a coffee`. Small: `Optional. Downloading never depends on it.`
- **Layout:** compact mint card (radius 22), no animation, the button opens a new tab with `rel="noopener"`. *(Check "one developer" with the owner before launch; remove if untrue.)*

### 3.8 `#faq` (accordion, native `<details>`, first closed)
| HE question | HE answer | EN question | EN answer |
|---|---|---|---|
| האם DIBE באמת עובדת בלי אינטרנט? | כן. אחרי שהורדתם מודל, התמלול עובד לגמרי במחשב. אינטרנט נדרש רק להורדת מודל, לקישורים ולסיכום בענן אם בחרתם בו. | Does DIBE really work offline? | Yes. Once a model is downloaded, transcription runs entirely on your computer. The internet is needed only for model downloads, links, and cloud summaries if you pick that. |
| כמה זה עולה? | כלום. DIBE חינמית וקוד פתוח (MIT). סיכומי AI דורשים מפתח של הספק שבחרתם. ל-Gemini יש מפתח חינמי, ובהגדרות יש סרטון קצר שמראה איך מקבלים אותו. | What does it cost? | Nothing. DIBE is free and open source (MIT). AI summaries need a key from the provider you choose. Gemini offers a free key, and Settings has a short video on how to get it. |
| מה צריך כדי שזה ירוץ? | מחשב חזק, כי התמלול רץ עליו. מק עם Apple Silicon הוא הסביבה הנבדקת. בטלפון: אנדרואיד 64-ביט מ-2019 בערך. | What do I need to run it? | A powerful computer, because transcription runs on it. A Mac with Apple Silicon is the tested setup. On a phone: 64-bit Android from about 2019. |
| למה המחשב מזהיר אותי בפתיחה? | DIBE לא חתומה ולא עברה notarization של אפל. זה צפוי, ואפשר לאשר פעם אחת (ראו "הורדה"). | Why does my computer warn me? | DIBE is not code-signed or notarized by Apple. That is expected, and you allow it once (see Download). |
| האם זה עובד בעברית? | כן, לצד כמעט כל שפה אחרת. הממשק עצמו מתורגם לעשרות שפות. | Does it work in Hebrew? | Yes, alongside almost every other language. The interface is translated into many languages. |
| מה ההבדל בינה לבין Vibe? | DIBE נבנתה על Vibe ושומרת על כל מה שהיא עושה. היא מוסיפה תמלול בזמן אמת, מקליט יציב יותר, Gemini לסיכומים, ושום אנליטיקס. | How is it different from Vibe? | DIBE is built on Vibe and keeps everything it does. It adds live transcription, a sturdier recorder, Gemini for summaries, and no analytics at all. |
| DIBE מתעדכנת לבד? | לא. מורידים גרסה חדשה מדף ההורדות. | Does it update itself? | No. Download the new version from the releases page. |

Also emit `FAQPage` JSON-LD from this table.

### 3.9 `#footer`
- Logo + `תמלול פרטי. בלייב.` / `Private transcription. Live.`
- Links (HE / EN): הורדה / Download · GitHub · פרטיות / Privacy · יומן שינויים / Changelog (CHANGELOG.md on GitHub) · דיווח על בעיה / Report an issue · קנו קפה / Buy a coffee
- Legal line: `DIBE פרויקט עצמאי, לא קשור ל-OpenAI, NVIDIA, Anthropic, Google או Apple. שמות ולוגואים שייכים לבעליהם.` / `DIBE is an independent project, not affiliated with or endorsed by OpenAI, NVIDIA, Anthropic, Google or Apple. Names and logos belong to their owners.`
- `© MIT · thewh1teagle (Vibe) · akucbruhu0-cyber (DIBE)`. No social icons (no accounts exist).

## 4. Animation table (Remotion)

Project: `promo-video/`. Reuse theme/i18n/ui from `promo-video/src` (`C` colors, `useLang`, `LangContext`). Register in `Root.tsx` with the ids below. Every composition: 30 fps, transparent or Paper-colored background per note, **no baked-in text unless listed** (text comes from the page, except in-app UI mockup strings which use `i18n.ts` keys). Export per composition: `.webm` (VP9, alpha where noted) + `.mp4` (H.264) + `.poster.webp` (the reduced-motion fallback, frame noted). Max 1.5 MB per loop, 4 MB per showcase. Lottie is not used.

| Composition id | Zone | Purpose | Ratio / px | Duration | Loop | HE / EN | Reduced-motion poster |
|---|---|---|---|---|---|---|---|
| `SiteHero` | `#hero` | Waveform pulses, a live transcript types in (gray pending line then finished line), red REC dot and mono timer. Reuses the Intro hook + Live scene ideas | 4:3, 1440x1080 | 8 s | Yes, seamless (last frame = first) | Variants `SiteHeroHe`, `SiteHeroEn` (transcript lines from `i18n.ts` live.lines) | Frame 150: two finished lines, one grey pending line, timer 00:00:12 |
| `SiteFeatLive` | card 1 | Words resolving from grey to ink | 1:1, 720x720 | 4 s | Yes | He/En | Frame 90 |
| `SiteFeatSources` | card 2 | Three source icons (file, mic, link) flowing into one transcript | 1:1, 720x720 | 4 s | Yes | No text (icons only), single file | Frame 60 |
| `SiteFeatAi` | card 3 | Prompt typed, glow ring (11 s rotation, blur 16px) behind summary, fades 900 ms when done, result goes still | 1:1, 720x720 | 6 s | Yes (glow resets) | He/En (prompt/summary strings) | Last frame, glow off |
| `SiteFeatChart` | card 4 | Question bubble, answer with a bar chart drawing in | 1:1, 720x720 | 5 s | Yes | He/En | Last frame |
| `SiteFeatExport` | card 5 | One summary becoming PDF, Word, SRT tiles | 1:1, 720x720 | 4 s | Yes | No text, single file | Frame 100 |
| `SiteFeatPhone` | card 6 | Phone recording, waveform, lines appearing (reuse gemini-key `android.tsx` frame) | 9:16 phone mock in 1:1 canvas, 720x720 | 5 s | Yes | He/En | Frame 90 |
| `SiteShow1` | `#showcase` scene 1 | "Lecture to notes": record, live words, stop, summary appears. Adapted from `StudentReel` | 16:9, 1920x1080 | 12 s, scrubbed by scroll | No (plays once per scene) | He/En (UI strings, no overlays) | Frame at 85%: summary on screen |
| `SiteShow2` | `#showcase` scene 2 | "Models and speed": model chips, progress, ready. Adapted from `Models` scene | 16:9, 1920x1080 | 8 s | No | He/En | Last frame |
| `SiteShow3` | `#showcase` scene 3 | "One question, one chart": the app answers with a timeline and a table | 16:9, 1920x1080 | 10 s | No | He/En | Last frame |
| `SitePrivacy` | `#privacy` | Airplane mode on, Wi-Fi off, transcript keeps writing; a small lock on the device. Adapted from `AirplaneReel` (16:9 crop of its key beats) | 1:1, 900x900 | 7 s | Yes | He/En (status bar text only) | Frame 140: airplane icon + finished transcript |
| `SiteWordmark` | header/footer | Logo star sparkle (Spark color, 3 stars 1/0.5/0.3), 1 s one-shot on page load | 1:1, 256x256, transparent | 1.2 s | No | No text | Static SVG logo (`design/logo.svg`) |

Rules for the animator:
- Motion = sign of life: only what is live or AI moves; finished content is still. Continuous motion max 3% scale, 4px shift. Easing: spring, no linear. Glow only behind an active AI element, one per screen.
- No red except the REC dot; Spark orange only on the AI star.
- Seamless loops: verify frame 0 equals last frame + 1.
- Deliver each variant with identical timing so Webflow can swap by language without layout shift. Provide `site-animations.json` listing id, files, poster, width, height, duration.

## 5. What is NOT on the site

| Left out | Reason |
|---|---|
| Speaker diarization, translation, global hotkey, batch, custom models, CLI, Swagger API | True but not top 6. They belong in README/FAQ-level detail, and the page is for a first-time visitor, not a feature matrix. Developers get the GitHub link |
| User counts, testimonials, star counts, "trusted by" logos, press | None exist or are verified. Fabricated social proof is forbidden |
| Pricing table, plans, "Pro" | There are none. The product is free |
| Accounts, sign-in, newsletter form, cookie banner | DIBE has no accounts. The site must use no tracking cookies, so no banner is needed. Use cookie-less analytics or none (match the product's privacy stance) |
| Accuracy claims ("99% accurate"), speed claims ("10x faster") | No measured source. Models vary by hardware |
| Comparison table versus Otter, Rev, Whisper apps | Needs verified claims and ages badly. Only the Vibe relation is stated, in FAQ |
| Build-from-source steps, code signing docs | Developer content, lives in docs/BUILDING.md. Footer links to GitHub |
| Changelog on the page | Footer link to CHANGELOG.md is enough |
| Linux download | No Linux build is published |
| iOS / App Store badges | There is no iOS app |
| Stock photos of people, headphones, microphones | Brand book: no photographic textures. The product UI is the imagery |
| Auto-playing sound or video with audio | Accessibility and annoyance. The promo video narration is opt-in only (see 6) |
| Full promo video (`DibePromoHe/En`, 54 s) in the hero | Too heavy for first paint. Offered as a "Watch the 54 s tour" text button under #showcase that opens a modal with captions, sound off by default |

## 6. Interaction, accessibility, RTL

**OS detection (Webflow custom code, ~20 lines, no library):**
- `navigator.userAgentData?.platform` then `navigator.platform`/UA fallback. Windows -> `windows-x64-setup.exe`. Android -> APK. macOS -> default to `macos-arm64`; try `navigator.userAgentData.getHighEntropyValues(['architecture'])`, and if it returns `x86` show Intel. Safari reports no reliable CPU, so show Apple Silicon with the line `Mac עם Intel? בחרו ב"כל הגרסאות"` / `Intel Mac? Pick it under "All versions"`. iPhone/iPad/Linux/unknown -> show the Mac/Windows pair and the APK, no single recommendation.
- Without JavaScript: render the full #download list (progressive enhancement). The hero CTA then scrolls to #download.

**Scroll and hover**
- Reveals: opacity 0->1 and translateY 16px->0, 400 ms, ease-out, once per element, 60 ms stagger inside a group, max 6 elements per group. Never animate width/height. Hero is visible at load (no reveal on the H1).
- Feature cards hover/focus: lift 2px, tint to Mint-soft, the card's animation plays (it sits on first frame of its poster otherwise). On touch devices cards autoplay when 60% visible.
- Buttons: primary Forest fill, hover darkens 6%, pressed scale 0.98, 120 ms. Focus ring 3px Forest with 2px offset (never removed).
- Animation videos: `muted playsinline loop preload="metadata"`, lazy (`IntersectionObserver`), pause when out of view. LCP element is the H1, not the video; hero poster is the first paint and the video swaps in after `canplay`.

**Reduced motion** (`prefers-reduced-motion: reduce`): no scroll reveals, no scrubbing, no autoplay; every video shows its poster (`<picture>` with the `.poster.webp`); `#showcase` becomes three static stacked images with their hidden captions made visible. Also provide a small "pause animations" toggle in the footer.

**Accessibility (target WCAG 2.2 AA)**
- Contrast: Ink on Paper and Forest on Paper pass; Muted `#4D6656` only for text 16px+ on Paper (verify 4.5:1 in both themes). Never Spark/Live as text color.
- Landmarks: `header`, `main`, `section[aria-labelledby]`, `footer`. One `h1`. Skip link `דלגו לתוכן` / `Skip to content`.
- Animations are decorative (`aria-hidden="true"`, `alt=""`) except showcase scenes, which get `role="img"` + a real `aria-label` describing the moment (HE and EN).
- Download buttons name the file: `aria-label="הורידו DIBE-1.4.0-macos-arm64.zip, 38 מגה-בייט"`. Open-in-new-tab links say so.
- Touch targets 44x44 minimum; 8px gaps. Accordions are native `<details>/<summary>` (keyboard works free).
- Theme: follows `prefers-color-scheme` with a manual toggle; both palettes defined as tokens (Webflow Variables, modes Light/Dark).
- Page weight target: under 350 KB before videos; videos lazy.

**RTL / Hebrew**
- `<html lang="he" dir="rtl">`; English locale `lang="en" dir="ltr"`. Use logical properties only (`margin-inline-start`, `padding-inline-end`, `text-align: start`, `inset-inline-*`). In Webflow use the Start/End alignment options, not Left/Right.
- Body text justified (`text-align: justify`) only for paragraphs over 2 lines in columns of 32 characters or more; headings, buttons, chips, card titles are not justified. Last line aligns to the start.
- Hebrew line-height 1.7, no negative letter-spacing, no `text-transform`. Fonts via Google Fonts with `hebrew` + `latin` subsets: Rubik, IBM Plex Sans Hebrew, IBM Plex Mono; `font-display: swap`. `text-wrap: balance` on headings.
- Isolate LTR runs inside Hebrew: file names, version `1.4.0`, `127.0.0.1`, the `xattr` command, "macOS", "Apple Silicon", "Gemini", "Claude" -> `<span dir="ltr" lang="en">` (commands in `<code dir="ltr">` with `unicode-bidi: isolate`). Sizes like `38 MB` in Mono inside `<bdi>`.
- Directional icons (arrows, chevrons in accordions and "all versions") flip via `:dir(rtl)`; play/pause, check, logo, the sparkle and the REC dot do not flip.
- Hero layout: in RTL the text sits on the right and the animation on the left; this follows from `dir`, do not hard-code. Recorded UI inside animations is the real Hebrew app, already RTL; do not mirror video files.
- Scrollbar gutter: `scrollbar-gutter: stable`. Edge blur is symmetrical, no direction issue. Shadows are not used; the glow is symmetrical.
- Test string for QA: `שלום John 1.4.0 38 MB DIBE-1.4.0-macos-arm64.zip` rendered in a card, a button and the FAQ.

**Webflow builder notes**
- Localization: he (primary), en. Same structure, same IDs, translated via the tables above (strings are final copy; ask before rewording).
- Download data: one small custom-code embed fetches the latest release JSON, fills `[data-asset="arm64|intel|win|apk"]` href, size and version nodes, with fallback hrefs pre-set in the HTML (`/releases/latest`; APK uses the stable `/releases/latest/download/dibe-android-arm64.apk`).
- SEO: title `DIBE: תמלול פרטי בלייב על המחשב שלכם` / `DIBE: private live transcription on your computer`; meta description = hero sub; OG image = hero poster (1200x630, Paper background); `SoftwareApplication` JSON-LD (free, offers price 0, OS macOS/Windows/Android), `FAQPage` JSON-LD; `hreflang` he/en.
- Open before launch: custom domain (none assigned), confirm "one developer" wording in #support, confirm whether Intel/Windows get tested before the page calls them anything but "untested".
