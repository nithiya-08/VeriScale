'use strict';

const me = requireRole('OWNER');
registerServiceWorker();
const $ = id => document.getElementById(id);
I18N.picker($('langPicker'));
$('whoami').textContent = `${me.name} · ${me.email}`;
$('avatar').textContent = initials(me.name);
$('greeting').textContent = t('Welcome back, {name}', { name: me.name.split(' ')[0] });
$('today').textContent = fmtToday();
$('logoutBtn').onclick = () => API.logout();

const IN_PROGRESS = ['SUBMITTED', 'ASSIGNED', 'SCHEDULED', 'INSPECTED_PENDING_SYNC', 'INSPECTED', 'PASSED'];
const STEPS = [['Submitted', 'bi-send'], ['Scheduled', 'bi-calendar-event'], ['Inspected', 'bi-clipboard-check'], ['Certified', 'bi-patch-check']];
const STEP_INDEX = {
  SUBMITTED: 0, ASSIGNED: 1, SCHEDULED: 1, INSPECTED_PENDING_SYNC: 1, INSPECTED: 2, PASSED: 2,
  FAILED: 2, CERTIFIED: 3, DUE_SOON: 3, EXPIRED: 3, REVOKED: 3,
};

let state = { types: [], districts: [], businesses: [], instruments: [], applications: [], certificates: [], notifications: [] };
let docsInstrumentId = null;

// Skeletons while the first load runs.
$('kpis').innerHTML = Array.from({ length: 4 }, () => '<div class="col-6 col-lg-3"><div class="skel" style="height:86px"></div></div>').join('');
$('instrumentCards').innerHTML = skeletonCards(3, 190);

async function load() {
  const [types, districts, businesses, instruments, applications, certificates, notifications] = await Promise.all([
    API.call('/api/meta/instrument-types'), API.call('/api/meta/jurisdictions'), API.call('/api/businesses'),
    API.call('/api/instruments'), API.call('/api/applications'), API.call('/api/certificates'),
    API.call('/api/notifications'),
  ]);
  state = { types, districts, businesses, instruments, applications, certificates, notifications };
  renderKpis();
  renderAlertStrip();
  renderInstruments();
  renderApplications();
  renderCertificates();
  renderBusinesses();
  renderNotifications();
}

const latestApp = id => state.applications.find(a => a.instrumentId === id);
const latestCert = id => state.certificates.find(c => c.instrumentId === id);
const plural = (n, one, many) => t(n === 1 ? one : many, { n });
const isBusy = id => { const a = latestApp(id); return !!a && IN_PROGRESS.includes(a.status); };

function renderKpis() {
  const valid = state.certificates.filter(c => c.status === 'VALID');
  const dueSoon = valid.filter(c => daysUntil(c.validUntil) <= 30).length;
  const pending = state.applications.filter(a => IN_PROGRESS.includes(a.status)).length;
  const lapsed = state.instruments.filter(i => { const c = latestCert(i.id); return c && c.status !== 'VALID'; }).length;
  $('kpis').innerHTML = [
    kpiTile('Instruments', state.instruments.length, 'bi-speedometer2', 'navy', 0),
    kpiTile('Valid certificates', valid.length, 'bi-shield-check', 'good', 1),
    kpiTile('Due in 30 days', dueSoon, 'bi-hourglass-split', 'warn', 2),
    kpiTile('In progress', pending, 'bi-arrow-repeat', 'info', 3),
    kpiTile('Expired / revoked', lapsed, 'bi-exclamation-octagon', 'bad', 4),
  ].map(x => `<div class="col-6 col-md-4 col-xl">${x}</div>`).join('');
  countUp($('kpis'));
}

/** First-time owners get a guided start; everyone else gets the most urgent action. */
function renderAlertStrip() {
  if (!state.instruments.length) {
    $('alertStrip').innerHTML = `
      <div class="onboard mb-4 fade-up">
        <div class="small-caps">Get started</div>
        <h2 class="h4 fw-bold mb-0">Get your first instrument verified in 4 easy steps</h2>
        <div class="steps3">
          <div><span class="ibub sm navy"><i class="bi bi-shop"></i></span><div><b>1. Premises</b>Your shop or pump</div></div>
          <div><span class="ibub sm navy"><i class="bi bi-speedometer2"></i></span><div><b>2. Instrument</b>Scale, pump or measure</div></div>
          <div><span class="ibub sm navy"><i class="bi bi-file-earmark-arrow-up"></i></span><div><b>3. Documents</b>Optional</div></div>
          <div><span class="ibub sm good"><i class="bi bi-send-check"></i></span><div><b>4. Submit</b>An officer is assigned</div></div>
        </div>
        <button class="btn btn-lm big-cta" type="button" data-wizard><i class="bi bi-play-fill me-1"></i> Start now <i class="bi bi-arrow-right ms-1"></i></button>
      </div>`;
    return;
  }
  const urgent = state.instruments.map(i => ({ i, c: latestCert(i.id) }))
    .filter(x => x.c && !isBusy(x.i.id))
    .filter(x => x.c.status !== 'VALID' || daysUntil(x.c.validUntil) <= 30);
  if (!urgent.length) { $('alertStrip').innerHTML = ''; return; }
  const x = urgent[0];
  const lapsed = x.c.status !== 'VALID';
  const item = `${t(x.i.type.name)} (${x.i.serialNo})`;
  const msg = lapsed
    ? t(x.c.status === 'REVOKED' ? '{item} is revoked. It must not be used for trade until re-verified.'
      : '{item} is expired. It must not be used for trade until re-verified.', { item })
    : t('{item} certificate expires in {n} days.', { item, n: daysUntil(x.c.validUntil) });
  const more = urgent.length > 1 ? ' ' + t('(+{n} more)', { n: urgent.length - 1 }) : '';
  $('alertStrip').innerHTML = `
    <div class="alert-strip ${lapsed ? 'bad' : ''} mb-3 fade-up">
      <i class="bi ${lapsed ? 'bi-exclamation-octagon-fill' : 'bi-hourglass-split'} fs-4"></i>
      <div class="flex-grow-1"><strong>${lapsed ? 'Action needed' : 'Renewal due soon'}</strong><div class="small">${esc(msg + more)}</div></div>
      <button class="btn btn-sm ${lapsed ? 'btn-danger' : 'btn-gold'}" data-apply="${x.i.id}" type="button">Apply for re-verification</button>
    </div>`;
}

function validityMeter(cert, type) {
  if (!cert) return '<div class="small text-muted"><i class="bi bi-dash-circle"></i> Never certified</div>';
  const left = daysUntil(cert.validUntil);
  const total = Math.max(1, type.validityMonths * 30.4);
  const pct = cert.status === 'VALID' ? Math.max(3, Math.min(100, 100 * left / total)) : 100;
  const color = cert.status !== 'VALID' ? 'var(--st-neutral)' : left <= 30 ? 'var(--st-warn)' : 'var(--st-good)';
  const label = cert.status === 'VALID'
    ? (left <= 30 ? `<strong style="color:var(--st-warn-ink)">${esc(plural(left, '{n} day left', '{n} days left'))}</strong>` : esc(plural(left, '{n} day left', '{n} days left')))
    : esc(t(cert.status === 'REVOKED' ? 'Revoked {date}' : 'Expired {date}', { date: fmtDate(cert.validUntil) }));
  return `<div class="d-flex justify-content-between small mb-1"><span class="text-muted">${esc(t('Valid until {date}', { date: fmtDate(cert.validUntil) }))}</span><span>${label}</span></div>
    <div class="meter"><span data-w="${pct}" style="background:${color}"></span></div>`;
}

function renderInstruments() {
  if (!state.instruments.length) {
    $('instrumentCards').innerHTML = `<div class="col-12"><div class="card">${emptyState('bi-speedometer2', 'No instruments yet',
      'Tap "Get an instrument verified" to add your first weighing scale or pump.')}</div></div>`;
    return;
  }
  $('instrumentCards').innerHTML = state.instruments.map((i, n) => {
    const app = latestApp(i.id);
    const cert = latestCert(i.id);
    const busy = isBusy(i.id);
    const status = app ? badge(app.status) : '<span class="pill neutral"><i class="bi bi-circle"></i>Not applied</span>';
    const action = busy
      ? `<div class="small text-muted"><i class="bi bi-arrow-repeat"></i> ${app.officerName
        ? esc(t('Inspection {date} · {officer}', { date: fmtDate(app.scheduledDate), officer: app.officerName })) : 'Awaiting officer'}</div>`
      : `<button class="btn btn-sm ${cert ? 'btn-soft' : 'btn-lm'} w-100" data-apply="${i.id}" type="button">
           <i class="bi ${cert ? 'bi-arrow-clockwise' : 'bi-send'} me-1"></i>${cert ? 'Apply for re-verification' : 'Apply for verification'}</button>`;
    return `<div class="col-md-6 col-xl-4"><div class="card inst-card card-lift fade-up" style="--i:${n}">
      <div class="d-flex gap-3 align-items-start">
        <span class="ibub navy"><i class="bi ${typeIcon(i.type.name)}"></i></span>
        <div class="flex-grow-1 min-w-0"><div class="serial" data-no-i18n>${esc(i.serialNo)}</div><div class="meta">${esc(i.type.name)}</div></div>
        ${status}
      </div>
      <div class="meta" data-no-i18n><i class="bi bi-shop me-1"></i>${esc(i.businessName)} · ${esc(i.district)}
        ${i.make ? `<br><i class="bi bi-tag me-1"></i>${esc([i.make, i.model].filter(Boolean).join(' '))}` : ''}</div>
      <div>${validityMeter(cert, i.type)}</div>
      <div class="mt-auto d-flex gap-2">
        <div class="flex-grow-1">${action}</div>
        <button class="btn btn-sm btn-soft" type="button" data-docs="${i.id}" data-serial="${esc(i.serialNo)}" title="Documents" aria-label="Documents"><i class="bi bi-folder2-open"></i></button>
      </div>
    </div></div>`;
  }).join('');
  requestAnimationFrame(() => document.querySelectorAll('.meter > span[data-w]').forEach(s => { s.style.width = s.dataset.w + '%'; }));
}

function stepper(a) {
  const idx = STEP_INDEX[a.status] ?? 0;
  const bad = a.status === 'FAILED' || a.status === 'REVOKED';
  const pending = IN_PROGRESS.includes(a.status);
  return `<div class="stepper">${STEPS.map(([label, icon], n) => {
    const cls = n < idx ? 'done' : n === idx ? (bad ? 'bad now' : pending ? 'now pending' : 'done now') : '';
    const ic = n === idx && bad ? 'bi-x-lg' : icon;
    const lbl = n === idx && bad ? (a.status === 'FAILED' ? 'Failed' : 'Revoked') : label;
    return `<div class="st ${cls}" style="--i:${n}"><div class="node"><i class="bi ${ic}"></i></div><div class="lbl">${lbl}</div></div>`;
  }).join('')}</div>`;
}

function renderApplications() {
  if (!state.applications.length) {
    $('applicationList').innerHTML = `<div class="card">${emptyState('bi-list-check', 'No applications yet', 'Apply for verification from the Instruments tab.')}</div>`;
    return;
  }
  $('applicationList').innerHTML = state.applications.map((a, n) => `
    <div class="card p-3 fade-up" style="--i:${Math.min(n, 8)}">
      <div class="row g-3 align-items-center">
        <div class="col-lg-4 d-flex gap-3 align-items-center">
          <span class="ibub ${statusTone(a.status)}"><i class="bi ${typeIcon(a.instrumentType)}"></i></span>
          <div class="min-w-0"><div class="fw-bold"><span data-no-i18n>${esc(a.serialNo)}</span> <span class="small text-muted fw-normal">#${a.id}</span></div>
            <div class="small text-muted"><span>${esc(a.instrumentType)}</span> · <span>${a.type === 'NEW' ? 'New' : 'Re-verification'}</span> · ₹${esc(a.fee)}</div></div>
        </div>
        <div class="col-lg-5">${stepper(a)}</div>
        <div class="col-lg-3 text-lg-end small">
          ${badge(a.status)}
          <div class="text-muted mt-1">${a.officerName ? `<i class="bi bi-person-badge"></i> <span data-no-i18n>${esc(a.officerName)} · ${esc(fmtDate(a.scheduledDate))}</span>` : 'Awaiting assignment'}</div>
          ${a.certNo ? `<div class="mt-1" data-no-i18n><i class="bi bi-patch-check"></i> ${esc(a.certNo)}</div>` : ''}
        </div>
      </div>
    </div>`).join('');
}

function renderCertificates() {
  if (!state.certificates.length) {
    $('certificateCards').innerHTML = `<div class="col-12"><div class="card">${emptyState('bi-patch-check', 'No certificates yet', 'Certificates appear here after a successful inspection.')}</div></div>`;
    return;
  }
  $('certificateCards').innerHTML = state.certificates.map((c, n) => {
    const d = daysUntil(c.validUntil);
    const tone = c.status === 'VALID' ? (d <= 30 ? 'warn' : 'good') : c.status === 'REVOKED' ? 'bad' : 'neutral';
    const note = c.status === 'VALID'
      ? (d <= 30 ? `<span style="color:var(--st-warn-ink)" class="fw-semibold"><i class="bi bi-hourglass-split"></i> ${esc(plural(d, 'Expires in {n} day', 'Expires in {n} days'))}</span>`
        : `<i class="bi bi-calendar-check"></i> ${esc(plural(d, '{n} day remaining', '{n} days remaining'))}`)
      : c.revokedReason ? `<span>${esc(c.revokedReason)}</span>` : esc(t('Expired on {date}', { date: fmtDate(c.validUntil) }));
    return `<div class="col-md-6 col-xl-4"><div class="card cert-card card-lift fade-up" style="--i:${n}"><span class="ribbon ${tone}"></span>
      <div class="card-body d-flex gap-3">
        <div class="qr-box"><img alt="QR code" data-qr="${esc(c.certNo)}"></div>
        <div class="flex-grow-1 min-w-0">
          <div class="fw-bold" data-no-i18n>${esc(c.certNo)}</div>
          <div class="small text-muted text-truncate"><span>${esc(c.instrumentType)}</span> · <span data-no-i18n>${esc(c.serialNo)}</span></div>
          <div class="my-2">${badge(c.status)}</div>
          <div class="small">${note}</div>
          <div class="d-flex gap-2 mt-2">
            <button class="btn btn-sm btn-lm" type="button" data-pdf="${esc(c.certNo)}"><i class="bi bi-file-earmark-pdf"></i> PDF</button>
            <a class="btn btn-sm btn-soft" href="${esc(c.verifyUrl)}" target="_blank" rel="noopener"><i class="bi bi-box-arrow-up-right"></i> Verify</a>
          </div>
        </div>
      </div></div></div>`;
  }).join('');
  $('certificateCards').querySelectorAll('[data-qr]').forEach(async img => {
    try {
      img.onload = () => img.classList.add('loaded');
      img.src = URL.createObjectURL(await API.blob(`/api/certificates/${encodeURIComponent(img.dataset.qr)}/qr.png`));
    } catch (e) { /* QR is optional in the list */ }
  });
}

function renderBusinesses() {
  if (!state.businesses.length) {
    $('businessCards').innerHTML = `<div class="col-12"><div class="card">${emptyState('bi-shop', 'No businesses yet', 'Add your shop or petrol pump to start.')}</div></div>`;
    return;
  }
  $('businessCards').innerHTML = state.businesses.map((b, n) => {
    const count = state.instruments.filter(i => i.businessId === b.id).length;
    const map = b.lat != null
      ? `<a class="small" href="https://www.openstreetmap.org/?mlat=${b.lat}&mlon=${b.lng}#map=17/${b.lat}/${b.lng}" target="_blank" rel="noopener"><i class="bi bi-geo-alt"></i> View on map</a>`
      : '<span class="small text-muted"><i class="bi bi-geo"></i> Location not set</span>';
    return `<div class="col-md-6 col-xl-4"><div class="card p-3 card-lift fade-up h-100" style="--i:${n}">
      <div class="d-flex gap-3"><span class="ibub gold"><i class="bi bi-shop"></i></span>
        <div data-no-i18n><div class="fw-bold">${esc(b.name)}</div><div class="small text-muted">${esc(b.address || '')}${b.address ? ', ' : ''}${esc(b.district)}</div></div></div>
      <div class="d-flex justify-content-between align-items-center mt-3"><span class="small"><i class="bi bi-speedometer2"></i> ${esc(plural(count, '{n} instrument', '{n} instruments'))}</span>${map}</div>
    </div></div>`;
  }).join('');
}

function renderNotifications() {
  const unread = state.notifications.filter(n => !n.read).length;
  $('bellCount').textContent = unread > 9 ? '9+' : unread;
  $('bellCount').classList.toggle('d-none', unread === 0);
  $('bellInfo').textContent = unread ? t('{n} new', { n: unread }) : t('All caught up');
  const icon = ty => ty.includes('EXPIR') ? ['bi-hourglass-split', 'warn'] : ty.includes('REVOKED') || ty.includes('FAILED') ? ['bi-exclamation-octagon', 'bad']
    : ty.includes('ISSUED') ? ['bi-patch-check', 'good'] : ['bi-calendar-event', 'info'];
  $('notificationList').innerHTML = state.notifications.map(n => {
    const [ic, tone] = icon(n.type);
    return `<div class="d-flex gap-3 px-3 py-2 border-bottom ${n.read ? '' : 'bg-light'}">
      <span class="ibub sm ${tone}"><i class="bi ${ic}"></i></span>
      <div class="small"><div>${esc(n.message)}</div><div class="text-muted">${esc(fmtDateTime(n.sentAt))}</div></div></div>`;
  }).join('') || `<div class="p-3">${emptyState('bi-bell', 'No alerts')}</div>`;
}

// =====================================================================
// "Get verified" wizard: Premises -> Instrument -> Documents -> Review -> Done
// =====================================================================
const WZ_NAMES = ['Premises', 'Instrument', 'Documents', 'Review', 'Done'];
const wizModal = new bootstrap.Modal($('wizModal'));
const wizard = new Wizard($('wzSteps'), $('wzPanels'), WZ_NAMES.map(n => t(n)), i => goStep(i));
const wz = { businessId: null, instrumentId: null, addingBiz: false, addingInst: false, typeId: null };

function openWizard(instrumentId = null) {
  Object.assign(wz, { businessId: null, instrumentId: null, addingBiz: false, addingInst: false, typeId: null });
  ['wzBizForm', 'wzInstForm'].forEach(id => $(id).reset());
  $('wzConfirm').checked = false;
  wizard.reset();
  fillDistricts();
  if (instrumentId) {
    const i = state.instruments.find(x => x.id === instrumentId);
    wz.businessId = i.businessId;
    wz.instrumentId = i.id;
    wizard.furthest = 2;
    goStep(2);
  } else {
    wz.addingBiz = state.businesses.length === 0;
    if (state.businesses.length === 1) wz.businessId = state.businesses[0].id;
    goStep(0);
  }
  wizModal.show();
}

function goStep(i) {
  const renderers = [renderBizStep, renderInstStep, renderDocsStep, renderReviewStep, () => {}];
  renderers[i]();
  wizard.go(i, { lock: i === 4 });
  $('wzCounter').textContent = i < 4 ? t('Step {n} of {total}', { n: i + 1, total: 4 }) : t('Completed');
  $('wzBack').classList.toggle('invisible', i === 0 || i === 4);
  setNextLabel(i);
  $('wzHint').textContent = [t('The officer will visit this address'), t('Serial number is on the name plate'),
    t('You can skip this and add documents later'), t('Nothing is charged online'), ''][i];
  $('wzPanels').scrollTop = 0;
}

function setNextLabel(i) {
  const next = $('wzNext');
  next.innerHTML = i === 3 ? `<i class="bi bi-send-check me-1"></i>${esc(t('Submit application'))}`
    : `${esc(t(i === 4 ? 'Track application' : 'Continue'))} <i class="bi bi-arrow-right ms-1"></i>`;
  next.classList.toggle('btn-success', i === 3);
  next.classList.toggle('btn-lm', i !== 3);
}

// ---------- step 1: premises ----------
function renderBizStep() {
  $('wzBusinesses').innerHTML = state.businesses.map((b, n) => `
    <div class="col-md-6"><button type="button" class="choice fade-up ${!wz.addingBiz && wz.businessId === b.id ? 'selected' : ''}" style="--i:${n}" data-biz="${b.id}">
      <span class="ibub gold"><i class="bi bi-shop"></i></span>
      <span class="min-w-0" data-no-i18n><span class="d-block fw-bold">${esc(b.name)}</span><span class="d-block small text-muted text-truncate">${esc(b.address || '')}${b.address ? ', ' : ''}${esc(b.district)}</span></span>
      <span class="tick"><i class="bi bi-check"></i></span></button></div>`).join('') + `
    <div class="col-md-6"><button type="button" class="choice add fade-up ${wz.addingBiz ? 'selected' : ''}" style="--i:${state.businesses.length}" data-biz="new">
      <i class="bi bi-plus-circle fs-5"></i> ${esc(t('Add new premises'))}</button></div>`;
  $('wzBizForm').classList.toggle('d-none', !wz.addingBiz);
}

$('wzBusinesses').addEventListener('click', ev => {
  const c = ev.target.closest('[data-biz]');
  if (!c) return;
  wz.addingBiz = c.dataset.biz === 'new';
  wz.businessId = wz.addingBiz ? null : Number(c.dataset.biz);
  if (wz.businessId !== (state.instruments.find(i => i.id === wz.instrumentId) || {}).businessId) wz.instrumentId = null;
  renderBizStep();
  if (wz.addingBiz) $('bName').focus();
});

function fillDistricts() {
  $('bDistrict').innerHTML = `<option value="">${esc(t('Choose district'))}</option>` +
    state.districts.map(d => `<option value="${d.id}" data-no-i18n>${esc(d.district)}, ${esc(d.state)}</option>`).join('');
}

// ---------- step 2: instrument ----------
function renderInstStep() {
  const list = state.instruments.filter(i => i.businessId === wz.businessId);
  if (!list.length) wz.addingInst = true;
  $('wzInstruments').innerHTML = list.map((i, n) => {
    const busy = isBusy(i.id);
    const cert = latestCert(i.id);
    const sub = busy ? t('Already in progress') : cert ? (cert.status === 'VALID'
      ? plural(daysUntil(cert.validUntil), 'Valid · {n} day left', 'Valid · {n} days left') : t('Needs re-verification')) : t('Never certified');
    return `<div class="col-md-6"><button type="button" class="choice fade-up ${busy ? 'disabled' : ''} ${!wz.addingInst && wz.instrumentId === i.id ? 'selected' : ''}"
        style="--i:${n}" data-inst="${i.id}" ${busy ? 'aria-disabled="true"' : ''}>
      <span class="ibub navy"><i class="bi ${typeIcon(i.type.name)}"></i></span>
      <span class="min-w-0"><span class="d-block fw-bold" data-no-i18n>${esc(i.serialNo)}</span>
        <span class="d-block small">${esc(i.type.name)}</span><span class="d-block small text-muted">${esc(sub)}</span></span>
      <span class="tick"><i class="bi bi-check"></i></span></button></div>`;
  }).join('') + `
    <div class="col-md-6"><button type="button" class="choice add fade-up ${wz.addingInst ? 'selected' : ''}" style="--i:${list.length}" data-inst="new">
      <i class="bi bi-plus-circle fs-5"></i> ${esc(t('Register a new instrument'))}</button></div>`;
  $('wzNewInst').classList.toggle('d-none', !wz.addingInst);
  if (wz.addingInst) renderTypes();
}

function renderTypes() {
  $('wzTypes').innerHTML = state.types.map((x, n) => `
    <button type="button" class="choice type-card fade-up ${wz.typeId === x.id ? 'selected' : ''}" style="--i:${n}" data-type="${x.id}">
      <span class="ibub navy"><i class="bi ${typeIcon(x.name)}"></i></span>
      <span class="tn">${esc(x.name)}</span>
      <span class="tm">${esc(t('₹{fee} · valid {m} months', { fee: x.fee, m: x.validityMonths }))}</span>
      <span class="tick"><i class="bi bi-check"></i></span></button>`).join('');
  applyTypeFields();
}

function applyTypeFields() {
  const x = state.types.find(y => y.id === wz.typeId);
  const weighing = !x || x.errorModel === 'OIML_R76';
  $('eBox').style.display = weighing ? '' : 'none';
  $('inE').required = !!x && weighing;
  document.querySelectorAll('#wzInstForm .unit').forEach(u => { u.textContent = x ? x.unit : '-'; });
}

$('wzInstruments').addEventListener('click', ev => {
  const c = ev.target.closest('[data-inst]');
  if (!c || c.classList.contains('disabled')) return;
  wz.addingInst = c.dataset.inst === 'new';
  wz.instrumentId = wz.addingInst ? null : Number(c.dataset.inst);
  renderInstStep();
});

$('wzTypes').addEventListener('click', ev => {
  const c = ev.target.closest('[data-type]');
  if (!c) return;
  wz.typeId = Number(c.dataset.type);
  $('wzTypes').querySelectorAll('.choice').forEach(el => el.classList.toggle('selected', el === c));
  applyTypeFields();
  $('inSerial').focus();
});

// ---------- step 3: documents ----------
async function renderDocsStep() {
  $('wzUploads').innerHTML = '<div class="skel" style="height:56px"></div>';
  try {
    const docs = await API.call(`/api/instruments/${wz.instrumentId}/documents`);
    $('wzUploads').innerHTML = docs.map(d => uploadRow(d.label, d.fileName, true)).join('')
      || `<div class="small text-muted py-3 text-center" id="noDocs"><i class="bi bi-folder2 fs-3 d-block mb-1"></i>${esc(t('No documents yet'))}</div>`;
  } catch (e) { $('wzUploads').innerHTML = ''; }
}

function uploadRow(label, name, ok) {
  return `<div class="up-item ${ok ? 'ok' : ''}">
    <span class="ibub sm ${/\.pdf$/i.test(name) ? 'bad' : 'info'}"><i class="bi ${/\.pdf$/i.test(name) ? 'bi-file-earmark-pdf' : 'bi-file-earmark-image'}"></i></span>
    <div class="flex-grow-1 min-w-0"><div class="small fw-semibold">${esc(label)}</div>
      <div class="small text-muted text-truncate" data-no-i18n>${esc(name)}</div><div class="bar"><span></span></div></div>
    <i class="bi ${ok ? 'bi-check-circle-fill text-success' : 'bi-hourglass-split text-muted'} state"></i></div>`;
}

/** Uploads with real progress (XHR), used by the wizard and the documents dialog. */
function uploadFile(instrumentId, file, label, onProgress) {
  return new Promise((resolve, reject) => {
    if (file.size > 5 * 1024 * 1024) return reject(new Error(t('File is too large (max 5 MB)')));
    const fd = new FormData();
    fd.append('file', file);
    fd.append('label', label);
    const xhr = new XMLHttpRequest();
    xhr.open('POST', `/api/instruments/${instrumentId}/documents`);
    xhr.setRequestHeader('Authorization', 'Bearer ' + API.token());
    xhr.upload.onprogress = e => { if (e.lengthComputable && onProgress) onProgress(100 * e.loaded / e.total); };
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) resolve(JSON.parse(xhr.responseText));
      else { let m = 'Upload failed'; try { m = JSON.parse(xhr.responseText).error || m; } catch (e) { /* keep */ } reject(new Error(t(m))); }
    };
    xhr.onerror = () => reject(new Error(t('No connection to the server')));
    xhr.send(fd);
  });
}

async function handleFiles(files) {
  const label = $('docLabel').value;
  $('noDocs')?.remove();
  for (const file of files) {
    const wrap = document.createElement('div');
    wrap.innerHTML = uploadRow(label, file.name, false);
    const row = wrap.firstElementChild;
    $('wzUploads').prepend(row);
    const bar = row.querySelector('.bar span');
    try {
      await uploadFile(wz.instrumentId, file, label, p => { bar.style.width = p + '%'; });
      row.classList.add('ok');
      row.querySelector('.state').className = 'bi bi-check-circle-fill text-success state';
    } catch (e) {
      row.classList.add('err');
      row.querySelector('.state').className = 'bi bi-x-circle-fill text-danger state';
      toast(e.message, 'danger');
    }
  }
}

const dz = $('dropzone');
['dragenter', 'dragover'].forEach(e => dz.addEventListener(e, ev => { ev.preventDefault(); dz.classList.add('over'); }));
['dragleave', 'drop'].forEach(e => dz.addEventListener(e, ev => { ev.preventDefault(); dz.classList.remove('over'); }));
dz.addEventListener('drop', ev => handleFiles(ev.dataTransfer.files));
$('docFile').addEventListener('change', ev => { handleFiles(ev.target.files); ev.target.value = ''; });

// ---------- step 4: review ----------
async function renderReviewStep() {
  const b = state.businesses.find(x => x.id === wz.businessId);
  const i = state.instruments.find(x => x.id === wz.instrumentId);
  let docCount = 0;
  try { docCount = (await API.call(`/api/instruments/${i.id}/documents`)).length; } catch (e) { /* show 0 */ }
  const row = (icon, k, v, step) => `<div class="review-row fade-up">
    <span class="ibub sm navy"><i class="bi ${icon}"></i></span>
    <div class="min-w-0"><div class="k">${esc(t(k))}</div>${v}</div>
    <button type="button" class="edit" data-edit="${step}"><i class="bi bi-pencil"></i> ${esc(t('Edit'))}</button></div>`;
  $('wzReview').innerHTML =
    row('bi-shop', 'Premises', `<div class="fw-bold" data-no-i18n>${esc(b.name)}</div><div class="small text-muted" data-no-i18n>${esc(b.address || '')}${b.address ? ', ' : ''}${esc(b.district)}</div>`, 0) +
    row(typeIcon(i.type.name), 'Instrument', `<div class="fw-bold">${esc(i.type.name)}</div><div class="small text-muted" data-no-i18n>${esc(i.serialNo)}${i.make ? ' · ' + esc([i.make, i.model].filter(Boolean).join(' ')) : ''}</div>`, 1) +
    row('bi-folder2-open', 'Documents', `<div class="fw-bold">${esc(plural(docCount, '{n} file attached', '{n} files attached'))}</div>`, 2) +
    row('bi-arrow-repeat', 'Application type', `<div class="fw-bold">${latestCert(i.id) ? esc(t('Re-verification')) : esc(t('New verification'))}</div>`, 1);
  $('wzFee').textContent = '₹' + i.type.fee;
  $('wzValidity').textContent = t('Certificate valid for {m} months after a pass', { m: i.type.validityMonths });
}

$('wzReview').addEventListener('click', ev => {
  const e = ev.target.closest('[data-edit]');
  if (e) goStep(Number(e.dataset.edit));
});

// ---------- navigation ----------
$('wzBack').onclick = () => goStep(Math.max(0, wizard.current - 1));

// withBusy restores the old label when it finishes, so the label for the new step is re-applied afterwards.
$('wzNext').onclick = ev => withBusy(ev.currentTarget, wizardNext).then(() => setNextLabel(wizard.current));

async function wizardNext() {
  try {
    const step = wizard.current;
    if (step === 0) {
      if (wz.addingBiz) {
        const form = $('wzBizForm');
        if (!form.reportValidity()) return;
        const fd = new FormData(form);
        const b = await API.call('/api/businesses', { method: 'POST', body: {
          name: fd.get('name'), address: fd.get('address'), jurisdictionId: Number(fd.get('jurisdictionId')),
          lat: numOrNull(fd.get('lat')), lng: numOrNull(fd.get('lng')) } });
        await load();
        Object.assign(wz, { businessId: b.id, addingBiz: false });
        toast(t('Premises saved'));
      } else if (!wz.businessId) {
        return toast(t('Choose your premises or add a new one'), 'warning');
      }
      goStep(1);
    } else if (step === 1) {
      if (wz.addingInst) {
        if (!wz.typeId) return toast(t('Choose what kind of instrument it is'), 'warning');
        const form = $('wzInstForm');
        if (!form.reportValidity()) return;
        const fd = new FormData(form);
        const x = state.types.find(y => y.id === wz.typeId);
        const inst = await API.call('/api/instruments', { method: 'POST', body: {
          businessId: wz.businessId, typeId: wz.typeId, serialNo: fd.get('serialNo'), make: fd.get('make'), model: fd.get('model'),
          capacityMin: numOrNull(fd.get('capacityMin')), capacityMax: numOrNull(fd.get('capacityMax')),
          eValue: x.errorModel === 'OIML_R76' ? numOrNull(fd.get('eValue')) : null,
          modelApprovalNo: fd.get('modelApprovalNo'), installationType: fd.get('installationType') } });
        await load();
        Object.assign(wz, { instrumentId: inst.id, addingInst: false });
        toast(t('Instrument registered'));
      } else if (!wz.instrumentId) {
        return toast(t('Choose an instrument or register a new one'), 'warning');
      }
      goStep(2);
    } else if (step === 2) {
      goStep(3);
    } else if (step === 3) {
      if (!$('wzConfirm').checked) {
        $('wzConfirm').closest('.confirm-box').animate([{ transform: 'translateX(-6px)' }, { transform: 'translateX(6px)' }, { transform: 'none' }], { duration: 300 });
        return toast(t('Please tick the confirmation box'), 'warning');
      }
      const a = await API.call('/api/applications', { method: 'POST', body: { instrumentId: wz.instrumentId } });
      showDone(a);
      load();
    } else {
      wizModal.hide();
      bootstrap.Tab.getOrCreateInstance($('appsTab')).show();
    }
  } catch (e) { toast(t(e.message), 'danger'); }
}

function showDone(a) {
  const fact = (icon, k, v, n) => `<div style="animation-delay:${.5 + n * .08}s"><div class="small text-muted"><i class="bi ${icon}"></i> ${esc(t(k))}</div><div class="fw-bold" data-no-i18n>${esc(v)}</div></div>`;
  $('wzDone').innerHTML = `${successMark()}
    <h3>${esc(t('Application submitted!'))}</h3>
    <p class="text-muted mb-0">${esc(a.officerName ? t('An officer has been assigned. You will get an alert before the visit.')
      : t('An officer will be assigned soon. You will get an alert.'))}</p>
    <div class="facts">
      ${fact('bi-hash', 'Application', '#' + a.id, 0)}
      ${fact('bi-speedometer2', 'Instrument', a.serialNo, 1)}
      ${a.officerName ? fact('bi-person-badge', 'Officer', a.officerName, 2) : ''}
      ${a.scheduledDate ? fact('bi-calendar-event', 'Inspection date', fmtDate(a.scheduledDate), 3) : ''}
    </div>
    <button class="btn btn-link mt-3 fw-semibold" type="button" id="wzAnother"><i class="bi bi-plus-circle"></i> ${esc(t('Verify another instrument'))}</button>`;
  goStep(4);
  confetti($('wzDone'));
  $('wzAnother').onclick = () => openWizard();
}

document.addEventListener('click', ev => {
  const start = ev.target.closest('#startWizard, [data-wizard]');
  const apply = ev.target.closest('[data-apply]');
  if (start) openWizard();
  else if (apply) openWizard(Number(apply.dataset.apply));
});

$('useLocation').onclick = ev => {
  if (!navigator.geolocation) return toast(t('Location is not available on this device'), 'warning');
  const btn = ev.currentTarget;
  btn.innerHTML = `<span class="spinner-border me-2" aria-hidden="true"></span>${esc(t('Getting location...'))}`;
  navigator.geolocation.getCurrentPosition(p => {
    $('bLat').value = p.coords.latitude.toFixed(6);
    $('bLng').value = p.coords.longitude.toFixed(6);
    btn.innerHTML = `<i class="bi bi-check-circle me-1"></i> ${esc(t('Location captured'))}`;
  }, () => {
    btn.innerHTML = `<i class="bi bi-geo-alt me-1"></i> ${esc(t('Use my current location'))}`;
    toast(t('Could not get location. Allow location access and try again.'), 'warning');
  }, { enableHighAccuracy: true, timeout: 15000 });
};

// =====================================================================
// Other actions
// =====================================================================
$('certificateCards').addEventListener('click', async ev => {
  const btn = ev.target.closest('[data-pdf]');
  if (!btn) return;
  await withBusy(btn, async () => {
    try {
      const blob = await API.blob(`/api/certificates/${encodeURIComponent(btn.dataset.pdf)}/pdf`);
      window.open(URL.createObjectURL(blob), '_blank') || downloadBlob(blob, btn.dataset.pdf + '.pdf');
    } catch (e) { toast(t(e.message), 'danger'); }
  });
});

// Documents dialog (from an instrument card)
$('instrumentCards').addEventListener('click', ev => {
  const btn = ev.target.closest('[data-docs]');
  if (!btn) return;
  docsInstrumentId = Number(btn.dataset.docs);
  $('docsSerial').textContent = btn.dataset.serial;
  $('docForm').reset();
  bootstrap.Modal.getOrCreateInstance($('docsModal')).show();
  loadDocs();
});

async function loadDocs() {
  $('docList').innerHTML = '<div class="skel" style="height:56px"></div>';
  try {
    const docs = await API.call(`/api/instruments/${docsInstrumentId}/documents`);
    $('docList').innerHTML = docs.map((d, n) => `
      <div class="d-flex align-items-center gap-3 p-2 border rounded-3 fade-up" style="--i:${n}">
        <span class="ibub sm ${d.contentType === 'application/pdf' ? 'bad' : 'info'}"><i class="bi ${d.contentType === 'application/pdf' ? 'bi-file-earmark-pdf' : 'bi-file-earmark-image'}"></i></span>
        <div class="flex-grow-1 min-w-0"><div class="fw-semibold">${esc(d.label)}</div>
          <div class="small text-muted text-truncate"><span data-no-i18n>${esc(d.fileName)}</span> · ${Math.max(1, Math.round((d.sizeBytes || 0) / 1024))} KB · ${esc(fmtDate(d.uploadedAt))}</div></div>
        <button class="btn btn-sm btn-soft" type="button" data-open="${d.id}" aria-label="Open"><i class="bi bi-eye"></i></button>
        <button class="btn btn-sm btn-outline-danger" type="button" data-del="${d.id}" aria-label="Delete"><i class="bi bi-trash"></i></button>
      </div>`).join('') || emptyState('bi-folder2', 'No documents yet', 'Upload the model approval certificate and purchase invoice to speed up verification.');
  } catch (e) { $('docList').innerHTML = ''; toast(t(e.message), 'danger'); }
}

$('docList').addEventListener('click', async ev => {
  const open = ev.target.closest('[data-open]'), del = ev.target.closest('[data-del]');
  try {
    if (open) {
      await withBusy(open, async () => window.open(URL.createObjectURL(await API.blob('/api/documents/' + open.dataset.open)), '_blank'));
    } else if (del && confirm(t('Delete this document?'))) {
      await withBusy(del, () => API.call('/api/documents/' + del.dataset.del, { method: 'DELETE' }));
      toast(t('Document deleted'));
      loadDocs();
    }
  } catch (e) { toast(t(e.message), 'danger'); }
});

onSubmit($('docForm'), async fd => {
  await uploadFile(docsInstrumentId, fd.get('file'), fd.get('label'));
  $('docForm').reset();
  toast(t('Document uploaded'));
  loadDocs();
});

$('bellBtn').addEventListener('shown.bs.dropdown', async () => {
  if (state.notifications.some(n => !n.read)) {
    await API.call('/api/notifications/read', { method: 'POST' }).catch(() => {});
    setTimeout(() => { state.notifications.forEach(n => { n.read = true; }); renderNotifications(); }, 2500);
  }
});

I18N.ready.then(load).catch(e => toast(t(e.message), 'danger'));
