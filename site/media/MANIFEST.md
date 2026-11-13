# site/media: website animations

Rendered from `promo-video/src/site` (Remotion, 30 fps, silent). Re-render with `promo-video/render-site.sh [Id ...]`.
Encoding: MP4 = H.264 CRF 28, yuv420p, faststart; WebM = VP9 CRF 38 (`SiteWordmark.webm` keeps alpha). Posters are WebP, used as `poster=` and as the reduced-motion image.
HE and EN variants of one id have identical size and timing, so the page swaps `src` by `lang` without layout shift.
Loops marked seamless: frame 0 equals the frame after the last, so `loop` shows no seam. Showcase clips are meant to be scrubbed by scroll (set `currentTime`) and are not looped.
All clips are drawn on Paper `#F5FAF6` (light theme), except the transparent wordmark WebM.

Total: 4.47 MB for 21 variants.

| File base | Lang | Size px | Length | Loop | .mp4 | .webm | .poster.webp | SPEC section |
|---|---|---|---|---|---|---|---|---|
| `SiteHero-he` | he | 1440x1080 | 8.0 s | seamless | 238 KB | 233 KB | 21 KB (frame 150: two finished lines, one pending, timer 00:00:12) | §3.1 #hero (ANIM-HERO) |
| `SiteHero-en` | en | 1440x1080 | 8.0 s | seamless | 285 KB | 264 KB | 30 KB (frame 150: two finished lines, one pending, timer 00:00:12) | §3.1 #hero (ANIM-HERO) |
| `SiteFeatLive-he` | he | 720x720 | 4.0 s | seamless | 45 KB | 57 KB | 13 KB (frame 90: all words resolved to ink) | §3.3 card 1 |
| `SiteFeatLive-en` | en | 720x720 | 4.0 s | seamless | 54 KB | 70 KB | 19 KB (frame 90: all words resolved to ink) | §3.3 card 1 |
| `SiteFeatSources` | any | 720x720 | 4.0 s | seamless | 28 KB | 33 KB | 5 KB (frame 60: mic source active) | §3.3 card 2 |
| `SiteFeatAi-he` | he | 720x720 | 6.0 s | seamless | 48 KB | 72 KB | 13 KB (frame 158: summary settled, glow off (last frames reset the loop)) | §3.3 card 3 |
| `SiteFeatAi-en` | en | 720x720 | 6.0 s | seamless | 59 KB | 90 KB | 19 KB (frame 158: summary settled, glow off (last frames reset the loop)) | §3.3 card 3 |
| `SiteFeatChart-he` | he | 720x720 | 5.0 s | seamless | 38 KB | 52 KB | 8 KB (frame 128: chart drawn, glow off (last frames reset the loop)) | §3.3 card 4 |
| `SiteFeatChart-en` | en | 720x720 | 5.0 s | seamless | 44 KB | 63 KB | 11 KB (frame 128: chart drawn, glow off (last frames reset the loop)) | §3.3 card 4 |
| `SiteFeatExport` | any | 720x720 | 4.0 s | seamless | 24 KB | 16 KB | 6 KB (frame 100: PDF / DOCX / SRT tiles out) | §3.3 card 5 |
| `SiteFeatPhone-he` | he | 720x720 | 5.0 s | seamless | 68 KB | 65 KB | 11 KB (frame 90: phone recording, lines on screen) | §3.3 card 6 |
| `SiteFeatPhone-en` | en | 720x720 | 5.0 s | seamless | 84 KB | 81 KB | 14 KB (frame 90: phone recording, lines on screen) | §3.3 card 6 |
| `SiteShow1-he` | he | 1920x1080 | 12.0 s | once (scrub) | 215 KB | 234 KB | 32 KB (frame 306: 85%: summary on screen) | §3.4 #showcase scene 1 |
| `SiteShow1-en` | en | 1920x1080 | 12.0 s | once (scrub) | 248 KB | 277 KB | 44 KB (frame 306: 85%: summary on screen) | §3.4 #showcase scene 1 |
| `SiteShow2-he` | he | 1920x1080 | 8.0 s | once (scrub) | 61 KB | 83 KB | 19 KB (frame 239: last frame: model ready) | §3.4 #showcase scene 2 |
| `SiteShow2-en` | en | 1920x1080 | 8.0 s | once (scrub) | 70 KB | 94 KB | 22 KB (frame 239: last frame: model ready) | §3.4 #showcase scene 2 |
| `SiteShow3-he` | he | 1920x1080 | 10.0 s | once (scrub) | 129 KB | 129 KB | 24 KB (frame 299: last frame: timeline + table) | §3.4 #showcase scene 3 |
| `SiteShow3-en` | en | 1920x1080 | 10.0 s | once (scrub) | 138 KB | 143 KB | 28 KB (frame 299: last frame: timeline + table) | §3.4 #showcase scene 3 |
| `SitePrivacy-he` | he | 900x900 | 7.0 s | seamless | 62 KB | 60 KB | 10 KB (frame 140: airplane mode + transcript) | §3.5 #privacy (ANIM-PRIVACY) |
| `SitePrivacy-en` | en | 900x900 | 7.0 s | seamless | 60 KB | 60 KB | 10 KB (frame 140: airplane mode + transcript) | §3.5 #privacy (ANIM-PRIVACY) |
| `SiteWordmark` | any | 256x256 | 1.2 s | one-shot | 10 KB | 21 KB | 18 KB (frame 35: rest state = design/logo.svg; WebM has alpha, MP4 on Paper) | header/footer logo |

Machine-readable list: `site-animations.json` (SPEC §4 rule).
