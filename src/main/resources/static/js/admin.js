'use strict';

const me = requireRole('STATE_ADMIN');
const $ = id => document.getElementById(id);
$('whoami').textContent = `${me.name} · ${me.email}`;
$('avatar').textContent = initials(me.name);
$('stateName').textContent = me.state || '';
$('today').textContent = new Date().toLocaleDateString('en-IN', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
$('logoutBtn').onclick = () => API.logout();

const RULES = {
  F1: ['GPS far from business', 'bi-geo-alt'], F2: ['Unusually high pass rate', 'bi-graph-up-arrow'], F3: ['Inspection too short', 'bi-stopwatch'],
  F4: ['Too many inspections in a day', 'bi-calendar-x'], F5: ['Back-dated inspection', 'bi-clock-history'], F6: ['Photo reused', 'bi-images'],
};
const APP_STATUSES = ['SUBMITTED', 'ASSIGNED', 'SCHEDULED', 'INSPECTED', 'PASSED', 'FAILED', 'CERTIFIED', 'DUE_SOON', 'EXPIRED', 'REVOKED'];
let assignAppId = null;
let revokeId = null;
let lastSearch = '';

// ---------- chart tooltip (shared) ----------
const tip = $('chartTip');
document.addEventListener('mousemove', ev => {
  const t = ev.target.closest('[data-tip]');
  if (!t) { tip.classList.remove('show'); return; }
  tip.innerHTML = t.dataset.tip;
  tip.classList.add('show');
  const x = Math.min(ev.clientX + 14, window.innerWidth - tip.offsetWidth - 8);
  tip.style.left = x + 'px';
  tip.style.top = (ev.clientY + 14) + 'px';
});

// ---------- dashboard ----------
$('kpis').innerHTML = Array.from({ length: 6 }, () => '<div class="col-6 col-md-4 col-xxl-2"><div class="skel" style="height:86px"></div></div>').join('');

async function loadDashboard() {
  const d = await API.call('/api/dashboard/state');
  const s = d.applicationsByStatus;
  const pending = ['SUBMITTED', 'ASSIGNED', 'SCHEDULED', 'INSPECTED_PENDING_SYNC', 'INSPECTED', 'PASSED'].reduce((n, k) => n + (s[k] || 0), 0);
  $('kpis').innerHTML = [
    kpiTile('Pending applications', pending, 'bi-hourglass', 'info', 0),
    kpiTile('Valid certificates', d.certificatesByStatus.VALID, 'bi-shield-check', 'good', 1),
    kpiTile('Due in 30 days', d.dueSoon, 'bi-hourglass-split', 'warn', 2),
    kpiTile('Expired', d.certificatesByStatus.EXPIRED, 'bi-clock-history', 'neutral', 3),
    kpiTile('Revoked / failed', (d.certificatesByStatus.REVOKED || 0) + (s.FAILED || 0), 'bi-x-octagon', 'bad', 4),
    kpiTile('Open fraud flags', d.openFraudFlags, 'bi-flag', d.openFraudFlags ? 'bad' : 'neutral', 5),
  ].map(t => `<div class="col-6 col-md-4 col-xxl-2">${t}</div>`).join('');
  countUp($('kpis'));

  setCount('fraudCount', d.openFraudFlags);
  setCount('subCount', s.SUBMITTED || 0);

  renderDistrictChart(d.districts);
  renderStageChart(s);

  $('workloadRows').innerHTML = d.officers.map(o => officerRow(o, true)).join('');
  $('officerRows').innerHTML = d.officers.map(o => officerRow(o, false)).join('');
  requestAnimationFrame(() => document.querySelectorAll('.meter > span[data-w]').forEach(el => { el.style.width = el.dataset.w + '%'; }));
}

function setCount(id, n) {
  $(id).textContent = n;
  $(id).classList.toggle('d-none', !n);
}

function officerRow(o, withRate) {
  const role = `<span class="pill ${o.role === 'GATC' ? 'info' : 'neutral'}">${o.role === 'GATC' ? '<i class="bi bi-building-check"></i>' : '<i class="bi bi-person-badge"></i>'}${esc(o.role)}</span>`;
  const rate = o.passRatePercent == null ? '<span class="small text-muted">No inspections</span>'
    : `<div class="d-flex align-items-center gap-2"><div class="meter flex-grow-1"><span data-w="${o.passRatePercent}" style="background:var(--chart-pending)"></span></div>
        <span class="small fw-semibold" style="width:42px;text-align:right">${o.passRatePercent}%</span></div>`;
  return `<tr><td><div class="d-flex align-items-center gap-2"><span class="avatar">${esc(initials(o.name))}</span>
      <span class="fw-semibold">${esc(o.name)}</span></div></td>
    ${withRate ? '' : `<td>${role}</td>`}<td>${esc(o.district)}</td><td class="text-end">${o.openAssignments}</td><td class="text-end">${o.inspectionsLast30Days}</td>
    ${withRate ? `<td>${rate}</td>` : ''}</tr>`;
}

/** Stacked bar per district: valid (not due) / due soon / expired / pending. 2px gaps, hover tooltip, legend, table view. */
function renderDistrictChart(districts) {
  // Order and colours validated for the dark surface (adjacent segments stay distinguishable, incl. colour-blind).
  const SEGS = [['valid', 'Valid', 'var(--chart-valid)'], ['soon', 'Due soon', 'var(--chart-soon)'],
    ['pending', 'Pending', 'var(--chart-pending)'], ['expired', 'Expired', 'var(--chart-expired)']];
  const rows = districts.map(x => ({
    name: x.district, instruments: x.instruments,
    valid: Math.max(0, x.validCertificates - x.dueSoon), soon: x.dueSoon, expired: x.expired, pending: x.pending,
  }));
  const max = Math.max(1, ...rows.map(r => r.valid + r.soon + r.expired + r.pending));
  $('districtChart').innerHTML = rows.map((r, n) => {
    const total = r.valid + r.soon + r.expired + r.pending;
    const segs = SEGS.filter(([k]) => r[k] > 0).map(([k, label, color], i) =>
      `<div class="seg" style="width:${100 * r[k] / max}%;background:${color};--i:${n + i}"
         data-tip="<strong>${esc(r.name)}</strong><br>${label}: ${r[k]}"></div>`).join('');
    return `<div class="dist-row">
      <div class="name">${esc(r.name)}</div>
      <div class="stack ${total ? '' : 'no-data'}" role="img" aria-label="${esc(r.name)}: ${r.valid} valid, ${r.soon} due soon, ${r.expired} expired, ${r.pending} pending">${segs}</div>
      <div class="tot">${total}</div></div>`;
  }).join('');
  $('districtRows').innerHTML = districts.map(x => `<tr><td class="fw-semibold">${esc(x.district)}</td><td class="text-end">${x.instruments}</td>
    <td class="text-end">${x.pending}</td><td class="text-end">${x.validCertificates}</td><td class="text-end">${x.dueSoon}</td><td class="text-end">${x.expired}</td></tr>`).join('');
}

$('tableToggle').onclick = ev => {
  const showTable = $('districtTable').classList.toggle('d-none') === false;
  $('districtChart').classList.toggle('d-none', showTable);
  ev.currentTarget.setAttribute('aria-pressed', String(showTable));
  ev.currentTarget.innerHTML = showTable ? '<i class="bi bi-bar-chart"></i> Chart' : '<i class="bi bi-table"></i> Table';
};

/** Single-series horizontal bars: applications per workflow stage, value at the bar tip. */
function renderStageChart(s) {
  const stages = [['Submitted', s.SUBMITTED], ['Scheduled', (s.ASSIGNED || 0) + (s.SCHEDULED || 0)], ['Inspected', (s.INSPECTED || 0) + (s.PASSED || 0)],
    ['Certified', s.CERTIFIED], ['Due soon', s.DUE_SOON], ['Failed', s.FAILED], ['Expired', s.EXPIRED], ['Revoked', s.REVOKED]];
  const max = Math.max(1, ...stages.map(([, v]) => v || 0));
  $('stageChart').innerHTML = stages.map(([label, v], i) => `
    <div class="hbar" data-tip="<strong>${label}</strong>: ${v || 0} application${v === 1 ? '' : 's'}">
      <span>${label}</span><div class="track"><div class="fill" style="width:${100 * (v || 0) / max}%;--i:${i}"></div></div><span class="v">${v || 0}</span>
    </div>`).join('');
}

['runExpiry', 'runExpiry2'].forEach(id => {
  $(id).onclick = ev => withBusy(ev.currentTarget, async () => {
    try {
      const r = await API.call('/api/admin/run-expiry-job', { method: 'POST' });
      toast(`Expiry job done: ${r.expired} expired, ${r.dueSoon} due soon, ${r.remindersSent} reminder(s) sent`);
      loadDashboard();
    } catch (e) { toast(e.message, 'danger'); }
  });
});

// ---------- applications ----------
$('statusFilter').innerHTML += APP_STATUSES.map(s => `<option value="${s}">${s.replace(/_/g, ' ')}</option>`).join('');
$('statusFilter').onchange = loadApplications;

async function loadApplications() {
  $('appRows').innerHTML = skeletonRows(5, 8);
  const status = $('statusFilter').value;
  const list = await API.call('/api/admin/applications' + (status ? '?status=' + status : ''));
  $('appRows').innerHTML = list.map((a, n) => {
    let actions = '';
    if (a.status === 'SUBMITTED') {
      actions = `<button class="btn btn-sm btn-lm" data-auto="${a.id}" type="button"><i class="bi bi-magic"></i> Auto-assign</button>
        <button class="btn btn-sm btn-soft" data-assign="${a.id}" type="button">Manual</button>`;
    } else if (['ASSIGNED', 'SCHEDULED'].includes(a.status)) {
      actions = a.lockedForOffline
        ? '<span class="small text-muted" title="Officer downloaded it for offline work"><i class="bi bi-lock"></i> With officer</span>'
        : `<button class="btn btn-sm btn-soft" data-assign="${a.id}" type="button"><i class="bi bi-arrow-left-right"></i> Reassign</button>`;
    }
    return `<tr class="fade-in" style="animation-delay:${Math.min(n, 12) * 30}ms"><td class="text-muted">${a.id}</td>
      <td><div class="d-flex gap-2 align-items-center"><span class="ibub sm navy"><i class="bi ${typeIcon(a.instrumentType)}"></i></span>
        <div><div class="fw-semibold">${esc(a.serialNo)}</div><div class="small text-muted">${esc(a.instrumentType)}</div></div></div></td>
      <td>${esc(a.businessName)}<div class="small text-muted">${esc(a.ownerName)}</div></td><td>${esc(a.district)}</td>
      <td>${badge(a.status)}${a.certNo ? `<div class="small text-muted mt-1">${esc(a.certNo)}</div>` : ''}</td>
      <td>${a.officerName ? esc(a.officerName) + `<div class="small text-muted">${a.assignedBy === 'AUTO' ? '<i class="bi bi-magic"></i> auto' : '<i class="bi bi-person"></i> admin'}</div>` : '<span class="text-muted">-</span>'}</td>
      <td>${fmtDate(a.scheduledDate)}</td><td class="text-end text-nowrap">${actions}</td></tr>`;
  }).join('') || `<tr><td colspan="8">${emptyState('bi-inbox', 'No applications', 'Nothing matches this filter.')}</td></tr>`;
}

$('appRows').addEventListener('click', async ev => {
  const auto = ev.target.closest('[data-auto]'), assign = ev.target.closest('[data-assign]');
  if (auto) {
    await withBusy(auto, async () => {
      try {
        const a = await API.call('/api/assignments/auto/' + auto.dataset.auto, { method: 'POST' });
        toast(`Assigned to ${a.officerName} on ${fmtDate(a.scheduledDate)}`);
        loadApplications();
        loadDashboard();
      } catch (e) { toast(e.message, 'danger'); }
    });
  } else if (assign) {
    openAssign(Number(assign.dataset.assign));
  }
});

async function openAssign(appId) {
  try {
    const officers = await API.call(`/api/admin/applications/${appId}/eligible-officers`);
    if (!officers.length) return toast('No eligible officer in this district for this instrument category', 'warning');
    assignAppId = appId;
    $('assignInfo').innerHTML = `Application <strong>#${appId}</strong> · ${officers.length} eligible officer${officers.length === 1 ? '' : 's'}`;
    $('assignOfficer').innerHTML = officers.map(o => `<option value="${o.id}">${esc(o.name)} (${esc(o.role)}, ${esc(o.district)})</option>`).join('');
    const today = new Date();
    $('assignDate').value = $('assignDate').min = new Date(today.getTime() - today.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
    bootstrap.Modal.getOrCreateInstance($('assignModal')).show();
  } catch (e) { toast(e.message, 'danger'); }
}

onSubmit($('assignForm'), async fd => {
  await API.call(`/api/admin/applications/${assignAppId}/assignment`, {
    method: 'PUT', body: { officerId: Number(fd.get('officerId')), scheduledDate: fd.get('scheduledDate') },
  });
  bootstrap.Modal.getInstance($('assignModal')).hide();
  toast('Assignment saved and recorded in the audit log');
  loadApplications();
  loadDashboard();
});

// ---------- fraud flags ----------
async function loadFraud() {
  const flags = await API.call('/api/fraud-flags');
  $('fraudCards').innerHTML = flags.map((f, n) => {
    const [title, icon] = RULES[f.ruleCode] || [f.ruleCode, 'bi-flag'];
    const open = f.status === 'OPEN';
    return `<div class="col-md-6 col-xxl-4"><div class="card h-100 card-lift fade-up" style="--i:${n};${open ? 'border-left:4px solid var(--st-bad)' : 'opacity:.75'}">
      <div class="card-body d-flex flex-column gap-2">
        <div class="d-flex gap-3 align-items-start">
          <span class="ibub ${open ? 'bad' : 'neutral'}"><i class="bi ${icon}"></i></span>
          <div class="flex-grow-1"><div class="small-caps">${esc(f.ruleCode)}</div><div class="fw-bold">${esc(title)}</div></div>
          ${badge(f.status)}
        </div>
        <div class="small">${esc(f.details)}</div>
        <div class="small text-muted"><i class="bi bi-person-badge"></i> ${esc(f.officerName)} · ${fmtDateTime(f.createdAt)}</div>
        <div class="d-flex gap-2 mt-auto pt-2">
          ${f.inspectionId ? `<button class="btn btn-sm btn-soft" data-insp="${f.inspectionId}" type="button"><i class="bi bi-eye"></i> View inspection</button>` : ''}
          ${open ? `<button class="btn btn-sm btn-outline-success ms-auto" data-flag="${f.id}" data-to="REVIEWED" type="button"><i class="bi bi-check2"></i> Reviewed</button>
          <button class="btn btn-sm btn-outline-secondary" data-flag="${f.id}" data-to="DISMISSED" type="button">Dismiss</button>` : ''}
        </div>
      </div></div></div>`;
  }).join('') || `<div class="col-12"><div class="card">${emptyState('bi-shield-check', 'No flags', 'No suspicious activity detected.')}</div></div>`;
}

$('fraudCards').addEventListener('click', async ev => {
  const flagBtn = ev.target.closest('[data-flag]'), inspBtn = ev.target.closest('[data-insp]');
  try {
    if (flagBtn) {
      await withBusy(flagBtn, () => API.call('/api/fraud-flags/' + flagBtn.dataset.flag, { method: 'PUT', body: { status: flagBtn.dataset.to } }));
      toast(flagBtn.dataset.to === 'REVIEWED' ? 'Marked as reviewed' : 'Flag dismissed');
      loadFraud();
      loadDashboard();
    } else if (inspBtn) {
      await withBusy(inspBtn, () => showInspection(inspBtn.dataset.insp));
    }
  } catch (e) { toast(e.message, 'danger'); }
});

async function showInspection(id) {
  const i = await API.call('/api/admin/inspections/' + id);
  const gps = i.gpsLat != null
    ? `<a href="https://www.openstreetmap.org/?mlat=${i.gpsLat}&mlon=${i.gpsLng}#map=17/${i.gpsLat}/${i.gpsLng}" target="_blank" rel="noopener">${i.gpsLat.toFixed(5)}, ${i.gpsLng.toFixed(5)} <i class="bi bi-box-arrow-up-right"></i></a>`
    : '<span class="text-danger">not captured</span>';
  const mins = Math.round((new Date(i.completedAt) - new Date(i.startedAt)) / 60000);
  $('inspTitle').textContent = `Inspection #${i.id} · ${i.businessName}`;
  $('inspBody').innerHTML = `
    <div class="row g-3 mb-3 small">
      <div class="col-6 col-md-3"><div class="small-caps">Result</div>${badge(i.result === 'PASS' ? 'PASSED' : 'FAILED')}</div>
      <div class="col-6 col-md-3"><div class="small-caps">Duration</div>${mins} min</div>
      <div class="col-6 col-md-3"><div class="small-caps">GPS</div>${gps}</div>
      <div class="col-6 col-md-3"><div class="small-caps">Certificate</div>${esc(i.certNo || '-')}</div>
      <div class="col-6 col-md-3"><div class="small-caps">Started</div>${fmtDateTime(i.startedAt)}</div>
      <div class="col-6 col-md-3"><div class="small-caps">Completed</div>${fmtDateTime(i.completedAt)}</div>
      <div class="col-6 col-md-3"><div class="small-caps">Synced</div>${fmtDateTime(i.syncedAt)}</div>
      <div class="col-6 col-md-3"><div class="small-caps">Serial</div>${esc(i.serialNo)}</div>
    </div>
    <table class="table table-sm"><thead><tr><th>Test load</th><th>Indicated</th><th>Error</th><th>Limit ±</th><th></th></tr></thead>
    <tbody>${i.observations.map(o => `<tr><td>${o.testLoad}</td><td>${o.indicatedValue}</td><td>${o.error}</td><td>${o.permissibleError}</td>
      <td>${o.withinLimit ? badge('PASSED') : badge('FAILED')}</td></tr>`).join('')}</tbody></table>
    ${i.remarks ? `<div class="small mb-3"><i class="bi bi-chat-left-text"></i> ${esc(i.remarks)}</div>` : ''}
    <div class="d-flex flex-wrap gap-2" id="inspPhotos">${i.photoIds.length ? '' : '<span class="small text-muted"><i class="bi bi-image"></i> No photos</span>'}</div>`;
  bootstrap.Modal.getOrCreateInstance($('inspModal')).show();
  for (const pid of i.photoIds) {
    const img = document.createElement('img');
    img.className = 'photo-tile border';
    img.style.width = img.style.height = '140px';
    img.alt = 'Inspection photo';
    $('inspPhotos').appendChild(img);
    API.blob('/api/documents/' + pid).then(b => { img.src = URL.createObjectURL(b); }).catch(() => {});
  }
}

// ---------- officers ----------
$('oRole').onchange = () => document.querySelectorAll('.gatc-only').forEach(el => el.classList.toggle('d-none', $('oRole').value !== 'GATC'));

onSubmit($('officerForm'), async fd => {
  const body = Object.fromEntries(fd);
  body.jurisdictionId = Number(body.jurisdictionId);
  body.authorisedTypeIds = [...document.querySelectorAll('#oTypes input:checked')].map(c => Number(c.value));
  await API.call('/api/admin/officers', { method: 'POST', body });
  $('officerForm').reset();
  $('oRole').onchange();
  toast('Account created. Share the initial password with the officer securely.');
  loadDashboard();
});

// ---------- search & export ----------
$('searchRows').innerHTML = `<tr><td colspan="8">${emptyState('bi-search', 'Search records', 'Find any instrument by serial number, certificate, owner, business or district.')}</td></tr>`;

onSubmit($('searchForm'), async fd => {
  lastSearch = fd.get('q') || '';
  await runSearch();
});

async function runSearch() {
  const rows = await API.call('/api/admin/search?q=' + encodeURIComponent(lastSearch));
  $('searchRows').innerHTML = rows.map((r, n) => `
    <tr class="fade-in" style="animation-delay:${Math.min(n, 12) * 30}ms"><td class="fw-semibold">${esc(r.serialNo)}</td><td>${esc(r.instrumentType)}</td>
    <td>${esc(r.businessName)}<div class="small text-muted">${esc(r.ownerName)} · ${esc(r.ownerEmail)}</div></td>
    <td>${esc(r.district)}</td><td>${r.latestApplicationStatus ? badge(r.latestApplicationStatus) : '-'}</td>
    <td>${r.certNo ? `<a href="/verify.html?c=${encodeURIComponent(r.certNo)}" target="_blank" rel="noopener">${esc(r.certNo)}</a><div class="mt-1">${badge(r.certStatus)}</div>` : '-'}</td>
    <td>${fmtDate(r.validUntil)}</td>
    <td class="text-end">${r.certStatus === 'VALID' ? `<button class="btn btn-sm btn-outline-danger" data-revoke="${r.certificateId}" data-cert="${esc(r.certNo)}" type="button"><i class="bi bi-slash-circle"></i> Revoke</button>` : ''}</td></tr>`).join('')
    || `<tr><td colspan="8">${emptyState('bi-search', 'No matching records')}</td></tr>`;
}

$('searchRows').addEventListener('click', ev => {
  const btn = ev.target.closest('[data-revoke]');
  if (!btn) return;
  revokeId = btn.dataset.revoke;
  $('revokeCert').textContent = btn.dataset.cert;
  $('revokeReason').value = '';
  $('revokeReasonPick').value = '';
  bootstrap.Modal.getOrCreateInstance($('revokeModal')).show();
});
$('revokeReasonPick').onchange = ev => { if (ev.target.value) $('revokeReason').value = ev.target.value; };

onSubmit($('revokeForm'), async fd => {
  await API.call(`/api/certificates/${revokeId}/revoke`, { method: 'POST', body: { reason: fd.get('reason') } });
  bootstrap.Modal.getInstance($('revokeModal')).hide();
  toast('Certificate revoked. Its QR now shows REVOKED.');
  runSearch();
  loadDashboard();
});

$('exportBtn').onclick = ev => withBusy(ev.currentTarget, async () => {
  try {
    const q = $('q').value || '';
    downloadBlob(await API.blob('/api/admin/export.csv?q=' + encodeURIComponent(q)), `lm-records-${new Date().toISOString().slice(0, 10)}.csv`);
    toast('Export downloaded');
  } catch (e) { toast(e.message, 'danger'); }
});

// ---------- instrument rules ----------
async function loadTypes() {
  const types = await API.call('/api/meta/instrument-types');
  $('typeRows').innerHTML = types.map(t => `
    <tr><td><div class="d-flex gap-2 align-items-center"><span class="ibub sm navy"><i class="bi ${typeIcon(t.name)}"></i></span>
      <div><div class="fw-semibold">${esc(t.name)}</div><div class="small text-muted">${esc(t.category || '')}${t.accuracyClass ? ' · Class ' + esc(t.accuracyClass) : ''}</div></div></div></td>
    <td class="small">${t.errorModel === 'PERCENT' ? '% of quantity' : 'OIML R76 (e-based)'}</td>
    <td><input class="form-control form-control-sm" style="max-width:90px" type="number" min="1" name="validityMonths" value="${t.validityMonths}" aria-label="Validity months for ${esc(t.name)}"></td>
    <td><input class="form-control form-control-sm" style="max-width:100px" type="number" min="0" name="fee" value="${t.fee}" aria-label="Fee for ${esc(t.name)}"></td>
    <td>${t.errorModel === 'PERCENT' ? `<input class="form-control form-control-sm" style="max-width:90px" type="number" step="any" min="0" name="mpePercent" value="${t.mpePercent}" aria-label="MPE percent for ${esc(t.name)}">` : '<span class="text-muted">-</span>'}</td>
    <td class="text-end"><button class="btn btn-sm btn-soft" data-save="${t.id}" type="button"><i class="bi bi-check2"></i> Save</button></td></tr>`).join('');
  $('oTypes').innerHTML = types.map(t => `<label class="form-check small"><input class="form-check-input" type="checkbox" value="${t.id}"> ${esc(t.name)}</label>`).join('');
}

$('typeRows').addEventListener('click', ev => {
  const btn = ev.target.closest('[data-save]');
  if (!btn) return;
  const tr = btn.closest('tr');
  const val = n => { const el = tr.querySelector(`[name=${n}]`); return el ? numOrNull(el.value) : null; };
  withBusy(btn, async () => {
    try {
      await API.call('/api/admin/instrument-types/' + btn.dataset.save, {
        method: 'PUT', body: { validityMonths: val('validityMonths'), fee: val('fee'), mpePercent: val('mpePercent') },
      });
      toast('Rules updated and audit-logged');
    } catch (e) { toast(e.message, 'danger'); }
  });
});

// ---------- audit ----------
async function loadAudit() {
  $('auditRows').innerHTML = skeletonRows(6, 6);
  const list = await API.call('/api/admin/audit');
  $('auditRows').innerHTML = list.map(a => `
    <tr><td class="small text-nowrap text-muted">${fmtDateTime(a.at)}</td>
    <td class="small">${a.actorName === 'SYSTEM' ? '<span class="pill neutral"><i class="bi bi-cpu"></i>System</span>' : esc(a.actorName)}</td>
    <td class="small">${esc(a.entity)} <span class="text-muted">#${esc(a.entityId)}</span></td><td class="small fw-semibold">${esc(a.action.replace(/_/g, ' '))}</td>
    <td class="small text-muted">${esc(a.oldValue)}</td><td class="small">${esc(a.newValue)}</td></tr>`).join('');
}
$('auditTab').addEventListener('shown.bs.tab', loadAudit);

// ---------- init ----------
(async () => {
  try {
    const districts = await API.call('/api/meta/jurisdictions');
    $('oDistrict').innerHTML = districts.filter(d => d.state === me.state)
      .map(d => `<option value="${d.id}">${esc(d.district)}</option>`).join('');
    await Promise.all([loadDashboard(), loadApplications(), loadFraud(), loadTypes()]);
  } catch (e) { toast(e.message, 'danger'); }
})();
