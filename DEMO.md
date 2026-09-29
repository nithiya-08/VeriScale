# VeriScale: Demo Guide (SIH26036)

Everything needed to run and present the prototype.

---

## 1. Start the app

Needs Java 17 and Maven.

```bash
cd "C:\Users\MY PC\Documents\SIH26036"
mvn spring-boot:run
```

Wait for `Started LmVerifyApplication` (about 20 s), then open **http://localhost:8095**.

* Stop: `Ctrl + C` in the terminal.
* If the old design shows: refresh with `Ctrl + Shift + R`.
* "Port 8095 already in use": another copy is running. Close it, or run
  `netstat -ano | findstr :8095` and then `taskkill /PID <number> /F`.
* Demo data **resets on every restart**, so each demo starts clean.

## 2. Pages

| Page | URL | Who |
|---|---|---|
| Landing page | http://localhost:8095/ | Everyone |
| Login / register | http://localhost:8095/login.html | Everyone |
| Owner portal | http://localhost:8095/owner.html | Instrument owner |
| Field inspection app | http://localhost:8095/officer.html | LMO / GATC |
| Controller dashboard | http://localhost:8095/admin.html | State admin |
| Public QR check | http://localhost:8095/verify.html | Anyone, no login |
| API docs (Swagger) | http://localhost:8095/swagger-ui.html | Integrators / judges |

## 3. Demo accounts

Password for **all** accounts: `Demo@123`.

On the login page, first choose **Login as** (Owner / Officer (LMO) / Test centre (GATC) / Admin), then type the email and password.
If the choice does not match the account (e.g. **Admin** with an owner email), login is refused with a clear message.

**New owner sign-up checks the email.** Register → a 6-digit code is emailed → enter it → the account is created.
The code expires after 10 minutes, allows 5 wrong tries, and can be resent after 30 seconds.
No account exists until the code is verified. In the demo (no mail server) the code is shown on screen in a
"Demo mode" box; set `GMAIL_USERNAME` / `GMAIL_APP_PASSWORD` to send real emails instead.

**Fixing mistakes.** Every premises and instrument card (and each choice in the "Get verified" wizard) has
**View / Edit / Delete**. The rules protect official records:
premises can be edited unless an inspection is in progress there, and deleted only when they have no instruments;
an instrument can be edited until it gets a certificate (and not while an application is open), and deleted only
if it was never sent for verification. Every change is written to the audit log.

| Role | Email | Name | District |
|---|---|---|---|
| Owner | `owner@lm.demo` | Murugan K | Chennai |
| Owner | `owner2@lm.demo` | Lakshmi R | Madurai / Coimbatore |
| LMO | `lmo.chennai@lm.demo` | R. Karthik | Chennai |
| LMO | `lmo2.chennai@lm.demo` | S. Priya | Chennai |
| LMO | `lmo.madurai@lm.demo` | M. Senthil | Madurai |
| LMO | `lmo.coimbatore@lm.demo` | A. Deepa | Coimbatore |
| GATC | `gatc.chennai@lm.demo` | Chennai Precision Test Centre | Chennai (only scales and counter machines) |
| State admin | `admin@lm.demo` | Controller of Legal Metrology | Tamil Nadu |

## 4. What the demo data contains

| Serial | Instrument | Business (owner) | State at start | Use it to show |
|---|---|---|---|---|
| ES-CH-1001 | Electronic scale (Class III) | Sri Murugan Stores (owner@) | Scheduled today, R. Karthik | Offline inspection, then PASS, then certificate |
| FD-CH-2001 | Fuel dispenser | Balaji Fuels (owner@) | Scheduled today, R. Karthik | Offline inspection (try a FAIL: 20 L read as 20.5) |
| ES-CH-1002 | Electronic scale | Sri Murugan Stores | Valid, **expires in ~20 days** | "Renewal due soon" banner, reminder alert |
| CM-CH-3001 | Counter machine | Sri Murugan Stores | **Expired** | "Action needed" banner, re-verification wizard |
| FD-CH-2002 | Fuel dispenser | Balaji Fuels | Valid (2 years) | Valid QR |
| FD-CH-2003 | Fuel dispenser | Balaji Fuels | Valid, inspection GPS 4.7 km away | **Fraud flag F1** on the admin dashboard |
| PB-MD-4001 | Precision balance | Meenakshi Jewellers (owner2@) | **Revoked** (seal broken) | Revoked QR |
| PB-MD-4002 | Precision balance | Meenakshi Jewellers | **Failed** inspection | Failed status |
| ES-CB-5001 | Electronic scale | Kovai Fresh Mart (owner2@) | Submitted, **no officer yet** | Admin "Auto-assign" |

## 5. Demo script (about 6 minutes)

### Step 1: Landing page and languages (30 s)
1. Open http://localhost:8095. Explain the problem: paper registers, fake certificates, no reminders.
2. Tap the **language button** and switch to **தமிழ்** or **हिन्दी**: the whole app changes. Switch back.

### Step 2: Owner applies step by step (1.5 min)
1. **Login** → **Login as: Owner** → `owner@lm.demo` / `Demo@123`.
2. Point out the **"Action needed"** banner (expired counter machine) and the KPI tiles.
3. Tap **Get an instrument verified**:
   Premises, then **Register a new instrument** (pick a type card, e.g. Beam scale, serial `BS-CH-7001`),
   then **Documents** (drag a PDF or photo), then **Review**, tick the box, **Submit**.
4. Success screen: the officer is auto-assigned by district and workload.

### Step 3: Officer inspects offline (2 min): the headline moment
1. Log out, then **Login as: Officer (LMO)** → `lmo.chennai@lm.demo` / `Demo@123`. The assignments are downloaded to the phone.
2. **Turn Wi-Fi / network off** (on a phone: airplane mode). The "Offline" banner appears.
3. Open **Sri Murugan Stores (ES-CH-1001)**:
   * **Arrive**: GPS captured, name-plate check.
   * **Readings**: type `0.1`, `7.505`, `14.995`, `30.005`. Each row turns green and the banner says **PASS**.
     (Type `15.02` in one row to show a red **FAIL** with a shake.)
   * **Evidence**: take a photo, add remarks.
   * **Review**, then **Save inspection**: "Saved on this phone".
4. **Turn the network back on**. It syncs automatically, the certificate is issued, and confetti plays.

### Step 4: Customer scans the QR (45 s)
1. Tap **Check the QR** (or scan the QR in the owner's Certificates tab with any phone camera).
2. **Valid certificate** with "Digital signature verified". No login is needed.
3. Show a fake: open http://localhost:8095/verify.html?c=LM-2026-FAKE0000 to see **Not genuine**.
4. Tamper test: change one character after `&s=` in a real QR link; it also shows **Not genuine**.

### Step 5: Controller dashboard (1 min)
1. **Login as: Admin** → `admin@lm.demo` / `Demo@123`.
2. Dashboard: KPIs, district-wise chart, officer workload with pass rate.
3. **Applications**, then **Auto-assign** Kovai Fresh Mart (ES-CB-5001), assigned to A. Deepa (Coimbatore).
4. **Fraud flags**: F1 "GPS far from business" on FD-CH-2003, then **View inspection**, then **Reviewed**.
5. **Search & export**: search `FD-CH-2002`, **Revoke** it (reason: Seal found broken). Its QR now shows **Revoked**.
6. **Audit log**: every action with who and when.
7. **Run expiry job**: shows the automatic 30 / 15 / 7-day reminders.

### Step 6: Integration and quality (15 s)
* http://localhost:8095/swagger-ui.html: REST API ready for eMaap / state portals.
* `mvn test`: 34 automated tests (pass/fail maths, QR signing, offline sync, security).

## 6. Key talking points

* **Offline-first**: inspections work with no network and sync later without duplicates.
* **Tamper-proof QR**: ECDSA P-256 signature. A forged QR is detected instantly, with no login.
* **Automatic pass/fail**: permissible error is calculated (OIML R76 bands); the server re-checks it.
* **Rule-aware allocation**: district + GATC category limits (GATC Rules 2025) + least workload.
* **Fraud flags F1 to F6**: GPS far away, very high pass rate, rushed inspection, too many per day, back-dated, reused photo.
* **8 languages**: English, हिन्दी, தமிழ், తెలుగు, ಕನ್ನಡ, മലയാളം, मराठी, বাংলা.
* **Zero cost**: all open-source, runs on a laptop.

## 7. Phone demo (optional)

A phone needs HTTPS for camera, GPS and "Install app". Use a free tunnel:

```bash
cloudflared tunnel --url http://localhost:8095
```

Then restart the app with the tunnel URL so QR codes point to it:

```bash
set PUBLIC_BASE_URL=https://<your-tunnel>.trycloudflare.com
mvn spring-boot:run
```

On the phone, open `/officer.html` in Chrome and tap **Install app**.

## 8. Before the demo checklist

- [ ] `mvn spring-boot:run` started without errors
- [ ] Opened the site once while online (so the offline cache is filled)
- [ ] Browser zoom at 100%, language set to English
- [ ] Phone (if used) on the same tunnel URL, location permission allowed
- [ ] Remember: restarting resets all demo data
