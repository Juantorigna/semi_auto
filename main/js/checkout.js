/**
 * checkout.js — Screen 1: Language Selection
 *
 * Responsibilities:
 *   - Render language buttons
 *   - On tap: validate locale, save to sessionStorage, navigate to exit_bar.html
 *     carrying the locale as a URL query parameter (?lang=XX) so the next
 *     screen can read it even when sessionStorage is unavailable (WebView with
 *     DOM storage disabled, private mode, file:// origin, etc.)
 *   - Inactivity timer (on this page, timeout resets to itself)
 *
 * Depends on: utils.js (must be loaded first)
 *
 * Bug fixed: DOM queries were evaluated at IIFE top-level, before the
 * DOMContentLoaded guard; they are now deferred inside boot() to match the
 * pattern used by all other pages and avoid empty NodeLists in WebViews that
 * execute bottom-of-body scripts before the DOM is fully interactive.
 *
 * Bug fixed: locale was carried exclusively via sessionStorage, which can be
 * blocked/unavailable in certain Android WebView configurations. The locale is
 * now appended to the navigation URL as ?lang=XX (whitelisted, URL-encoded).
 * sessionStorage is retained as a secondary store for screens further downstream.
 */

'use strict';

(function () {

  /* ── DOM references (resolved inside boot — never at module level) ── */
  var dom = {
    langBtns:           null,
    inactivityContinue: null,
  };

  /* ── Navigation target ──────────────────────────────────────────────── */
  var EXIT_BAR_URL = 'exit_bar.html';

  /* ══════════════════════════════════════════════════════════════════════
     EVENT BINDING
     ══════════════════════════════════════════════════════════════════════ */
  function bindEvents() {
    dom.langBtns.forEach(function (btn) {
      btn.addEventListener('click', handleLangClick);
    });

    if (dom.inactivityContinue) {
      dom.inactivityContinue.addEventListener('click', function () {
        KioskUtils.dismissInactivityWarning();
      });
    }
  }

  /**
   * Handles a language button tap.
   *
   * 1. Reads the data-lang attribute.
   * 2. Validates it against the whitelist — rejects unknowns.
   * 3. Saves the locale to sessionStorage (best-effort; may be unavailable).
   * 4. Navigates to exit_bar.html with the locale appended as ?lang=XX.
   *    The query param is URL-encoded and whitelisted; it is never echoed
   *    to the DOM raw — XSS is not possible via this channel.
   *
   * @param {MouseEvent|TouchEvent} e
   */
  function handleLangClick(e) {
    var btn  = e.currentTarget;
    var code = btn.getAttribute('data-lang');

    if (!KioskUtils.isAllowedLocale(code)) {
      console.warn('[checkout] Rejected unknown lang code:', code);
      return;
    }

    /* Save to sessionStorage as a secondary fallback for downstream screens. */
    KioskUtils.saveLocale(code);

    /*
     * Primary locale carrier: URL query parameter.
     * encodeURIComponent is called even though the whitelist already guarantees
     * ASCII-safe values — defence-in-depth against future whitelist changes.
     */
    window.location.href = EXIT_BAR_URL + '?lang=' + encodeURIComponent(code);
  }

  /* ══════════════════════════════════════════════════════════════════════
     BOOT
     ══════════════════════════════════════════════════════════════════════ */
  async function boot() {

    /* Resolve DOM references inside boot() so they are always deferred until
       after DOMContentLoaded — matching the pattern in exit_bar.js.         */
    dom.langBtns           = document.querySelectorAll('[data-lang]');
    dom.inactivityContinue = document.getElementById('btn-inactivity-continue');

    if (!dom.langBtns || dom.langBtns.length === 0) {
      console.error('[checkout] No [data-lang] buttons found in DOM.');
      return;
    }

    /*
     * Load the default locale for this screen.
     * Button labels are hardcoded in their own language; only the
     * title/subtitle use locale strings from the JSON file.
     */
    try {
      await KioskUtils.loadLocale(KioskUtils.DEFAULT_LOCALE);
    } catch (err) {
      /* Non-fatal — static HTML fallback strings remain visible. */
      console.error('[checkout] Could not load default locale:', err);
    }

    bindEvents();

    /* Inactivity on the language screen reloads itself. */
    KioskUtils.initInactivityTimer(function () {
      window.location.reload();
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }

}());