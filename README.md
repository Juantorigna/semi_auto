# Cassa Semi-Automatica — Camping Self-Checkout Kiosk

> **Self-checkout departure terminal for [areacamperbergamo.it](https://areacamperbergamo.it)**  
> Guests identify themselves at the kiosk, confirm their stay, pay any remaining balance via card, drop their key in the physical box, and the exit barrier opens — with zero staff involvement.

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Tech Stack](#2-tech-stack)
3. [Repository Structure](#3-repository-structure)
4. [Guest Flow — 9 Screens](#4-guest-flow--9-screens)
5. [Implementation Status](#5-implementation-status)
6. [Frontend Architecture](#6-frontend-architecture)
7. [Database Schemas](#7-database-schemas)
8. [Internationalisation](#8-internationalisation)
9. [Security Posture](#9-security-posture)
10. [Deployment](#10-deployment)
11. [Development Roadmap](#11-development-roadmap)
12. [Known Gaps & Next Steps](#12-known-gaps--next-steps)

---

## 1. Project Overview

The kiosk is a **departure checkout terminal** mounted at the campsite exit barrier. It is a web application hosted on Aruba shared hosting (IIS / Windows / PHP) and rendered inside a native Android WebView shell app. Payments are processed in-person via a **Stripe WisePOS E** card reader using the Stripe Terminal SDK.

**Key constraints driving the architecture:**

- Aruba shared hosting: no Node.js, no `exec()`, no persistent processes — PHP only.
- No physical keyboard on the kiosk — all input via a custom on-screen keyboard.
- Touch targets ≥ 64×64 px for outdoor use with potentially dirty/wet hands.
- Designed for a 10″ tablet at 1280×800. No scrolling. Every screen fits the viewport.
- Auto-timeout of 60 seconds inactivity on any screen → reset to Screen 1.
- Currency always EUR in Italian format: `€ 25,00`.

---

## 2. Tech Stack

| Layer | Technology |
|---|---|
| Web UI | Vanilla HTML5 / CSS3 / ES5 JavaScript (no framework, no build step) |
| Server | PHP 7.4+ on Aruba shared hosting (IIS / Windows Server) |
| Database | MySQL / MariaDB (Aruba-managed) |
| Payments | Stripe Terminal — WisePOS E (WISE3) reader, `stripe/stripe-php` SDK |
| Android shell | Kotlin + WebView + Stripe Terminal Android SDK |
| IIS config | `web.config` — HTTPS redirect, CSP, security headers |
| Locale files | JSON (IT / EN / DE / FR / ES) |
| Monitoring | UptimeRobot (free) + Stripe Dashboard + `admin.php` |
| SSL | Aruba DV certificate |

---

## 3. Repository Structure

```
areacamperbergamo.it/
│
├── app/
│   └── web.config                  ← IIS: HTTPS redirect, CSP, security headers
│
└── main/
    ├── css/
    │   ├── checkout.css            ← Screen 1 styles (language selection)
    │   ├── exit_bar.css            ← Screen 2 styles (exit bar confirmation)
    │   └── match.css               ← Screen 3 styles (plate + key entry & lookup)
    │
    ├── js/
    │   ├── utils.js                ← Shared KioskUtils namespace (locale, i18n, inactivity)
    │   ├── checkout.js             ← Screen 1 logic
    │   ├── exit_bar.js             ← Screen 2 logic
    │   └── match.js                ← Screen 3 logic (lookup, result card, no-match)
    │
    ├── html/
    │   ├── checkout.html           ← Screen 1 — Language Selection
    │   ├── exit_bar.html           ← Screen 2 — Ready at Exit Bar?
    │   └── match.html              ← Screen 3 — Plate + Key Entry & Lookup
    │
    ├── img/                        ← Static image assets
    │
    └── lang/
        ├── it.json                 ← Italian (default)
        ├── en.json                 ← English
        ├── de.json                 ← German
        ├── fr.json                 ← French
        └── es.json                 ← Spanish
```

> **Note:** The `api/` backend, `private/` config files, and `vendor/` (Stripe PHP SDK) directories are defined in the roadmap but **not yet created**. See [§5 Implementation Status](#5-implementation-status).

---

## 4. Guest Flow — 9 Screens

```
Screen 1  Language Selection     → IT / EN / DE / FR / ES
Screen 2  Ready at Exit Bar?     → Yes proceeds; No returns to Screen 1
Screen 3  Plate + Key Entry      → On-screen keyboard; POST to lookup API
Screen 4  Confirm Booking Info   → Guest name, dates, pitch, balance due   [PENDING]
Screen 5  Balance Due?           → €0 → skip to Screen 8; balance → Screen 6  [PENDING]
Screen 6  Stripe Payment         → Tap / insert card on WisePOS E          [PENDING]
Screen 7  Payment Confirmed      → 2-second auto-advance                   [PENDING]
Screen 8  Drop Key in Box        → Instruction + "Checkout" button          [PENDING]
Screen 9  Checkout Complete      → 10-second display → auto-reset to Screen 1  [PENDING]
```

---

## 5. Implementation Status

### ✅ Complete

| File | Screen | Notes |
|---|---|---|
| `utils.js` | All | `KioskUtils` namespace: locale whitelist, sessionStorage, `fetch()` locale loader, `t()` i18n, 60 s inactivity timer with overlay |
| `checkout.html` / `checkout.js` / `checkout.css` | 1 — Language | Language buttons, URL param + sessionStorage locale carrier, inactivity self-reload |
| `exit_bar.html` / `exit_bar.js` / `exit_bar.css` | 2 — Exit Bar | Yes/No guard, locale resolution (URL param → sessionStorage → default), fallback locale on failed fetch |
| `match.html` / `match.js` / `match.css` | 3 — Plate + Key | On-screen keyboard, input sanitisation (plate: `A-Z0-9` max 10; key: `0-9` max 3), `POST` to lookup API, result card built with DOM API (never `innerHTML`), no-match countdown, booking passed to next screen via `sessionStorage` |
| `web.config` | Server | HTTPS redirect, `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `Permissions-Policy`, strict **CSP** (`default-src 'none'`), removes `X-Powered-By` |
| `it.json`, `en.json`, `de.json`, `fr.json`, `es.json` | All | All i18n keys for Screens 1–3 present and complete |
| `db_schema.sql` | — | Operational campsite management schema (see §7) |

### 🔧 In Progress / Pending

| Item | Phase | Notes |
|---|---|---|
| `api/lookup.php` | 1 | PHP endpoint: PDO prepared statement, searches `registrati` by `Plate` + `Chiavetta`; returns booking JSON |
| `api/connection-token.php` | 1 | Stripe Terminal connection token provider |
| `api/create-payment-intent.php` | 3 | Creates PaymentIntent for WisePOS E |
| `api/complete-checkout.php` | 1 | Marks booking as checked out in DB |
| `api/webhook.php` | 3 | Stripe webhook receiver; writes to `transactions` table |
| `api/heartbeat.php` | 1 | Tablet health ping |
| `api/admin.php` | 1 | Password-protected admin panel |
| `private/stripe-config.php` | 1 | API keys + DB credentials (outside web root) |
| `private/db.php` | 1 | PDO singleton helper |
| Screens 4–9 HTML/JS/CSS | 1–3 | Confirm, payment, key-drop, complete screens |
| Android WebView shell | 2 | Kotlin app, JS Bridge (`AndroidBridge`), Stripe Terminal SDK |
| Kiosk lockdown (Phase 4) | 4 | Single-app mode, auto-restart, crash recovery |

---

## 6. Frontend Architecture

### Module Pattern

Every JavaScript file is wrapped in a strict-mode IIFE:

```javascript
'use strict';
(function () {
  // all state, DOM refs, and functions are private
  // nothing leaks to window except the KioskUtils namespace (utils.js only)
}());
```

DOM references are resolved inside `boot()` — never at IIFE top-level — to avoid race conditions in Android WebView environments where scripts may execute before the DOM is fully interactive.

### Locale Pipeline

```
checkout.js  →  saves locale to sessionStorage  +  appends ?lang=XX to URL
exit_bar.js  →  reads ?lang=XX (URL)  →  sessionStorage fallback  →  'it' default
match.js     →  reads sessionStorage  →  'it' default
```

Locale codes are validated against a frozen whitelist (`['it','en','de','fr','es']`) at every point of entry. They are never echoed to the DOM raw.

### i18n

All visible text is loaded from `lang/{locale}.json` via `KioskUtils.loadLocale()`. Translations are applied via `element.textContent = KioskUtils.t('key.path')` — **never `innerHTML`**. Missing keys fall back to the dot-notation key string so broken translations are visible rather than silent.

### Inactivity Timer

`KioskUtils.initInactivityTimer(callback)` sets two timers:

- **50 s** — shows an overlay ("Still there? Tap to continue").
- **60 s** — fires the callback (navigate to Screen 1 / reload).

Any user interaction (`click`, `touchstart`, `keydown`, `mousemove`) resets both timers.

---

## 7. Database Schemas

Two schemas coexist in this project:

### 7.1 Operational Schema (`db_schema.sql`) — **exists**

The live campsite management database, populated by reception staff. Key tables:

| Table | Purpose |
|---|---|
| `registrati` | Currently checked-in guests. Has `Plate` and `Chiavetta` columns used by the kiosk lookup. |
| `prenotazioni` | Future bookings not yet arrived. |
| `usciti` | Checked-out guests (historical). |
| `stanziali` | Long-stay / seasonal guests. |

The kiosk's `lookup.php` will query `registrati` using a prepared statement on `Plate` + `Chiavetta`.

### 7.2 Kiosk Payments Schema — **planned (roadmap §1.3)**

A separate set of tables to be created via phpMyAdmin for kiosk-specific payment tracking:

| Table | Purpose |
|---|---|
| `bookings` | Kiosk-visible booking view with financial fields in cents |
| `transactions` | Stripe payment log, written by `webhook.php` |
| `heartbeats` | Tablet health ping log |

> **Note:** Whether to use the existing `registrati` schema directly or maintain a separate `bookings` table is a pending architectural decision.

---

## 8. Internationalisation

Five locales are fully provided for Screens 1–3:

| Code | Language | File |
|---|---|---|
| `it` | Italian (default) | `lang/it.json` |
| `en` | English | `lang/en.json` |
| `de` | German | `lang/de.json` |
| `fr` | French | `lang/fr.json` |
| `es` | Spanish | `lang/es.json` |

All JSON locale files follow a nested dot-notation structure (`exitBar.question`, `match.errorPlateInvalid`, `inactivity.body`, etc.). Currency is always formatted as `€ 25,00` (Italian locale style) regardless of the UI language.

---

## 9. Security Posture

### Already Implemented

| Control | Where | Detail |
|---|---|---|
| HTTPS enforcement | `web.config` | Permanent redirect; no plain-HTTP access |
| Content Security Policy | `web.config` | `default-src 'none'`; only `'self'` for scripts/styles; no `'unsafe-inline'` or `'unsafe-eval'` |
| `X-Frame-Options: DENY` | `web.config` | Prevents clickjacking |
| `X-Content-Type-Options: nosniff` | `web.config` | Prevents MIME sniffing |
| `Referrer-Policy: no-referrer` | `web.config` | No referrer leakage |
| `Permissions-Policy` | `web.config` | Camera, microphone, geolocation all denied |
| `X-Powered-By` removed | `web.config` | Hides server fingerprint |
| Locale whitelist | `utils.js` | Codes validated against frozen array; unknowns silently rejected |
| No `innerHTML` anywhere | All JS files | All DOM writes use `textContent` or `createElement`; XSS impossible via this path |
| IIFE module isolation | All JS files | No global state pollution; no namespace collisions |
| Input sanitisation | `match.js` | Plate: `/^[A-Z0-9]+$/` max 10 chars; Key: `/^[0-9]+$/` max 3 chars |
| Same-origin fetch | `match.js` / `utils.js` | `credentials: 'same-origin'`; relative URLs only; no user-controlled fetch targets |
| URL encoding on navigation | `checkout.js` | `encodeURIComponent()` on locale code even though whitelist guarantees ASCII-safety — defence in depth |

### Required for Backend (Phase 1 — not yet built)

| Control | Requirement |
|---|---|
| PDO prepared statements | All `api/*.php` files — no string interpolation in SQL |
| Input validation + sanitisation | Server-side validation of all POST fields; never trust client |
| Constant-time comparison | Admin password check must use `hash_equals()` |
| Stripe webhook signature | `stripe_webhook_secret` verified with `\Stripe\Webhook::constructEvent()` |
| `private/` directory protection | Credentials outside web root or blocked via `web.config` `<hiddenSegments>` |
| Output encoding | All PHP echo must use `htmlspecialchars()` with `ENT_QUOTES` |
| Error handling | `display_errors = Off` in production; log to file only |

---

## 10. Deployment

**Host:** Aruba shared hosting — IIS / Windows Server / PHP 7.4+  
**Upload method:** FTP (FileZilla or Aruba File Manager)  
**SSL:** Aruba DV certificate (activate in Aruba panel → Security → SSL)

### Steps (Phase 1 target)

```bash
# 1. Install Stripe PHP SDK locally
composer require stripe/stripe-php

# 2. Upload entire project via FTP to Aruba web root
# 3. Create DB tables via Aruba phpMyAdmin
# 4. Populate stripe-config.php with real credentials
# 5. Verify endpoints:
#    GET  https://yourdomain.com/api/heartbeat.php     → {"status":"ok"}
#    POST https://yourdomain.com/api/lookup.php         → booking JSON
#    GET  https://yourdomain.com/api/connection-token.php → {"secret":"..."}
```

---

## 11. Development Roadmap

| Phase | Weeks | Goal | Status |
|---|---|---|---|
| **0 — Prerequisites** | 1 | Hosting verified, Stripe account, WisePOS E registered, Android Studio ready | ✅ Done |
| **1 — DB + Backend + Web UI** | 2–4 | All 9 screens live; PHP API endpoints; MySQL populated | 🔧 In Progress (Screens 1–3 done) |
| **2 — Android Shell** | 4–5 | Kotlin WebView app with JS Bridge | ⏳ Pending |
| **3 — Stripe Terminal E2E** | 5–7 | Real card payments via WisePOS E; full flow tested | ⏳ Pending |
| **4 — Kiosk Lockdown** | 8 | Single-app mode, crash recovery, auto-restart | ⏳ Pending |
| **5 — Go Live** | 9 | First real guest checkout processed | ⏳ Pending |
| **6 — Enhancements** | Ongoing | Reception booking form, email receipts, barrier integration, tourist tax | ⏳ Planned |

---

## 12. Known Gaps & Next Steps

The immediate priority is completing **Phase 1**. The following items are the minimum required to make the web UI functional end-to-end before the Android shell is introduced.

### Immediate (Phase 1 blockers)

1. **`api/lookup.php`** — PDO prepared statement querying `registrati` on `Plate` + `Chiavetta`; returns a JSON-encoded booking object. Must validate and sanitise both inputs server-side before touching the database.

2. **Screens 4–9** — HTML, CSS, and JS for: booking confirmation, balance check branch, payment screen (Stripe JS Bridge placeholder), payment confirmed, key-drop instruction, and checkout complete with auto-reset.

3. **`api/complete-checkout.php`** — PDO update marking the booking as checked out; writes `Entry Timestamp` and `Payment Method` to `usciti` (or equivalent).

4. **`private/db.php`** — PDO singleton with `ERRMODE_EXCEPTION`, `FETCH_ASSOC`, charset `utf8mb4`.

5. **`private/stripe-config.php`** — Credentials file; must never be web-accessible.

### Architecture Decision Pending

- **Which DB table does the kiosk actually write to?** The roadmap defines a separate `bookings` table, but the existing operational schema uses `registrati` / `usciti`. A decision is needed before `lookup.php` and `complete-checkout.php` can be written.

### Short-Term (Phase 2–3)

- Android Studio project scaffold with WebView and JS Bridge stub.
- Stripe Terminal `ConnectionTokenProvider` wired to `connection-token.php`.
- End-to-end test with the three sample bookings (balance due / fully paid / unpaid).

---

*README written April 12, 2026 — reflects the state of the codebase at that date.*
