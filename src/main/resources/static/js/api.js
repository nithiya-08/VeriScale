// Shared helpers for all pages: auth token, API calls, formatting, HTML escaping.
'use strict';

const API = {
  token() {
    try { return localStorage.getItem('lm_token'); } catch (e) { return null; }
  },
  user() {
    try { return JSON.parse(localStorage.getItem('lm_user') || 'null'); } catch (e) { return null; }
  },
  save(loginResponse) {
    localStorage.setItem('lm_token', loginResponse.token);
    localStorage.setItem('lm_user', JSON.stringify(loginResponse.user));
  },
  logout(redirect = true) {
    localStorage.removeItem('lm_token');
    localStorage.removeItem('lm_user');
    if (redirect) location.href = '/index.html';
  },

  /**
   * JSON API call. Throws Error(message) on HTTP errors; error.status holds the code.
   * Network failures throw error.offline = true (the caller decides what that means).
   */
  async call(path, { method = 'GET', body, redirectOn401 = true } = {}) {
    const headers = {};
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const t = API.token();
    if (t) headers.Authorization = 'Bearer ' + t;
    let res;
    try {
      res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
    } catch (e) {
      const err = new Error('No connection to the server');
      err.offline = true;
      throw err;
    }
    if (res.status === 401 && redirectOn401) {
      API.logout();
      throw new Error('Session expired, please log in again');
    }
    const text = await res.text();
    let data = null;
    if (text) {
      try { data = JSON.parse(text); } catch (e) { data = text; }
    }
    if (!res.ok) {
      const err = new Error((data && data.error) || ('Request failed (' + res.status + ')'));
      err.status = res.status;
      throw err;
    }
    return data;
  },

  /** Authenticated binary download (PDF, image, CSV). */
  async blob(path) {
    const res = await fetch(path, { headers: { Authorization: 'Bearer ' + API.token() } });
    if (res.status === 401) { API.logout(); throw new Error('Session expired'); }
    if (!res.ok) throw new Error('Download failed (' + res.status + ')');
    return res.blob();
  },
};

/** Redirects to login unless the stored user has one of the roles. */
function requireRole(...roles) {
  const u = API.user();
  if (!u || !API.token() || !roles.includes(u.role)) {
    location.href = '/login.html';
    throw new Error('redirecting');
  }
  return u;
}

function homeFor(role) {
  return { OWNER: '/owner.html', LMO: '/officer.html', GATC: '/officer.html', STATE_ADMIN: '/admin.html' }[role] || '/index.html';
}

function esc(v) {
  if (v === null || v === undefined) return '';
  return String(v).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

// Dates follow the chosen language (i18n.js); pages without it use English (India).
const uiLocale = () => (typeof I18N !== 'undefined' ? I18N.locale() : 'en-IN');
if (typeof t === 'undefined') window.t = (s, vars) => (vars ? s.replace(/\{(\w+)\}/g, (m, k) => (k in vars ? vars[k] : m)) : s);

function fmtDate(v) {
  if (!v) return '-';
  const d = new Date(v.length === 10 ? v + 'T00:00:00' : v);
  return d.toLocaleDateString(uiLocale(), { day: '2-digit', month: 'short', year: 'numeric' });
}

function fmtDateTime(v) {
  if (!v) return '-';
  // 24-hour time: avoids Latin "AM/PM" inside Indian-language dates.
  return new Date(v).toLocaleString(uiLocale(), { day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
}

function fmtToday() {
  return new Date().toLocaleDateString(uiLocale(), { weekday: 'long', day: 'numeric', month: 'long' });
}

function daysUntil(isoDate) {
  const today = new Date(); today.setHours(0, 0, 0, 0);
  return Math.round((new Date(isoDate + 'T00:00:00') - today) / 86400000);
}

// Status -> [tone, icon, label]. Status is always shown as icon + text, never colour alone.
const STATUS = {
  SUBMITTED: ['neutral', 'bi-send', 'Submitted'],
  ASSIGNED: ['info', 'bi-person-check', 'Assigned'],
  SCHEDULED: ['info', 'bi-calendar-event', 'Scheduled'],
  INSPECTED_PENDING_SYNC: ['warn', 'bi-cloud-arrow-up', 'Pending sync'],
  INSPECTED: ['info', 'bi-clipboard-check', 'Inspected'],
  PASSED: ['good', 'bi-check-circle', 'Passed'],
  FAILED: ['bad', 'bi-x-circle', 'Failed'],
  CERTIFIED: ['good', 'bi-patch-check', 'Certified'],
  DUE_SOON: ['warn', 'bi-hourglass-split', 'Due soon'],
  EXPIRED: ['neutral', 'bi-clock-history', 'Expired'],
  REVOKED: ['bad', 'bi-slash-circle', 'Revoked'],
  VALID: ['good', 'bi-shield-check', 'Valid'],
  OPEN: ['bad', 'bi-flag', 'Open'],
  REVIEWED: ['good', 'bi-check2', 'Reviewed'],
  DISMISSED: ['neutral', 'bi-dash-circle', 'Dismissed'],
};

function statusTone(status) {
  return (STATUS[status] || ['neutral'])[0];
}

function badge(status, extraClass = '') {
  const [tone, icon, label] = STATUS[status] || ['neutral', 'bi-circle', String(status).replace(/_/g, ' ')];
  return `<span class="pill ${tone} ${extraClass}"><i class="bi ${icon}" aria-hidden="true"></i>${esc(label)}</span>`;
}

const TOAST_ICONS = { success: 'bi-check-circle-fill', warning: 'bi-exclamation-triangle-fill', danger: 'bi-x-octagon-fill', info: 'bi-info-circle-fill' };

function toast(message, type = 'success') {
  let area = document.querySelector('.toast-area');
  if (!area) {
    area = document.createElement('div');
    area.className = 'toast-area';
    area.setAttribute('role', 'status');
    area.setAttribute('aria-live', 'polite');
    document.body.appendChild(area);
  }
  const el = document.createElement('div');
  el.className = `lm-toast ${type}`;
  el.innerHTML = `<i class="bi ${TOAST_ICONS[type] || TOAST_ICONS.info}" aria-hidden="true"></i><div></div>`;
  el.lastChild.textContent = message;
  area.appendChild(el);
  setTimeout(() => {
    el.classList.add('out');
    setTimeout(() => el.remove(), 350);
  }, type === 'danger' ? 7000 : 4200);
}

// ---------- UI helpers ----------
const REDUCED_MOTION = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

/** Animates the number in each [data-count] element from 0 to its value. */
function countUp(root = document) {
  root.querySelectorAll('[data-count]').forEach(el => {
    const to = Number(el.dataset.count) || 0;
    if (REDUCED_MOTION || to === 0) { el.textContent = to.toLocaleString('en-IN'); return; }
    const start = performance.now(), dur = 900;
    const step = now => {
      const t = Math.min(1, (now - start) / dur);
      el.textContent = Math.round(to * (1 - Math.pow(1 - t, 3))).toLocaleString('en-IN');
      if (t < 1) requestAnimationFrame(step);
    };
    requestAnimationFrame(step);
  });
}

function kpiTile(label, value, icon, tone, i = 0) {
  return `<div class="card kpi fade-up" style="--i:${i}">
      <span class="ibub ${tone}"><i class="bi ${icon}" aria-hidden="true"></i></span>
      <div><div class="value" data-count="${Number(value) || 0}">0</div><div class="label">${esc(label)}</div></div>
    </div>`;
}

function emptyState(icon, title, text = '') {
  return `<div class="empty fade-in"><span class="ibub lg navy"><i class="bi ${icon}" aria-hidden="true"></i></span>
    <h3>${esc(title)}</h3>${text ? `<div class="small">${esc(text)}</div>` : ''}</div>`;
}

function skeletonCards(n = 3, height = 120) {
  return Array.from({ length: n }, () => `<div class="col-md-6 col-xl-4"><div class="skel" style="height:${height}px"></div></div>`).join('');
}

function skeletonRows(n = 4, cols = 5) {
  return Array.from({ length: n }, () => `<tr>${Array.from({ length: cols }, () => '<td><div class="skel" style="height:14px"></div></td>').join('')}</tr>`).join('');
}

function initials(name) {
  return String(name || '?').split(/\s+/).filter(Boolean).map(p => p[0]).slice(-2).join('').toUpperCase();
}

/** Icon for an instrument type, by keyword. */
function typeIcon(typeName = '') {
  const n = typeName.toLowerCase();
  if (n.includes('fuel')) return 'bi-fuel-pump';
  if (n.includes('balance')) return 'bi-gem';
  if (n.includes('weight')) return 'bi-box-seam';
  if (n.includes('measure')) return 'bi-cup-straw';
  if (n.includes('beam') || n.includes('counter')) return 'bi-bar-chart-steps';
  return 'bi-speedometer2';
}

/** Puts a spinner in a button while an async action runs. */
async function withBusy(btn, fn) {
  const html = btn.innerHTML;
  btn.disabled = true;
  btn.innerHTML = '<span class="spinner-border" aria-hidden="true"></span>';
  try { return await fn(); } finally { btn.disabled = false; btn.innerHTML = html; }
}

/** Wires a form's submit to an async handler with a disabled button and error toast. */
function onSubmit(form, handler) {
  form.addEventListener('submit', async ev => {
    ev.preventDefault();
    const btn = form.querySelector('[type=submit]');
    const html = btn ? btn.innerHTML : '';
    if (btn) {
      btn.disabled = true;
      btn.innerHTML = '<span class="spinner-border me-2" aria-hidden="true"></span>' + html;
    }
    try {
      await handler(new FormData(form));
    } catch (e) {
      toast(e.message, 'danger');
    } finally {
      if (btn) { btn.disabled = false; btn.innerHTML = html; }
    }
  });
}

function numOrNull(v) {
  return v === null || v === undefined || String(v).trim() === '' ? null : Number(v);
}

function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10000);
}

function registerServiceWorker() {
  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('/sw.js').catch(() => { /* offline support unavailable */ });
  }
}
