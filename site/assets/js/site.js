// Page behaviour: header pill, language/theme/motion switches, reveals, lazy videos, showcase scrub, copy button.
const root = document.documentElement;
const he = root.lang === 'he';
const store = {
  get: (k) => { try { return localStorage.getItem(k); } catch { return null; } },
  set: (k, v) => { try { localStorage.setItem(k, v); } catch { /* private mode */ } },
};
const reduceMQ = matchMedia('(prefers-reduced-motion: reduce)');
const still = () => reduceMQ.matches || root.dataset.motion === 'off';
const fine = matchMedia('(hover: hover) and (pointer: fine)').matches;

// language: a click is a choice, remembered so the first-visit redirect never fires again
document.querySelectorAll('[data-lang]').forEach((a) => a.addEventListener('click', () => store.set('dibe-lang', a.dataset.lang)));

// theme
const themeBtn = document.querySelector('[data-theme-toggle]');
const isDark = () => root.dataset.theme ? root.dataset.theme === 'dark' : matchMedia('(prefers-color-scheme: dark)').matches;
const themeLabel = () => {
  if (!themeBtn) return;
  themeBtn.textContent = isDark() ? (he ? 'מצב בהיר' : 'Light mode') : (he ? 'מצב כהה' : 'Dark mode');
};
themeBtn?.addEventListener('click', () => {
  root.dataset.theme = isDark() ? 'light' : 'dark';
  store.set('dibe-theme', root.dataset.theme);
  themeLabel();
});
matchMedia('(prefers-color-scheme: dark)').addEventListener('change', themeLabel);
themeLabel();

// pause animations: every [data-motion-toggle] (hero + footer) drives one state; the label says what a press does
const motionBtns = document.querySelectorAll('[data-motion-toggle]');
const motionLabel = () => {
  const off = root.dataset.motion === 'off';
  motionBtns.forEach((b) => { b.textContent = off ? (he ? 'הפעילו אנימציות' : 'Play animations') : (he ? 'עצרו אנימציות' : 'Pause animations'); });
};
const applyMotion = () => {
  if (still()) document.querySelectorAll('video').forEach((v) => v.pause());
  else document.querySelectorAll('video[data-autoplay]').forEach(checkPlay);
};
motionBtns.forEach((b) => b.addEventListener('click', () => {
  if (root.dataset.motion === 'off') delete root.dataset.motion; else root.dataset.motion = 'off';
  store.set('dibe-motion', root.dataset.motion || 'on');
  motionLabel();
  applyMotion();
}));
reduceMQ.addEventListener('change', applyMotion); // OS setting flipped mid-visit
motionLabel();

// header download pill appears once the hero is gone
const pill = document.querySelector('.pill-cta');
const hero = document.getElementById('hero');
if (pill && hero) {
  new IntersectionObserver(([e]) => pill.classList.toggle('show', !e.isIntersecting), { threshold: 0 }).observe(hero);
}

// reveals: once, with a short stagger inside each group
const rv = new IntersectionObserver((entries) => {
  for (const e of entries) if (e.isIntersecting) { e.target.classList.add('in'); rv.unobserve(e.target); }
}, { rootMargin: '0px 0px -10% 0px' });
document.querySelectorAll('[data-reveal]').forEach((group) => {
  [...group.children].slice(0, 6).forEach((el, i) => { el.classList.add('rv'); el.style.setProperty('--i', i); rv.observe(el); });
});

// videos: graceful when a file is missing, never played when motion is off
document.querySelectorAll('.media video').forEach((v) => {
  const box = v.closest('.media');
  const sources = v.querySelectorAll('source');
  const last = sources[sources.length - 1];
  (last || v).addEventListener('error', () => box.classList.add('failed'));
  v.addEventListener('canplay', () => v.classList.add('ready'), { once: true });
});

const visible = new WeakMap();
function checkPlay(v) {
  if (still() || !visible.get(v)) { v.pause(); return; }
  if (v.dataset.once === 'done') return;
  if (v.preload === 'none') { v.preload = 'auto'; v.load(); }
  v.play().catch(() => {});
}
const vio = new IntersectionObserver((entries) => {
  for (const e of entries) { visible.set(e.target, e.intersectionRatio >= 0.6); checkPlay(e.target); }
}, { threshold: [0, 0.6] });

const scrub = fine && matchMedia('(min-width: 900px)').matches && !still();
document.querySelectorAll('video[data-autoplay]').forEach((v) => {
  const inCard = v.closest('.card');
  const inScene = v.closest('.scene');
  if (inCard && fine) return; // cards play on hover/focus on desktop
  if (inScene && scrub) return; // showcase is scrubbed by scroll on desktop
  if (inScene) v.addEventListener('ended', () => { v.dataset.once = 'done'; });
  vio.observe(v);
});

if (fine) {
  document.querySelectorAll('.card').forEach((card) => {
    const v = card.querySelector('video');
    if (!v) return;
    const on = () => { if (still()) return; if (v.preload === 'none') { v.preload = 'auto'; v.load(); } v.play().catch(() => {}); };
    const off = () => v.pause();
    card.addEventListener('pointerenter', on);
    card.addEventListener('pointerleave', off);
    card.addEventListener('focusin', on);
    card.addEventListener('focusout', off);
  });
}

// showcase: progress = scroll position inside each pinned scene
const showcase = document.getElementById('showcase');
if (showcase && scrub) {
  showcase.classList.add('scrub');
  const scenes = [...showcase.querySelectorAll('.scene')];
  const dots = [...showcase.querySelectorAll('.progress i')];
  const near = new IntersectionObserver((entries) => {
    for (const e of entries) {
      const v = e.target.querySelector('video');
      if (e.isIntersecting && v && v.preload === 'none') { v.preload = 'auto'; v.load(); }
    }
  }, { rootMargin: '100% 0px' });
  scenes.forEach((s) => near.observe(s));
  let ticking = false;
  const update = () => {
    ticking = false;
    if (still()) return;
    let active = 0;
    scenes.forEach((s, i) => {
      const r = s.getBoundingClientRect();
      const span = r.height - innerHeight;
      const p = Math.min(1, Math.max(0, -r.top / span));
      if (r.top < innerHeight / 2) active = i;
      const v = s.querySelector('video');
      if (v && v.readyState >= 1 && v.duration && r.bottom > 0 && r.top < innerHeight) {
        v.currentTime = p * (v.duration - 0.05);
        v.classList.add('ready');
      }
    });
    dots.forEach((d, i) => d.classList.toggle('on', i === active));
  };
  const queue = () => { if (!ticking) { ticking = true; requestAnimationFrame(update); } };
  addEventListener('scroll', queue, { passive: true });
  scenes.forEach((s) => s.querySelector('video')?.addEventListener('loadedmetadata', queue));
  update();
}

// copy the xattr command
document.querySelectorAll('[data-copy]').forEach((btn) => {
  btn.addEventListener('click', async () => {
    const text = document.getElementById(btn.dataset.copy)?.textContent || '';
    try {
      await navigator.clipboard.writeText(text);
      const old = btn.textContent;
      btn.textContent = he ? 'הועתק' : 'Copied';
      setTimeout(() => { btn.textContent = old; }, 1600);
    } catch { /* clipboard blocked: the command stays selectable */ }
  });
});
