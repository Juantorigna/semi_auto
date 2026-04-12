/**
 * exit_bar.js — Screen 2: Ready at Exit Bar?
 *
 * Responsibilities
 *   · Resolve the active locale — URL query param (?lang=XX) takes priority,
 *     sessionStorage is the fallback, 'it' is the hard default.
 *   · Persist the resolved locale back to sessionStorage so screens further
 *     downstream (entry, confirm, payment…) can read it without a URL param.
 *   · Load the matching lang JSON via KioskUtils.loadLocale().
 *   · Apply translations to [data-i18n] nodes via textContent (never innerHTML).
 *   · Set <html lang="…"> for screen-reader correctness.
 *   · "Yes" → navigate to entry.html (Screen 3: plate + key entry).
 *   · "No"  → clearLocale() + navigate back to checkout.html (Screen 1).
 *   · Inactivity (60 s) → clearLocale() + navigate back to checkout.html.
 *   · Inactivity overlay "Continue" → dismiss and reset the countdown.
 *
 * Locale resolution order (most-to-least reliable in WebView environments):
 *   1. ?lang= URL query parameter  — set by checkout.js on navigation
 *   2. sessionStorage kiosk_locale — set by KioskUtils.saveLocale()
 *   3. KioskUtils.DEFAULT_LOCALE   — 'it' hard fallback
 *
 * Depends on: utils.js (defines KioskUtils — must load first)
 */

'use strict';

(function () {

  /* ── Navigation targets ─────────────────────────────────────────────── */
  var HOME_URL  = 'checkout.html';  /* Screen 1 — language selection  */
  var ENTRY_URL = 'match.html';     /* Screen 3 — plate + key entry   */

  /* ── DOM references — resolved once inside boot() ───────────────────── */
  var dom = {
    btnYes               : null,
    btnNo                : null,
    btnInactivityContinue: null,
    i18nNodes            : null,
  };

  /* ══════════════════════════════════════════════════════════════════════
     LOCALE RESOLUTION
     ══════════════════════════════════════════════════════════════════════ */

  /**
   * Returns the locale to use for this page, applying the priority chain:
   *   URL param → sessionStorage → default.
   *
   * The URL param value is read via URLSearchParams (no manual string parsing)
   * and validated against the whitelist before use — it is never echoed to the
   * DOM and poses no XSS risk through this path.
   *
   * @returns {string}  A validated locale code, e.g. 'en'.
   */
  function resolveLocale() {
    /* 1 — URL query parameter (?lang=XX), set by checkout.js */
    var params   = new URLSearchParams(window.location.search);
    var urlLang  = params.get('lang');

    if (KioskUtils.isAllowedLocale(urlLang)) {
      /* Persist back to sessionStorage so downstream pages get it without
         needing to re-parse the URL.                                       */
      KioskUtils.saveLocale(urlLang);
      return urlLang;
    }

    /* 2 — sessionStorage (may be unavailable in some WebView configs) */
    var stored = KioskUtils.getSavedLocale(); /* returns DEFAULT on miss   */
    return stored;
  }

  /* ══════════════════════════════════════════════════════════════════════
     i18n
     ══════════════════════════════════════════════════════════════════════ */

  /**
   * Replaces textContent of every [data-i18n] element with its resolved
   * translation.  textContent is mandatory — never innerHTML — to prevent XSS
   * if a locale file is ever tampered with.
   *
   * When t() returns the key itself (missing translation), the static HTML
   * fallback is kept — visible placeholders are better UX than dot-notation
   * key names.
   */
  function applyTranslations() {
    dom.i18nNodes.forEach(function (el) {
      var key        = el.getAttribute('data-i18n');
      var translated = KioskUtils.t(key);

      if (translated && translated !== key) {
        el.textContent = translated;
      }
    });
  }

  /* ══════════════════════════════════════════════════════════════════════
     NAVIGATION
     ══════════════════════════════════════════════════════════════════════ */

  /**
   * Navigates back to language selection.
   * Clears the locale so the next guest always picks their own language.
   */
  function goHome() {
    KioskUtils.clearLocale();
    window.location.href = HOME_URL;
  }

  /**
   * Navigates forward to plate + key-number entry.
   * The locale stays in sessionStorage for Screen 3 and beyond.
   */
  function goEntry() {
    window.location.href = ENTRY_URL;
  }

  /* ══════════════════════════════════════════════════════════════════════
     EVENT BINDING
     ══════════════════════════════════════════════════════════════════════ */
  function bindEvents() {
    dom.btnYes.addEventListener('click', goEntry);
    dom.btnNo.addEventListener('click', goHome);

    if (dom.btnInactivityContinue) {
      dom.btnInactivityContinue.addEventListener('click', function () {
        KioskUtils.dismissInactivityWarning();
      });
    }
  }

  /* ══════════════════════════════════════════════════════════════════════
     BOOT
     ══════════════════════════════════════════════════════════════════════ */
  async function boot() {

    /* Resolve DOM references */
    dom.btnYes                = document.getElementById('btn-yes');
    dom.btnNo                 = document.getElementById('btn-no');
    dom.btnInactivityContinue = document.getElementById('btn-inactivity-continue');
    dom.i18nNodes             = document.querySelectorAll('[data-i18n]');

    if (!dom.btnYes || !dom.btnNo) {
      console.error('[exit_bar] Required button elements missing from DOM.');
      return;
    }

    /* Determine locale — URL param wins over sessionStorage wins over default */
    var locale = resolveLocale();

    /* Load locale JSON — fall back to default if the primary fetch fails */
    try {
      await KioskUtils.loadLocale(locale);
    } catch (primaryErr) {
      console.error('[exit_bar] Failed to load locale "' + locale + '":', primaryErr);

      if (locale !== KioskUtils.DEFAULT_LOCALE) {
        try {
          await KioskUtils.loadLocale(KioskUtils.DEFAULT_LOCALE);
          locale = KioskUtils.DEFAULT_LOCALE;
        } catch (fallbackErr) {
          /* Both failed — static HTML fallback strings remain visible */
          console.error('[exit_bar] Fallback locale also failed:', fallbackErr);
        }
      }
    }

    /* Set <html lang> for screen-reader language announcement */
    document.documentElement.lang = locale;

    applyTranslations();
    bindEvents();

    /* Inactivity timeout returns to language selection */
    KioskUtils.initInactivityTimer(goHome);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }

}());