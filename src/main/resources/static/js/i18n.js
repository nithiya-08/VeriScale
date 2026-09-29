// Multi-language support (gettext style: the English text is the key).
//
// * Static text and text added later by JS are translated automatically: every text node,
//   placeholder, title and aria-label whose English text is in the dictionary is replaced.
// * Strings with variables use t('Expires in {n} days', { n: 5 }).
// * Mark user data (names, serial numbers...) with data-no-i18n so it is never touched.
// * Dictionaries live in /i18n/<code>.json and are cached (service worker + localStorage)
//   so the field app stays translated offline.
'use strict';

const I18N = (() => {
  const LANGS = [
    ['en', 'English', 'en-IN'], ['hi', 'हिन्दी', 'hi-IN'], ['ta', 'தமிழ்', 'ta-IN'], ['te', 'తెలుగు', 'te-IN'],
    ['kn', 'ಕನ್ನಡ', 'kn-IN'], ['ml', 'മലയാളം', 'ml-IN'], ['mr', 'मराठी', 'mr-IN'], ['bn', 'বাংলা', 'bn-IN'],
  ];
  const CODES = LANGS.map(l => l[0]);
  const ATTRS = ['placeholder', 'title', 'aria-label'];
  const SKIP = new Set(['SCRIPT', 'STYLE', 'TEXTAREA', 'CODE', 'PRE']);
  const norm = s => s.replace(/\s+/g, ' ').trim();

  function pick() {
    let saved = null;
    try { saved = localStorage.getItem('lm_lang'); } catch (e) { /* storage blocked */ }
    if (CODES.includes(saved)) return saved;
    const nav = (navigator.language || 'en').slice(0, 2).toLowerCase();
    return CODES.includes(nav) ? nav : 'en';
  }

  const lang = pick();
  const locale = () => LANGS.find(l => l[0] === lang)[2];
  let dict = {};
  // Hide the page briefly while a non-English dictionary loads, so English does not flash first.
  if (lang !== 'en') document.documentElement.classList.add('i18n-wait');

  // Sentences with variables that arrive already filled in (server alerts, error messages):
  // "Certificate LM-2026-X issued for ..." matches the key "Certificate {cert} issued for ...".
  let patterns = [];
  function buildPatterns() {
    patterns = Object.keys(dict).filter(k => k.includes('{')).map(k => {
      const names = [];
      // Greedy groups: "Electronic weighing scale (Class III) (ES-1)" must give type = "... (Class III)".
      const src = k.replace(/[.*+?^$()|[\]\\]/g, '\\$&').replace(/\\?\{(\w+)\\?\}/g, (m, n) => { names.push(n); return '(.+)'; });
      return { re: new RegExp('^' + src + '$'), names, key: k };
    }).sort((a, b) => b.key.length - a.key.length);
  }

  function translateSentence(key) {
    if (dict[key]) return dict[key];
    if (key.length < 8) return null;
    for (const p of patterns) {
      const m = p.re.exec(key);
      if (m) {
        const vars = {};
        // Captured values may themselves be translatable (instrument types, reasons...);
        // ISO dates from the server are shown in the chosen language.
        p.names.forEach((n, i) => {
          const v = m[i + 1];
          vars[n] = dict[v] || (/^\d{4}-\d{2}-\d{2}$/.test(v)
            ? new Date(v + 'T00:00:00').toLocaleDateString(locale(), { day: '2-digit', month: 'short', year: 'numeric' }) : v);
        });
        return dict[p.key].replace(/\{(\w+)\}/g, (s, n) => (n in vars ? vars[n] : s));
      }
    }
    return null;
  }

  function translateText(node) {
    const raw = node.nodeValue;
    const key = norm(raw);
    if (!key || !/[A-Za-z]/.test(key)) return;
    const tr = translateSentence(key);
    if (tr) node.nodeValue = raw.replace(raw.trim(), tr);
  }

  function translateAttrs(el) {
    for (const a of ATTRS) {
      const v = el.getAttribute && el.getAttribute(a);
      if (v && dict[norm(v)]) el.setAttribute(a, dict[norm(v)]);
    }
  }

  function walk(root) {
    if (lang === 'en' || !root) return;
    if (root.nodeType === Node.TEXT_NODE) {
      if (!root.parentElement || !root.parentElement.closest('[data-no-i18n]')) translateText(root);
      return;
    }
    if (root.nodeType !== Node.ELEMENT_NODE || SKIP.has(root.tagName) || root.closest('[data-no-i18n]')) return;
    translateAttrs(root);
    const tw = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT, {
      acceptNode(n) {
        if (n.nodeType === Node.ELEMENT_NODE) {
          if (SKIP.has(n.tagName) || n.hasAttribute('data-no-i18n')) return NodeFilter.FILTER_REJECT;
          return NodeFilter.FILTER_SKIP;
        }
        return NodeFilter.FILTER_ACCEPT;
      },
    });
    root.querySelectorAll('[placeholder],[title],[aria-label]').forEach(el => { if (!el.closest('[data-no-i18n]')) translateAttrs(el); });
    let n;
    while ((n = tw.nextNode())) translateText(n);
  }

  async function load() {
    document.documentElement.lang = lang;
    if (lang === 'en') return;
    const cacheKey = 'lm_i18n_' + lang;
    try { dict = JSON.parse(localStorage.getItem(cacheKey) || '{}'); } catch (e) { dict = {}; }
    try {
      const res = await fetch(`/i18n/${lang}.json`, { cache: 'no-cache' });
      if (res.ok) {
        dict = await res.json();
        try { localStorage.setItem(cacheKey, JSON.stringify(dict)); } catch (e) { /* quota */ }
      }
    } catch (e) { /* offline: keep the cached copy */ }
    buildPatterns();
  }

  const ready = load().then(() => {
    const start = () => {
      walk(document.body);
      new MutationObserver(muts => muts.forEach(m => {
        if (m.type === 'attributes') translateAttrs(m.target);
        else m.addedNodes.forEach(walk);
      })).observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ATTRS });
      document.documentElement.classList.remove('i18n-wait');
    };
    if (document.body) start(); else document.addEventListener('DOMContentLoaded', start);
  });
  // Never keep the page hidden if something goes wrong.
  setTimeout(() => document.documentElement.classList.remove('i18n-wait'), 1500);

  /** Translate a string, then fill {placeholders}. */
  function t(text, vars) {
    let s = (lang !== 'en' && translateSentence(norm(String(text)))) || text;
    if (vars) s = s.replace(/\{(\w+)\}/g, (m, k) => (k in vars ? vars[k] : m));
    return s;
  }

  function set(code) {
    try { localStorage.setItem('lm_lang', code); } catch (e) { /* ignore */ }
    location.reload();
  }

  /** Renders a language dropdown into el. style: 'light' (on dark navbar) or 'dark'. */
  function picker(el, style = 'light') {
    if (!el) return;
    const current = LANGS.find(l => l[0] === lang);
    el.innerHTML = `<div class="dropdown" data-no-i18n>
      <button class="lang-btn ${style}" type="button" data-bs-toggle="dropdown" aria-expanded="false" aria-label="Language / भाषा">
        <i class="bi bi-translate" aria-hidden="true"></i><span>${current[1]}</span></button>
      <ul class="dropdown-menu dropdown-menu-end shadow border-0 lang-menu">
        ${LANGS.map(([c, name]) => `<li><button class="dropdown-item d-flex justify-content-between ${c === lang ? 'active' : ''}" type="button" data-lang="${c}" lang="${c}">
          ${name}${c === lang ? '<i class="bi bi-check2"></i>' : ''}</button></li>`).join('')}
      </ul></div>`;
    el.addEventListener('click', ev => {
      const b = ev.target.closest('[data-lang]');
      if (b && b.dataset.lang !== lang) set(b.dataset.lang);
    });
  }

  return { lang, ready, t, set, picker, locale, LANGS };
})();

const t = I18N.t;
