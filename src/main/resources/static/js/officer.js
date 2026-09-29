// Offline-first field inspection (LMO / GATC) as a 4-step wizard:
//   1 Arrive (GPS, name plate, owner documents) -> 2 Readings -> 3 Photos & remarks -> 4 Review -> Result
// Saved to IndexedDB first (INSPECTED_PENDING_SYNC), then synced on the "online" event, on app open,
// every minute, or by tapping "Sync now". Background Sync API is not used (unsupported on Safari / iOS).
'use strict';

const me = requireRole('LMO', 'GATC');
registerServiceWorker();
const $ = id => document.getElementById(id);
I18N.picker($('langPicker'));
$('whoami').textContent = `${me.name} · ${me.role}${me.district ? ' · ' + me.district : ''}`;
$('todayDate').textContent = fmtToday();
$('logoutBtn').onclick = async () => {
  const pending = await IDB.all('pending');
  if (pending.length && !confirm(t('{n} inspection(s) are not synced yet. They stay on this phone. Log out anyway?', { n: pending.length }))) return;
  API.logout();
};

const STEP_NAMES = ['Arrive', 'Readings', 'Evidence', 'Review', 'Result'];
let current = null; // inspection being recorded
let syncing = false;
const wizard = new Wizard($('inspSteps'), $('inspPanels'), STEP_NAMES.map(n => t(n)), i => goStep(i));

// ---------- connectivity ----------
function updateConn(serverReachable = true) {
  const b = $('connBadge');
  const online = navigator.onLine;
  b.className = 'conn' + (!online ? ' off' : serverReachable ? '' : ' warn');
  b.querySelector('.t').textContent = t(!online ? 'Offline' : serverReachable ? 'Online' : 'No server');
  $('offlineBar').classList.toggle('show', !online);
}
window.addEventListener('online', () => { updateConn(); syncPending(); });
window.addEventListener('offline', () => updateConn());
updateConn();

// ---------- install prompt (Android Chrome) ----------
let installEvent = null;
window.addEventListener('beforeinstallprompt', e => {
  e.preventDefault();
  installEvent = e;
  $('installBtn').classList.remove('d-none');
});
$('installBtn').onclick = async () => {
  if (!installEvent) return;
  installEvent.prompt();
  await installEvent.userChoice;
  installEvent = null;
  $('installBtn').classList.add('d-none');
};

// ---------- assignment list ----------
async function renderList() {
  const [assignments, pending, meta] = await Promise.all([IDB.all('assignments'), IDB.all('pending'), IDB.get('meta', 'downloadedAt')]);
  const pendingIds = new Set(pending.map(p => p.assignmentId));
  $('downloadedAt').textContent = meta ? t('Updated {time}', { time: fmtDateTime(meta.value) }) : t('Download while online to work offline');

  const open = assignments.filter(a => !pendingIds.has(a.assignmentId));
  open.sort((a, b) => (a.scheduledDate || '').localeCompare(b.scheduledDate || ''));
  $('openCount').textContent = open.length;
  $('openLabel').textContent = t(open.length === 1 ? 'inspection on this phone' : 'inspections on this phone');

  $('assignmentList').innerHTML = open.map((a, n) => {
    const d = daysUntil(a.scheduledDate);
    const when = d === 0 ? '<span class="pill info"><i class="bi bi-calendar-event"></i>Today</span>'
      : d < 0 ? `<span class="pill bad"><i class="bi bi-exclamation-circle"></i>${esc(t('{n}d overdue', { n: -d }))}</span>`
        : `<span class="pill neutral"><i class="bi bi-calendar"></i>${esc(fmtDate(a.scheduledDate))}</span>`;
    const docs = (a.documents || []).length;
    return `<button type="button" class="job fade-up" style="--i:${n}" data-start="${a.assignmentId}">
      <span class="ibub navy"><i class="bi ${typeIcon(a.instrument.type.name)}"></i></span>
      <div class="min-w-0">
        <div class="fw-bold" data-no-i18n>${esc(a.business.name)}</div>
        <div class="small"><span>${esc(a.instrument.type.name)}</span> · <span data-no-i18n>${esc(a.instrument.serialNo)}</span></div>
        <div class="small text-muted text-truncate" data-no-i18n><i class="bi bi-geo-alt"></i> ${esc(a.business.address || '')}, ${esc(a.business.district)}</div>
        <div class="mt-1 d-flex flex-wrap gap-2">${when}<span class="pill neutral">${a.applicationType === 'NEW' ? 'New' : 'Re-verification'}</span>
          ${docs ? `<span class="pill neutral"><i class="bi bi-paperclip"></i>${docs}</span>` : ''}</div>
      </div>
      <i class="bi bi-chevron-right go"></i>
    </button>`;
  }).join('') || `<div class="card">${emptyState('bi-cup-hot', 'All clear', 'No open assignments on this phone. Tap Download to check for new ones.')}</div>`;

  $('pendingCard').classList.toggle('d-none', pending.length === 0);
  $('pendingInfo').textContent = t(pending.length === 1 ? '{n} inspection saved on this phone' : '{n} inspections saved on this phone', { n: pending.length });
  $('pendingList').innerHTML = pending.map(p => `
    <div class="d-flex justify-content-between align-items-center gap-2 small p-2 pending-row">
      <div class="min-w-0"><div class="fw-semibold text-truncate" data-no-i18n>${esc(p.label)}</div>
        ${badge('INSPECTED_PENDING_SYNC', 'pulse')}
        ${p.lastError ? `<div class="text-danger mt-1"><i class="bi bi-exclamation-circle"></i> ${esc(t(p.lastError))}</div>` : ''}</div>
      ${badge(p.localResult === 'PASS' ? 'PASSED' : 'FAILED')}
    </div>`).join('');
}

$('assignmentList').addEventListener('click', ev => {
  const el = ev.target.closest('[data-start]');
  if (el) startInspection(Number(el.dataset.start));
});

$('downloadBtn').onclick = ev => withBusy(ev.currentTarget, async () => {
  try {
    const list = await API.call('/api/officer/assignments/today', { redirectOn401: false });
    await IDB.replaceAssignments(list);
    await IDB.put('meta', { key: 'downloadedAt', value: new Date().toISOString() });
    updateConn(true);
    toast(t(list.length === 1 ? '{n} assignment saved for offline use' : '{n} assignments saved for offline use', { n: list.length }));
    renderList();
  } catch (e) {
    if (e.status === 401) return showRelogin();
    if (e.offline) updateConn(false);
    toast(e.offline ? t('No connection. Showing assignments already on this phone.') : t(e.message), e.offline ? 'warning' : 'danger');
  }
});

// =====================================================================
// Inspection wizard
// =====================================================================
async function startInspection(assignmentId) {
  const a = await IDB.get('assignments', assignmentId);
  if (!a) return;
  current = {
    assignment: a,
    clientUuid: crypto.randomUUID ? crypto.randomUUID() : fallbackUuid(),
    startedAt: new Date().toISOString(),
    gps: null,
    photos: [],
    saved: false,
  };
  const i = a.instrument;
  $('inspectHeader').innerHTML = `<div class="card-body d-flex gap-3 align-items-center">
      <span class="ibub lg navy"><i class="bi ${typeIcon(i.type.name)}"></i></span>
      <div class="flex-grow-1 min-w-0">
        <div class="d-flex justify-content-between gap-2 flex-wrap"><h1 class="h5 fw-bold mb-0" data-no-i18n>${esc(a.business.name)}</h1>${badge(a.applicationStatus)}</div>
        <div class="small"><span>${esc(i.type.name)}</span> · <span data-no-i18n>${esc(i.serialNo)}</span></div>
        <div class="small text-muted text-truncate" data-no-i18n><i class="bi bi-geo-alt"></i> ${esc(a.business.address || '')}, ${esc(a.business.district)}</div>
      </div></div>`;
  const plate = [['Serial', i.serialNo], ['Make / model', [i.make, i.model].filter(Boolean).join(' ') || '-'],
    ['Capacity', t('{min} to {max} {unit}', { min: i.capacityMin ?? '-', max: i.capacityMax ?? '-', unit: i.type.unit })],
    ['Model approval', i.modelApprovalNo || '-'], ['Accuracy class', i.type.accuracyClass || '-'], ['Interval (e)', i.eValue ? `${i.eValue} ${i.type.unit}` : '-']];
  $('plateInfo').innerHTML = plate.map(([k, v]) => `<div class="col-6 col-md-4"><div class="p-2 soft-tile">
    <div class="small-caps">${esc(k)}</div><div class="fw-semibold" data-no-i18n>${esc(v)}</div></div></div>`).join('');
  const docs = a.documents || [];
  $('ownerDocs').innerHTML = docs.map(d => `
    <button type="button" class="choice py-2" data-doc="${d.id}">
      <span class="ibub sm ${d.contentType === 'application/pdf' ? 'bad' : 'info'}"><i class="bi ${d.contentType === 'application/pdf' ? 'bi-file-earmark-pdf' : 'bi-file-earmark-image'}"></i></span>
      <span class="min-w-0"><span class="d-block fw-semibold small">${esc(d.label)}</span><span class="d-block small text-muted text-truncate" data-no-i18n>${esc(d.fileName)}</span></span>
      <i class="bi bi-box-arrow-up-right ms-auto text-muted"></i></button>`).join('')
    || `<div class="small text-muted"><i class="bi bi-folder2"></i> ${esc(t('The owner has not uploaded any documents.'))}</div>`;

  $('ruleInfo').textContent = ErrorCalc.describe(i);
  $('readingRows').innerHTML = '';
  ErrorCalc.suggestedLoads(i).forEach(load => addReadingRow(load));
  $('remarks').value = '';
  $('photoThumbs').querySelectorAll('img').forEach(img => img.remove());
  updatePhotoCount();
  updateVerdict();
  captureGps();
  wizard.reset();
  $('listView').classList.add('d-none');
  $('inspectView').classList.remove('d-none');
  goStep(0);
}

function goStep(i) {
  if (i === 3) renderReview();
  wizard.go(i, { lock: i === 4 });
  const next = $('inspNext');
  $('inspFoot').classList.toggle('d-none', i === 4);
  $('inspBack').classList.toggle('d-none', i === 0);
  next.classList.toggle('btn-success', i === 3);
  next.classList.toggle('btn-lm', i !== 3);
  next.innerHTML = i === 3 ? `<i class="bi bi-save me-2"></i>${esc(t('Save inspection'))}`
    : `${esc(t(['I am at the premises', 'Continue to evidence', 'Review inspection'][i]))} <i class="bi bi-arrow-right ms-1"></i>`;
  $('inspHint').textContent = [t('GPS works without internet'), t('All readings are needed to continue'),
    t('Photos are optional but recommended'), t('Saved on the phone first; the certificate is issued after sync'), ''][i];
  window.scrollTo({ top: 0, behavior: REDUCED_MOTION ? 'auto' : 'smooth' });
}

$('inspBack').onclick = () => goStep(Math.max(0, wizard.current - 1));
$('inspNext').onclick = () => {
  const step = wizard.current;
  if (step === 0 && !current.gps && !confirm(t('GPS location was not captured. Continue without location? (This will be flagged for review.)'))) return;
  if (step === 1) {
    const rows = readRows();
    const missing = rows.find(r => r.testLoad === null || r.indicatedValue === null);
    if (!rows.length || missing) {
      if (missing) missing.el.animate([{ transform: 'translateX(-6px)' }, { transform: 'translateX(6px)' }, { transform: 'none' }], { duration: 300 });
      return toast(t('Fill in both test load and indicated value for every reading'), 'warning');
    }
  }
  if (step === 3) return saveInspection();
  goStep(step + 1);
};

$('ownerDocs').addEventListener('click', async ev => {
  const b = ev.target.closest('[data-doc]');
  if (!b) return;
  if (!navigator.onLine) return toast(t('Documents need a connection to open'), 'warning');
  try { window.open(URL.createObjectURL(await API.blob('/api/documents/' + b.dataset.doc)), '_blank'); } catch (e) { toast(t(e.message), 'danger'); }
});

// ---------- readings ----------
function addReadingRow(load = '') {
  const unit = esc(current.assignment.instrument.type.unit || '');
  const n = $('readingRows').children.length + 1;
  const div = document.createElement('div');
  div.className = 'reading';
  div.innerHTML = `
    <div><label class="form-label small mb-1">${esc(t('Test load {n}', { n }))}</label>
      <div class="input-group"><input type="number" step="any" inputmode="decimal" class="form-control load" value="${esc(load)}" aria-label="Test load"><span class="input-group-text">${unit}</span></div></div>
    <div><label class="form-label small mb-1">Indicated</label>
      <div class="input-group"><input type="number" step="any" inputmode="decimal" class="form-control indicated" aria-label="Indicated value" placeholder="reading"><span class="input-group-text">${unit}</span></div></div>
    <div class="res d-flex align-items-center gap-2 justify-content-end">
      <span class="out text-muted">waiting</span>
      <button type="button" class="btn btn-sm btn-link text-danger p-0 remove" aria-label="Remove reading"><i class="bi bi-trash"></i></button>
    </div>`;
  $('readingRows').appendChild(div);
}

function readRows() {
  return [...$('readingRows').children].map(el => ({
    el,
    testLoad: numOrNull(el.querySelector('.load').value),
    indicatedValue: numOrNull(el.querySelector('.indicated').value),
  }));
}

let lastVerdict = '';
function updateVerdict() {
  const rows = readRows();
  let complete = 0, failed = 0;
  rows.forEach(r => {
    const out = r.el.querySelector('.out');
    r.el.classList.remove('ok', 'ko');
    if (r.testLoad === null || r.indicatedValue === null) { out.className = 'out text-muted'; out.textContent = t('waiting'); return; }
    const c = ErrorCalc.check(current.assignment.instrument, r.testLoad, r.indicatedValue);
    out.className = 'out';
    out.innerHTML = `<b style="color:${c.within ? 'var(--st-good-ink)' : 'var(--st-bad-ink)'}"><i class="bi ${c.within ? 'bi-check-circle-fill' : 'bi-x-circle-fill'}"></i> ${esc(t(c.within ? 'Within limit' : 'Out of limit'))}</b>
      <span class="text-muted">${esc(t('error {e} / ±{p}', { e: (c.error > 0 ? '+' : '') + c.error, p: c.permissible }))}</span>`;
    // Re-adding the class in the same frame does not restart the CSS shake, so a row shakes once when it flips to failing.
    r.el.classList.add(c.within ? 'ok' : 'ko');
    complete++;
    if (!c.within) failed++;
  });
  const box = $('verdictBox');
  const verdict = !complete ? 'idle' : failed ? 'fail' : 'pass';
  box.className = 'verdict-live ' + verdict;
  box.innerHTML = verdict === 'idle' ? `<i class="bi bi-pencil"></i> ${esc(t('Enter the indicated value for each test load'))}`
    : verdict === 'fail' ? `<i class="bi bi-x-octagon-fill me-2"></i>${esc(t(failed === 1 ? 'FAIL · {n} reading outside the permissible error' : 'FAIL · {n} readings outside the permissible error', { n: failed }))}`
      : `<i class="bi bi-check-circle-fill me-2"></i>${esc(t(complete === 1 ? 'PASS · {n} reading within limits' : 'PASS · all {n} readings within limits', { n: complete }))}`;
  if (verdict !== lastVerdict && verdict !== 'idle') { box.style.animation = 'none'; void box.offsetWidth; box.style.animation = ''; }
  lastVerdict = verdict;
}

$('readingRows').addEventListener('input', updateVerdict);
$('readingRows').addEventListener('click', ev => {
  const rm = ev.target.closest('.remove');
  if (rm) { rm.closest('.reading').remove(); updateVerdict(); }
});
$('addRowBtn').onclick = () => { addReadingRow(); updateVerdict(); $('readingRows').lastElementChild.querySelector('.load').focus(); };
$('backBtn').onclick = () => {
  if (!current.saved && readRows().some(r => r.indicatedValue !== null) && !confirm(t('Discard this inspection?'))) return;
  closeInspection();
};

function closeInspection() {
  current = null;
  $('inspectView').classList.add('d-none');
  $('listView').classList.remove('d-none');
  window.scrollTo({ top: 0 });
  renderList();
}

// ---------- GPS (works offline on phones) ----------
function captureGps() {
  const info = $('gpsInfo');
  if (!navigator.geolocation) { info.innerHTML = `<i class="bi bi-geo text-muted"></i> ${esc(t('GPS not available on this device'))}`; return; }
  info.innerHTML = `<span class="spinner-border spinner-border-sm text-primary" aria-hidden="true"></span> <span>${esc(t('Capturing GPS...'))}</span>`;
  navigator.geolocation.getCurrentPosition(p => {
    if (!current) return;
    current.gps = { lat: p.coords.latitude, lng: p.coords.longitude, acc: p.coords.accuracy };
    const b = current.assignment.business;
    let dist = '';
    if (b.lat != null) {
      const m = Math.round(distance(p.coords.latitude, p.coords.longitude, b.lat, b.lng));
      dist = `<div class="${m > 500 ? 'text-danger' : 'text-success'}">${esc(t(m > 500 ? '{m} m from the registered shop location (will be flagged)'
        : '{m} m from the registered shop location', { m }))}</div>`;
    }
    info.innerHTML = `<i class="bi bi-geo-alt-fill text-success fs-4"></i><div><strong data-no-i18n>${p.coords.latitude.toFixed(5)}, ${p.coords.longitude.toFixed(5)}</strong>
      <span class="text-muted">(±${Math.round(p.coords.accuracy)} m)</span>${dist}</div>`;
  }, err => {
    info.innerHTML = `<i class="bi bi-geo text-danger fs-4"></i><div><strong class="text-danger">${esc(t('GPS not captured'))}</strong>
      <div class="text-muted">${esc(t('Allow location access and tap Capture again.'))}</div></div>`;
  }, { enableHighAccuracy: true, timeout: 20000, maximumAge: 60000 });
}
$('gpsBtn').onclick = captureGps;

function distance(lat1, lng1, lat2, lng2) {
  const r = 6371000, toRad = d => d * Math.PI / 180;
  const a = Math.sin(toRad(lat2 - lat1) / 2) ** 2 + Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(toRad(lng2 - lng1) / 2) ** 2;
  return 2 * r * Math.asin(Math.sqrt(a));
}

// ---------- photos: compressed on the phone to ~1280px JPEG ----------
function updatePhotoCount() {
  const n = current ? current.photos.length : 0;
  $('photoCount').textContent = `${n} / 6`;
  $('photoThumbs').querySelector('.photo-add').classList.toggle('d-none', n >= 6);
}

$('photoInput').addEventListener('change', async ev => {
  for (const file of ev.target.files) {
    if (current.photos.length >= 6) { toast(t('Maximum 6 photos'), 'warning'); break; }
    try {
      const dataUrl = await compress(file, 1280, 0.72);
      current.photos.push({ dataUrl, capturedAt: new Date().toISOString(), lat: current.gps?.lat ?? null, lng: current.gps?.lng ?? null });
      const img = document.createElement('img');
      img.src = dataUrl;
      img.className = 'photo-tile border';
      img.alt = 'Inspection photo';
      $('photoThumbs').insertBefore(img, $('photoThumbs').querySelector('.photo-add'));
    } catch (e) {
      toast(t('Could not read that photo'), 'danger');
    }
  }
  updatePhotoCount();
  ev.target.value = '';
});

function compress(file, maxSide, quality) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
      const scale = Math.min(1, maxSide / Math.max(img.width, img.height));
      const c = document.createElement('canvas');
      c.width = Math.round(img.width * scale);
      c.height = Math.round(img.height * scale);
      c.getContext('2d').drawImage(img, 0, 0, c.width, c.height);
      URL.revokeObjectURL(url);
      resolve(c.toDataURL('image/jpeg', quality));
    };
    img.onerror = () => { URL.revokeObjectURL(url); reject(new Error('bad image')); };
    img.src = url;
  });
}

// ---------- review ----------
function renderReview() {
  const inst = current.assignment.instrument;
  const rows = readRows().filter(r => r.testLoad !== null && r.indicatedValue !== null);
  const checks = rows.map(r => ({ r, c: ErrorCalc.check(inst, r.testLoad, r.indicatedValue) }));
  const failed = checks.filter(x => !x.c.within).length;
  const pass = failed === 0;
  $('reviewVerdict').innerHTML = `<div class="big-verdict ${pass ? 'pass' : 'fail'}">
    <i class="bi ${pass ? 'bi-patch-check-fill' : 'bi-x-octagon-fill'} fs-1"></i>
    <div class="v">${pass ? 'PASS' : 'FAIL'}</div>
    <div>${esc(pass ? t('All readings are within the permissible error. A certificate will be issued after sync.')
      : t(failed === 1 ? '{n} reading is outside the permissible error. The owner will be asked to repair and re-apply.'
        : '{n} readings are outside the permissible error. The owner will be asked to repair and re-apply.', { n: failed }))}</div></div>`;
  const item = (ok, text, i) => `<li style="animation-delay:${i * 70}ms"><i class="bi ${ok ? 'bi-check-circle-fill ok' : 'bi-exclamation-triangle-fill no'} fs-5"></i><span>${esc(text)}</span></li>`;
  $('reviewChecks').innerHTML = [
    item(!!current.gps, current.gps ? t('Location captured') : t('No GPS location (will be flagged for review)'), 0),
    item(true, t(rows.length === 1 ? '{n} reading recorded' : '{n} readings recorded', { n: rows.length }), 1),
    item(current.photos.length > 0, current.photos.length ? t(current.photos.length === 1 ? '{n} photo attached' : '{n} photos attached', { n: current.photos.length }) : t('No photos attached'), 2),
    item(!!$('remarks').value.trim(), $('remarks').value.trim() ? t('Remarks added') : t('No remarks'), 3),
  ].join('');
  $('reviewReadings').innerHTML = checks.map(({ r, c }) => `<tr class="${c.within ? '' : 'table-danger'}">
    <td>${r.testLoad}</td><td>${r.indicatedValue}</td><td>${c.error > 0 ? '+' : ''}${c.error}</td><td>${c.permissible}</td>
    <td>${badge(c.within ? 'PASSED' : 'FAILED')}</td></tr>`).join('');
}

// ---------- save (always local first) and show the result ----------
async function saveInspection() {
  const inst = current.assignment.instrument;
  const rows = readRows().filter(r => r.testLoad !== null && r.indicatedValue !== null);
  const localResult = rows.every(r => ErrorCalc.check(inst, r.testLoad, r.indicatedValue).within) ? 'PASS' : 'FAIL';
  const record = {
    clientUuid: current.clientUuid,
    assignmentId: current.assignment.assignmentId,
    startedAt: current.startedAt,
    completedAt: new Date().toISOString(),
    gpsLat: current.gps?.lat ?? null,
    gpsLng: current.gps?.lng ?? null,
    remarks: $('remarks').value.trim() || null,
    observations: rows.map(r => ({ testLoad: r.testLoad, indicatedValue: r.indicatedValue })),
    photos: current.photos,
    // Local-only fields (stripped before upload):
    label: `${current.assignment.business.name} · ${inst.serialNo}`,
    localResult,
  };
  try {
    await IDB.put('pending', record);
  } catch (e) {
    return toast(t('Could not save on this phone (storage full?). Remove some photos and try again.'), 'danger');
  }
  current.saved = true;
  goStep(4);
  showResult('saving');
  await new Promise(r => setTimeout(r, REDUCED_MOTION ? 0 : 700));
  if (!navigator.onLine) return showResult('queued');
  showResult('syncing');
  const results = await syncPending();
  const mine = results && results.find(r => r.clientUuid === record.clientUuid);
  if (!mine) showResult('queued');
  else if (mine.outcome === 'SYNCED' || mine.outcome === 'DUPLICATE') showResult(mine.result === 'PASS' ? 'certified' : 'failed', mine);
  else showResult('queued', mine);
}

function showResult(kind, r) {
  const conf = {
    saving: ['saving', 'bi-phone', 'Saving on this phone...', ''],
    syncing: ['saving syncing', 'bi-cloud-arrow-up', 'Syncing with the server...', 'Checking readings and issuing the certificate'],
    queued: ['queued', 'bi-cloud-slash', 'Saved on this phone', 'It will sync automatically when you are back online. You can continue with the next inspection.'],
    certified: ['done', 'bi-patch-check-fill', 'Inspection passed', 'The certificate has been issued and the owner has been notified.'],
    failed: ['failed', 'bi-x-octagon-fill', 'Inspection failed', 'The owner has been notified to repair and re-apply.'],
  }[kind];
  const [cls, icon, title, text] = conf;
  const box = $('resultBox');
  box.innerHTML = `
    <div class="sync-anim ${cls}"><i class="bi ${icon}"></i></div>
    <h3 class="h4 fw-bold mt-3 mb-1 fade-up">${esc(t(title))}</h3>
    ${text ? `<p class="text-muted fade-up" style="--i:1">${esc(t(text))}</p>` : ''}
    ${kind === 'certified' ? `<div class="cert-chip mb-3" data-no-i18n><i class="bi bi-qr-code"></i> ${esc(r.certNo)}</div>` : ''}
    ${r && r.outcome === 'REJECTED' ? `<p class="text-danger small">${esc(t(r.message))}</p>` : ''}
    ${['queued', 'certified', 'failed'].includes(kind) ? `<div class="d-flex flex-wrap gap-2 justify-content-center mt-2 fade-up" style="--i:2">
      <button class="btn btn-lm" type="button" id="nextJob"><i class="bi bi-list-task me-1"></i> ${esc(t('Next inspection'))}</button>
      ${kind === 'certified' ? `<a class="btn btn-soft" href="/verify.html?c=${encodeURIComponent(r.certNo)}" target="_blank" rel="noopener"><i class="bi bi-qr-code-scan me-1"></i> ${esc(t('Check the QR'))}</a>` : ''}
    </div>` : ''}`;
  if (kind === 'certified') confetti(box);
  // Keep the header status in step with the outcome.
  const pill = $('inspectHeader').querySelector('.pill');
  if (pill && (kind === 'certified' || kind === 'failed')) pill.outerHTML = badge(kind === 'certified' ? 'CERTIFIED' : 'FAILED');
  else if (pill && kind === 'queued') pill.outerHTML = badge('INSPECTED_PENDING_SYNC');
  const nb = $('nextJob');
  if (nb) nb.onclick = closeInspection;
}

// ---------- sync ----------
/** Uploads everything pending. Returns the server results (or null when it could not reach the server). */
async function syncPending() {
  if (syncing || !navigator.onLine) return null;
  const pending = await IDB.all('pending');
  if (!pending.length) return [];
  syncing = true;
  $('syncBtn').disabled = true;
  $('syncIcon').className = 'bi bi-arrow-repeat spin';
  try {
    const inspections = pending.map(({ label, localResult, lastError, ...rest }) => rest);
    const results = await API.call('/api/sync/inspections', { method: 'POST', body: { inspections }, redirectOn401: false });
    updateConn(true);
    for (const r of results) {
      const rec = pending.find(p => p.clientUuid === r.clientUuid);
      if (r.outcome === 'SYNCED' || r.outcome === 'DUPLICATE') {
        await IDB.delete('pending', r.clientUuid);
        if (rec) await IDB.delete('assignments', rec.assignmentId);
        if (!current || current.clientUuid !== r.clientUuid) {
          toast(r.result === 'PASS' ? t('{item}: passed. Certificate {cert} issued.', { item: rec ? rec.label : '', cert: r.certNo })
            : t('{item}: failed. Owner has been notified.', { item: rec ? rec.label : '' }), r.result === 'PASS' ? 'success' : 'warning');
        }
      } else if (rec) {
        rec.lastError = r.message;
        await IDB.put('pending', rec);
        if (r.outcome === 'REJECTED' && (!current || current.clientUuid !== r.clientUuid)) toast(`${rec.label}: ${t(r.message)}`, 'danger');
      }
    }
    loadHistory();
    return results;
  } catch (e) {
    if (e.status === 401) showRelogin();
    else if (e.offline) {
      updateConn(false);
      if (!current) toast(t('No connection to the server. Inspections are safe on this phone and will sync later.'), 'warning');
    } else toast(t('Sync failed: {msg}', { msg: t(e.message) }), 'danger');
    return null;
  } finally {
    syncing = false;
    $('syncBtn').disabled = false;
    $('syncIcon').className = 'bi bi-cloud-arrow-up';
    if (!current) renderList();
  }
}
$('syncBtn').onclick = () => navigator.onLine ? syncPending() : toast(t('You are offline'), 'warning');

function showRelogin() {
  $('reloginBox').classList.remove('d-none');
  $('reloginForm').email.value = me.email;
}
onSubmit($('reloginForm'), async fd => {
  const res = await API.call('/api/auth/login', { method: 'POST', body: Object.fromEntries(fd), redirectOn401: false });
  if (res.user.id !== me.id) {
    throw new Error(t('Log in as the same officer who recorded these inspections'));
  }
  API.save(res);
  $('reloginBox').classList.add('d-none');
  toast(t('Logged in. Syncing...'));
  syncPending();
});

// ---------- history ----------
async function loadHistory() {
  try {
    const list = await API.call('/api/officer/inspections', { redirectOn401: false });
    $('historyList').innerHTML = list.slice(0, 10).map((i, n) => `
      <div class="d-flex align-items-center gap-3 p-2 soft-tile fade-up" style="--i:${n}">
        <span class="ibub sm ${i.result === 'PASS' ? 'good' : 'bad'}"><i class="bi ${i.result === 'PASS' ? 'bi-check-lg' : 'bi-x-lg'}"></i></span>
        <div class="flex-grow-1 min-w-0 small"><div class="fw-semibold text-truncate" data-no-i18n>${esc(i.businessName)} · ${esc(i.serialNo)}</div>
          <div class="text-muted">${esc(fmtDateTime(i.completedAt))}</div></div>
        ${i.certNo ? `<a class="small text-nowrap" href="/verify.html?c=${encodeURIComponent(i.certNo)}" target="_blank" rel="noopener" data-no-i18n>${esc(i.certNo)}</a>` : badge('FAILED')}
      </div>`).join('') || `<div class="small text-muted">${esc(t('No inspections yet.'))}</div>`;
  } catch (e) {
    if (e.offline) $('historyList').innerHTML = `<div class="small text-muted"><i class="bi bi-wifi-off"></i> ${esc(t('Offline: history needs a connection.'))}</div>`;
  }
}
$('historyBtn').onclick = loadHistory;

function fallbackUuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const r = crypto.getRandomValues(new Uint8Array(1))[0] % 16;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

// Retry periodically: the phone can be "online" while the server is unreachable (weak signal).
setInterval(syncPending, 60000);

// On open: show local data immediately, then sync and refresh if online.
I18N.ready.then(renderList).then(() => {
  if (navigator.onLine) {
    syncPending();
    loadHistory();
    IDB.get('meta', 'downloadedAt').then(m => { if (!m) $('downloadBtn').click(); });
  }
});
