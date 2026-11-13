# מלאי כלים לאתר DIBE

נבדק 2026-11-13, קריאה בלבד. לא נוצר ולא שונה שום דבר ב-Webflow.

חלוקת הסוכנים שהנחתי (אפשר לשנות):
- **A1 תנועה** — סרטוני Remotion ואנימציות באתר
- **A2 בנייה** — מבנה, עיצוב וסטיילים ב-Webflow
- **A3 תוכן ושפות** — טקסטים בעברית ובאנגלית, RTL ו-SEO
- **A4 הפצה** — הורדות לפי מערכת הפעלה, GitHub Releases, Buy Me a Coffee ופרסום

## תנועה ואנימציה
| כלי | מה הוא נותן | סוכן |
|---|---|---|
| Remotion (`promo-video/`, גרסה 4.0.536) | פרויקט קיים: `DibePromoHe`/`DibePromoEn` ‏(1920x1080, ‏54 שניות, קריינות וכתוביות), `StudentReel`, `AirplaneReel`, `GeminiKeyDesktop`/`GeminiKeyPhone`, ו-`Onboarding*` ‏(720x720). יש i18n של he/en ב-`src/i18n.ts` עם `useDir()` שמחזיר RTL, טוקני צבע ב-`theme.ts`, ונכסים ב-`public/` (לוגו, קליפים, קריינות he/en) | A1 |
| סקילים `remotion-best-practices`, `remotion-create` | הנחיות ליצירה ולרינדור של קומפוזיציות חדשות לאתר (לולאות hero, סרטונים קצרים לפיצ'רים) | A1 |
| `gsap-web` | גלילה ו-ScrollTrigger בעזרת קוד מותאם | A1 |
| `lottie-animation` | אנימציות וקטוריות קלות, למשל אייקונים מונפשים | A1 |
| `svg-animation`, `micro-interaction`, `page-transition-animation`, `60fps-animation`, `accessible-animation` | מיקרו-אינטראקציות, מעברים, ביצועים ו-reduced-motion | A1 |
| `animate`, `review-animations`, `improve-animations`, `emil-design-eng`, `apple-design` | החלטות על תנועה וביקורת עליה | A1 |
| Webflow Interactions (`data_interactions_tool`, ‏IX3) | אנימציות נייטיב בטריגרים של click, hover, load, scroll ו-mouse-move. אחרי כתיבה צריך לאמת ב-Preview, כי שמירה מוצלחת לא אומרת שהאנימציה רצה | A1 (בתיאום עם A2) |
| `manim-video`, `excalidraw-diagram` | סרטוני הסבר ותרשימים, אם יהיה בהם צורך | A1 |

## תמונות ווידאו
| כלי | מה הוא נותן | סוכן |
|---|---|---|
| `nano-banana`, `flux`, `ideogram`, `image-prompt`, `ai-image-editing` | יצירה ועריכה של תמונות (רקעים, mockups, תמונת OG) | A1 / A2 |
| `ai-video`, `veo-3`, `kling`, `runway`, `luma` | וידאו גנרטיבי, אם Remotion לא יספיק | A1 |
| `thumbnail-design`, `quote-cards-and-text-graphics` | תמונות poster לסרטונים ותמונות לשיתוף | A1 |
| Connector של Descript | עריכה, כתוביות וקליפים מהסרטונים | A1 |
| Connector של vidIQ | מוזיקה, קריינות ותמונות ממוזערות. נוגע ברשתות חברתיות, אז רק באישור | A1 (אופציונלי) |

## אייקונים
| כלי | מה הוא נותן | סוכן |
|---|---|---|
| `ui-ux-pro-max` | ספרייה מקומית של 105 אייקונים, וגם פלטות, זוגות גופנים ו-17 presets של GSAP | A2 |
| נכסים קיימים | `promo-video/public/logo.png` ו-`logo-disc.png`, ו-`dibe-brand-book.html` בשורש הריפו (לא נמצא ב-git) | A2 |
| `lottie-animation` / `svg-animation` | אייקונים מונפשים | A1 |

## עיצוב ו-RTL
| כלי | מה הוא נותן | סוכן |
|---|---|---|
| `hebrew-rtl-best-practices` | logical properties, ‏`:dir()`, ‏bidi, היפוך אייקונים וגופנים בעברית | A2 + A3 |
| `frontend-design`, `impeccable`, `emil-design-eng`, `glassmorphism`, `design-and-templates`, `brand-profile` | כיוון אסתטי וליטוש. השפה של ה-promo: רקע `#040a07`, ירוק `#3cb54a`, כתום `#ffb347` | A2 |
| סוכן `design-system-architect` | טוקנים שאפשר להעביר ל-Webflow Variables ‏(`data_variable_tool`) | A2 |
| סוכן `accessibility-expert` | בדיקת WCAG ותקן 5568 | A2 |
| סקיל `translate` של הפרויקט + `i18n/` | מערך התרגום של הפרויקט. `locales.json` מסמן 15 שפות `website:true`, אבל אין תיקיית `i18n/website/` | A3 |

## תכנון ו-UX
| כלי | מה הוא נותן | סוכן |
|---|---|---|
| `brainstorming`, `writing-plans`, `executing-plans`, `subagent-driven-development`, `dispatching-parallel-agents` | תכנון וחלוקת עבודה | מתאם |
| סוכנים `ui-ux-designer`, `ux-researcher` | wireframes, זרימות ומחקר מתחרים | A2 / A3 |
| `ai-search-optimization`, `social-seo` | SEO ו-AEO | A3 |
| `verification-before-completion`, סוכן `ui-visual-validator`, Browser pane | אימות ויזואלי לפני שמכריזים "גמור" | כולם |
| Connectors של Mermaid ו-visualize | תרשימי זרימה של האתר | מתאם |

## Webflow
- **Connector:** ‏Webflow MCP, גרסה 2.2.0, מחובר. `session_id` למשימה: `ses_3M1xaq7z99MenVS2N1LrX8QvUwZ`. כל סוכן צריך `agent_id` משלו.
- **אתרים:** אתר אחד בלבד, "Shalev's Spectacular Site" (`6af317ee486b32dee5d141e4`, ‏shortName ‏`shalevs-spectacular-site-dfa311`). נוצר ב-2026-11-10. יש בו רק עמוד Home, הוא לא פורסם אף פעם, ואין לו דומיין מותאם.
- **תוכנית:** לא Enterprise (בקשת branches החזירה 403 ‏`not_enterprise_plan_site`). אין דרך לקרוא את דרגת התוכנית ישירות, אבל אתר חדש בלי דומיין ובלי locales מתאים לתוכנית Free/Starter.
- **Localization:** **לא.** ב-`get_site` אין שדה locales, כלומר הוגדרה רק שפה ראשית ואין שפות משניות. בלי שפה משנית, `data_localization_tool` לא יכול לכתוב שום דבר.
- **Custom code:** קריאה עובדת (head ו-footer ריקים, אין scripts רשומים). בתוכנית Free, ‏Webflow בדרך כלל חוסם custom code ברמת האתר. לא בדקתי כתיבה כי המשימה הייתה קריאה בלבד. רכיב Embed בתוך עמוד יכול להיות חלופה.
- **כלים לבנייה (A2):** `data_whtml_builder` (בניית אלמנטים מ-HTML ו-CSS), `data_element_builder`/`data_element_tool`, `data_style_tool`, `data_variable_tool`, `data_component_*`, `data_pages_tool`, `data_assets_tool`/`asset_tool`, `data_fonts_tool`, `designer_tool`, `element_snapshot_tool`.
- **תנועה (A1):** `data_interactions_tool` (לקרוא קודם את ה-guide שלו).
- **תוכן ושפות (A3):** `data_localization_tool` (רק כשיש שפה משנית), `data_cms_tool`, `data_pages_tool` (SEO, OG, JSON-LD), `data_sitemap_tool`.
- **הפצה (A4):** `data_scripts_tool` (סקריפט הורדה לפי מערכת הפעלה), `data_sites_tool > publish_site` (באישור המשתמש בלבד), `data_forms_tool`.

## GitHub ופרסום
| כלי | מה הוא נותן | סוכן |
|---|---|---|
| `gh` CLI | מחובר כ-`akucbruhu0-cyber`. כרגע אין רשת אל api.github.com (timeout), ולכן לא הצלחתי לקרוא את רשימת ה-assets של ה-release | A4 |
| GitHub Releases ‏(`akucbruhu0-cyber/dibe`) | קישורים יציבים מסוג `releases/latest/download/<asset>`, וזיהוי מערכת הפעלה ב-JS קטן או בכפתורים נפרדים | A4 |
| `github-publish` | אירוח חלופי ב-GitHub Pages, למקרה שאין custom code ב-Webflow | A4 |
| `creator-monetization` | ניסוח של Buy Me a Coffee (קישור או כפתור; ווידג'ט JS צריך custom code) | A4 |
| `campaign-and-launch-planning`, `youtube-shorts`, `reels-script` | השקה וקידום אחרי שהאתר עולה | A4 (אחר כך) |

## חסמים
1. **אין Localization ב-Webflow.** לא הוגדרה שפה משנית, וכנראה התוכנית לא כוללת את זה. אתר he+en דורש אחד מאלה: לשדרג ל-Site plan עם Localization ולהוסיף שפה משנית ב-Designer (פעולה של המשתמש); לבנות עמודים כפולים (`/` בעברית ו-`/en`) עם `dir` ידני; או לבחור שפה ראשית אחרת. ההחלטה צריכה לבוא מהמשתמש.
2. **Custom code כנראה חסום בתוכנית Free.** זה משפיע על סקריפט ההורדות לפי מערכת הפעלה, GSAP, Lottie והווידג'ט של Buy Me a Coffee. צריך לאמת בכתיבה ניסיונית (באישור), לשדרג, או להסתפק בכפתורים סטטיים ובאינטראקציות IX3.
3. **אין דומיין והאתר לא פורסם.** השם הוא ברירת מחדל ("Shalev's Spectacular Site"). ייתכן שעדיף ליצור אתר DIBE חדש או לשנות את השם (באישור).
4. **אין branches** (התוכנית לא Enterprise). עובדים ישירות על העמודים, ולכן צריך לתאם שלא יהיו שני סוכנים על אותו עמוד.
5. **אין רשת אל GitHub API כרגע,** אז שמות ה-assets של ה-release לא אומתו.
6. **אירוח הסרטונים:** Webflow מגביל גודל של נכסי וידאו. סרטוני Remotion יצטרכו קידוד דחוס (crf ו-scale כמו בסקריפטים הקיימים) או אירוח חיצוני כמו GitHub Releases או YouTube.
