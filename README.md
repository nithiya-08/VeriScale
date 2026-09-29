# LM Verify: SIH26036

**Online Verification System for Weighing and Measuring Instruments**
Ministry of Consumer Affairs, Food & Public Distribution, Department of Consumer Affairs (Legal Metrology)

Owners apply online; the system assigns a Legal Metrology Officer (LMO) or GATC. The officer inspects
on a phone **even with no network**, pass/fail is calculated automatically, and a **digitally signed
QR certificate** is issued that anyone can scan to see **Valid / Expired / Revoked / Fake**.

Everything is free and open source: zero cost to build, run and demo.

---

## Run it (2 minutes)

Needs Java 17 and Maven (already on this laptop).

```bash
mvn spring-boot:run
```

Open <http://localhost:8095>. The default `demo` profile uses an in-memory H2 database with
Tamil Nadu demo data, so there is nothing to set up. Data resets on every restart.

### Demo accounts (password for all: `Demo@123`)

| Role | Email | What to show |
|---|---|---|
| Owner | `owner@lm.demo` | Instruments, apply, track status, certificates with QR, alerts |
| LMO (Chennai) | `lmo.chennai@lm.demo` | Offline field inspection, 2 jobs scheduled today |
| GATC (Chennai) | `gatc.chennai@lm.demo` | Only authorised categories (scales, counter machines) |
| State admin | `admin@lm.demo` | Dashboard, allocation, fraud flags, search/export, rules, audit |
| Other | `owner2@lm.demo`, `lmo2.chennai@lm.demo`, `lmo.madurai@lm.demo`, `lmo.coimbatore@lm.demo` | |

The seed data covers every status: scheduled today, valid, due in about 20 days, expired, revoked,
failed, an unassigned application, and an open fraud flag (GPS 4.7 km from the shop).

### Use MySQL instead

```bash
set DB_PASSWORD=your_mysql_root_password
mvn spring-boot:run -Dspring-boot.run.profiles=mysql
```

The `lm_verify` database is created automatically and demo data is loaded once.

---

## Demo script (60 to 90 s video)

1. **Owner** logs in, sees instruments and statuses, and taps *Apply for re-verification* on the
   *due soon* scale. An officer is auto-assigned.
2. **LMO** opens the field app, taps *Download*, then turns on **airplane mode**.
3. Opens *Sri Murugan Stores*, types the indicated readings, and the app shows PASS/FAIL instantly.
   Takes a photo, then *Save*. The record shows *INSPECTED PENDING SYNC*.
4. Turns airplane mode off. It syncs automatically and the toast shows the certificate number.
5. **Owner** opens *Certificates* and the new certificate with its QR code.
6. Scan the QR with any phone camera: **Valid certificate**. Edit one character of the `s=` value
   in the URL and it shows **Not genuine**.
7. **Admin** revokes it from *Search*. Scan again: **Revoked**. Show the *Audit log* entry and the
   *Fraud flags* tab.

### Testing on a real phone (free)

A phone needs HTTPS for the camera, GPS and "Install app". Use a free tunnel:

```bash
cloudflared tunnel --url http://localhost:8095
```

(or `ngrok http 8095`). Then start the app with the tunnel URL, so QR codes point to it:

```bash
set PUBLIC_BASE_URL=https://your-tunnel-url
mvn spring-boot:run
```

On Android Chrome, open `/officer.html` and tap **Install app**.

---

## Features

| # | Feature | Where |
|---|---|---|
| 1 | Role-based login (Owner / LMO / GATC / State admin / Public), enforced in the API | `SecurityConfig`, `@PreAuthorize`, per-record checks in services |
| 2 | Business and instrument registry | `OwnerService` |
| 3 | Online application (new or re-verification), status tracking | `OwnerService`, `owner.html` |
| 4 | **Rule-aware auto-allocation**: jurisdiction plus GATC category/district limits (GATC Rules 2025), least workload, then earliest free date. Admin override is audit-logged | `AllocationService` |
| 5 | **Offline-first field inspection**: Service Worker, IndexedDB, GPS, compressed photos, idempotent sync on `clientUuid` | `officer.html`, `js/officer.js`, `sw.js`, `InspectionService` |
| 6 | **Automatic pass/fail** against permissible error (server recomputes; the phone is never trusted) | `ErrorCalculator` / `js/errorcalc.js` |
| 7 | **ECDSA P-256 signed certificates** with QR, PDF, and a public verify page (no login) | `SigningService`, `CertificateService`, `verify.html` |
| 8 | Expiry job: due soon at 30 days, reminders at 30 / 15 / 7 days, auto-expire | `ExpiryService` (02:00 daily and on startup) |
| 9 | **Fraud / anomaly flags** F1 to F6 for admin review (never auto-punish) | `FraudService` |
| 10 | Dashboard: status counts, district-wise stats, officer workload and pass rate | `AdminService`, `admin.html` |
| 11 | Search by serial / certificate / owner / district; CSV export (opens in Excel) | `AdminService` |
| 12 | Revocation (seal broken, re-installed, fraud) | `CertificateService.revoke` |
| 13 | Configurable validity, fee and error limits per instrument type | *Instrument rules* tab |
| 14 | Audit log of every state change | `AuditService`, `WorkflowService` |
| 15 | Alerts in-app, plus free email via Gmail SMTP (`GMAIL_USERNAME`, `GMAIL_APP_PASSWORD`) | `NotificationService` |
| 16 | Step-by-step wizards: owner "Get verified" (premises, instrument, documents, review) and officer inspection (arrive, readings, evidence, review) | `owner.js`, `officer.js`, `wizard.js` |
| 17 | Owner document upload (PDF/JPG/PNG, checked by content, 5 MB) | `DocumentService` |
| 18 | 8 languages across all owner, officer and public screens | `js/i18n.js`, `static/i18n/*.json` |
| 19 | API docs (OpenAPI / Swagger UI) at `/swagger-ui.html` | `OpenApiConfig` |

### Status lifecycle

```
SUBMITTED -> ASSIGNED -> SCHEDULED -> (INSPECTED_PENDING_SYNC on phone) -> INSPECTED
  -> PASSED -> CERTIFIED -> DUE_SOON -> EXPIRED -> new RE_VERIFICATION application
  -> FAILED -> owner repairs and re-applies
CERTIFIED -> REVOKED
```

### Fraud rules (thresholds in `application.yml`, not legal limits)

F1 GPS more than 500 m from the shop, or missing · F2 pass rate far above the state average ·
F3 inspection under 3 min · F4 more than 15 inspections a day · F5 back-dated · F6 same photo reused

---

## Languages

The owner portal, the field app, the login page and the public verify page are fully available in
**English, हिन्दी, தமிழ், తెలుగు, ಕನ್ನಡ, മലയാളം, मराठी and বাংলা**. Pick one from the language button
(top right). The choice is remembered, and the first visit follows the phone's language.

* Everything is translated, from the first screen to the certificate: menus, wizard steps, readings,
  pass/fail, toasts, error messages, server alerts ("Certificate ... issued for ..."), instrument
  types, revoke reasons and dates. Names, serial numbers and certificate numbers stay as they are.
* Language files are cached, so the field app stays translated offline.
* The Controller dashboard stays in English, and the PDF certificate stays in English as the legal record.
* Translations were machine-assisted; **have native speakers review them** before a public launch.

**Adding or changing a UI string:** the English text is the key. Add it to `tools/i18n/keys.json` and
to every `src/main/resources/static/i18n/<lang>.json`, then run:

```bash
python tools/i18n/check_i18n.py
```

It fails if any language is missing a string or changes a `{placeholder}`.

## Tech stack

Spring Boot 3.3 (Java 17), Spring Security + JWT, BCrypt · MySQL 8 / H2 · HTML + Bootstrap 5 +
vanilla JS (PWA: Service Worker + IndexedDB) · ZXing (QR) · OpenPDF (PDF) · `java.security` ECDSA
P-256 · Spring `@Scheduled` · Spring Mail. No JSP, because server-rendered pages cannot load offline.

## Security notes

* The signing private key is generated on first start in `./keys/` and is git-ignored. Back it up:
  if it is lost, existing QR codes can no longer be verified by signature.
* Set `JWT_SECRET` to a long random value outside the demo.
* Public verify returns only non-sensitive fields. A network error is never shown as "fake".
* Demo accounts are created only when the database is empty. Remove `DataSeeder` for production.

## Before quoting in the PPT: VERIFY

* Validity months, fees and MPE percentages in `DataSeeder` are **demo values**.
* OIML R76 limits (0.5e / 1e / 1.5e) are used for weighing instruments; confirm against the LM
  (General) Rules 2011 schedule.
* No timing or percentage claims unless measured on this prototype.

## API outline

```
POST /api/auth/register | /api/auth/login        GET /api/me
GET/POST /api/businesses, /api/instruments        GET/POST /api/applications
GET  /api/officer/assignments/today               POST /api/sync/inspections
GET  /api/certificates/{certNo} | /pdf | /qr.png  GET /api/public/verify?c=&s=
GET  /api/public/signing-key                      POST /api/certificates/{id}/revoke
POST /api/assignments/auto/{appId}                PUT /api/admin/applications/{id}/assignment
GET  /api/dashboard/state                         GET/PUT /api/fraud-flags
GET  /api/admin/search?q= | /api/admin/export.csv POST /api/admin/run-expiry-job
```
