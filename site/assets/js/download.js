// Picks the right DIBE download for this visitor and fills every download link on the page.
// Works without the GitHub API: the hard-coded 1.4.0 files below are the fallback.

const REPO = 'akucbruhu0-cyber/dibe';
const API = `https://api.github.com/repos/${REPO}/releases/latest`;
const MiB = 1048576; // GitHub shows sizes in MiB, and so does the spec
const FALLBACK_BASE = `https://github.com/${REPO}/releases/download/v1.4.0/`;

export const PATTERNS = {
  arm64: /^DIBE-.+-macos-arm64\.zip$/,
  intel: /^DIBE-.+-macos-intel\.zip$/,
  win: /^DIBE-.+-windows-x64-setup\.exe$/,
  apk: /^dibe-android-arm64\.apk$/,
};

export const FALLBACK = {
  version: '1.4.0',
  assets: {
    arm64: { name: 'DIBE-1.4.0-macos-arm64.zip', url: FALLBACK_BASE + 'DIBE-1.4.0-macos-arm64.zip', size: 38.2 * MiB },
    intel: { name: 'DIBE-1.4.0-macos-intel.zip', url: FALLBACK_BASE + 'DIBE-1.4.0-macos-intel.zip', size: 43.3 * MiB },
    win: { name: 'DIBE-1.4.0-windows-x64-setup.exe', url: FALLBACK_BASE + 'DIBE-1.4.0-windows-x64-setup.exe', size: 43.0 * MiB },
    apk: { name: 'dibe-android-arm64.apk', url: `https://github.com/${REPO}/releases/latest/download/dibe-android-arm64.apk`, size: 11.6 * MiB },
  },
};

// Pure: what the visitor is on. `arch` is the high-entropy hint ('arm', 'x86' or '').
// Returns { os, key, sure }; key is null when there is no single right file.
export function classify({ ua = '', platform = '', touch = 0, arch = '' }) {
  if (platform === 'Android' || /Android/i.test(ua)) return { os: 'android', key: 'apk', sure: true };
  if (platform === 'iOS' || /iPhone|iPad|iPod/.test(ua) || (/Macintosh/.test(ua) && touch > 1)) return { os: 'ios', key: null, sure: true };
  if (platform === 'Windows' || /Windows NT/.test(ua)) return { os: 'windows', key: 'win', sure: true };
  if (platform === 'macOS' || /Macintosh|Mac OS X/.test(ua)) {
    if (arch === 'x86') return { os: 'mac', key: 'intel', sure: true };
    // Safari never reports the CPU; Apple Silicon is the tested build, so it is the default.
    return { os: 'mac', key: 'arm64', sure: arch === 'arm' };
  }
  return { os: 'other', key: null, sure: false };
}

// Pure: map a GitHub release JSON onto our four keys, keeping fallback entries for anything missing.
export function pickAssets(release) {
  const out = { version: FALLBACK.version, assets: { ...FALLBACK.assets } };
  if (!release || !Array.isArray(release.assets)) return out;
  if (typeof release.tag_name === 'string') out.version = release.tag_name.replace(/^v/, '');
  for (const [key, re] of Object.entries(PATTERNS)) {
    const a = release.assets.find((x) => re.test(x.name));
    if (a) out.assets[key] = { name: a.name, url: a.browser_download_url, size: a.size };
  }
  return out;
}

export const mb = (bytes) => `${Math.max(1, Math.round(bytes / MiB))} MB`;

const TEXT = {
  he: {
    arm64: ['הורידו ל-', 'macOS', 'macOS (Apple Silicon)'],
    intel: ['הורידו ל-', 'macOS', 'macOS (Intel)'],
    win: ['הורידו ל-', 'Windows', 'Windows'],
    apk: ['הורידו לאנדרואיד', '', ''],
    generic: 'הורידו את DIBE',
    aria: (n, s) => `${n}, ${s}, הורדה`, // visible text first (WCAG 2.5.3)
    ios: 'אין גרסה ל-iPhone ול-iPad. אפשר להוריד את DIBE למחשב מק או Windows, או לטלפון אנדרואיד.',
    other: 'לא זיהינו את המחשב שלכם. בחרו גרסה מהרשימה.',
    intelHint: 'Mac עם Intel? בחרו ב"כל הגרסאות".',
  },
  en: {
    arm64: ['Download for ', 'macOS', 'macOS (Apple Silicon)'],
    intel: ['Download for ', 'macOS', 'macOS (Intel)'],
    win: ['Download for ', 'Windows', 'Windows'],
    apk: ['Download for Android', '', ''],
    generic: 'Download DIBE',
    aria: (n, s) => `${n}, ${s}, download`, // visible text first (WCAG 2.5.3)
    ios: 'There is no iPhone or iPad version. Get DIBE for a Mac or Windows computer, or an Android phone.',
    other: 'We could not tell which computer you are on. Pick a version from the list.',
    intelHint: 'Intel Mac? Pick it under "All versions".',
  },
};

function setLabel(el, pre, ltr) {
  el.textContent = pre;
  if (ltr) {
    const s = document.createElement('span');
    s.dir = 'ltr';
    s.lang = 'en';
    s.textContent = ltr;
    el.append(s);
  }
}

function apply(data, who) {
  const lang = document.documentElement.lang === 'he' ? 'he' : 'en';
  const t = TEXT[lang];
  document.querySelectorAll('[data-version]').forEach((n) => { n.textContent = data.version; });

  for (const [key, a] of Object.entries(data.assets)) {
    document.querySelectorAll(`[data-asset="${key}"]`).forEach((row) => {
      const link = row.matches('a') ? row : row.querySelector('a');
      if (link) { link.href = a.url; link.setAttribute('aria-label', t.aria(a.name, mb(a.size))); }
      row.querySelectorAll('[data-asset-name]').forEach((n) => { n.textContent = a.name; });
      row.querySelectorAll('[data-asset-size]').forEach((n) => { n.textContent = mb(a.size); });
    });
  }

  const auto = document.getElementById('dl-auto');
  const pick = who.key && data.assets[who.key];
  document.querySelectorAll('[data-cta]').forEach((cta) => {
    const label = cta.querySelector('[data-cta-label]') || cta;
    if (!pick) { label.textContent = t.generic; cta.href = '#download'; cta.removeAttribute('aria-label'); return; }
    const [pre, short, long] = t[who.key];
    setLabel(label, pre, cta.hasAttribute('data-cta-long') ? long : short);
    cta.href = pick.url;
    cta.setAttribute('aria-label', `${label.textContent}, ${pick.name}, ${mb(pick.size)}`);
  });

  if (!auto) return;
  auto.hidden = false;
  auto.dataset.key = who.key || 'none';
  auto.querySelector('[data-auto-yes]').hidden = !pick;
  const note = auto.querySelector('[data-auto-note]');
  note.hidden = pick && who.sure;
  if (!pick) note.textContent = who.os === 'ios' ? t.ios : t.other;
  else if (!who.sure) note.textContent = t.intelHint;
  if (pick) {
    auto.querySelector('[data-auto-file]').textContent = pick.name;
    auto.querySelector('[data-auto-size]').textContent = mb(pick.size);
  }
}

async function detect(nav) {
  const uad = nav.userAgentData;
  let arch = '';
  try {
    if (uad && uad.getHighEntropyValues) arch = (await uad.getHighEntropyValues(['architecture'])).architecture || '';
  } catch { /* the hint is optional */ }
  return classify({ ua: nav.userAgent || '', platform: (uad && uad.platform) || '', touch: nav.maxTouchPoints || 0, arch });
}

async function latest() {
  try {
    const signal = typeof AbortSignal !== 'undefined' && AbortSignal.timeout ? AbortSignal.timeout(6000) : undefined;
    const r = await fetch(API, { signal, headers: { Accept: 'application/vnd.github+json' } });
    if (!r.ok) throw new Error(String(r.status));
    return pickAssets(await r.json());
  } catch {
    return pickAssets(null);
  }
}

export async function init() {
  const who = await detect(navigator);
  apply(pickAssets(null), who); // instant, from the fallback
  apply(await latest(), who); // then the real release, if GitHub answers
}

if (typeof document !== 'undefined') init();
