# Free Gemini key → DIBE: the flow the tutorial shows

Checked 2026-11-12 against Google's docs (aistudio.google.com/docs: models, pricing, rate-limits,
google-ai-plans, api-key) and a real free-tier project in AI Studio.

## Steps

1. **Google AI Studio**: open `aistudio.google.com/apikey` and sign in with any Google account.
   On first sign-in you accept the terms, and AI Studio creates a default project **and a first key** by itself.
2. **Create a key** (only if the list is empty or you want a new one): `Create API key` → name it → `Create key`.
3. **Copy the key**: the copy icon in the key's row. Keep it private, like a password.
4. **Paste in DIBE**
   - Desktop: Settings → עוזר AI → פלטפורמה: Gemini → מפתח API של Gemini.
   - Android: Settings → סיכום AI → Gemini → מפתח API.
5. **Pick the model** `gemini-3-flash-preview` (Gemini 3 Flash Preview). It shows in the list, badged
   "חינמי", once a key is pasted (the list is loaded from the key). It can also be typed by hand.
6. **Test**: בדיקת חיבור → "עובד". From then on every recording gets a summary.

## Facts the video states

| Claim | Source |
|---|---|
| `gemini-3-flash-preview` is on the free tier (input and output "Free of charge") | pricing page |
| Free tier needs no billing / credit card | rate-limits page: Free tier = active project, no billing |
| Free tier quota for this model: 5 RPM, 250K TPM, **20 requests/day** | AI Studio → Rate Limit, on a free-tier project |
| A Google AI Pro subscription does **not** change API-key limits ("AI Studio UI only") | google-ai-plans page |
| On the free tier, content may be used to improve Google's products | pricing page, "Used to improve our products: Yes" |
| New users get a default project and key after accepting the terms | api-key page |

Not in the video, but worth knowing: Google calls `gemini-3-flash-preview` its *legacy* Flash model and
says preview models have tighter limits. DIBE's default, `gemini-3.8-flash`, is newer and also free.
`gemini-3.1-pro-preview` has **no** free tier.

## Assets

- `shots/`: real screenshots. AI Studio (masked: email, project ids, key suffix) and the DIBE desktop
  settings from the dev preview (`desktop-mock`), with a fake key typed in.
- No Android device was available, so the phone video rebuilds the AI card from
  `AiSettingsSection.kt` and its Hebrew strings (`src/gemini-key/android.tsx`).
- Source: `src/gemini-key/` (compositions `GeminiKeyDesktop`, `GeminiKeyPhone`).
- Render into both apps: `pnpm render:gemini-key`.
