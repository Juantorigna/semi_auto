/**
 * utils.js — Shared Kiosk Utilities  (KioskUtils namespace)
 *
 * Load order: utils.js  →  [page].js
 *
 * Provides
 *   · Locale management  (validate / save / read / load JSON)
 *   · i18n string lookup (dot-notation keys)
 *   · Inactivity timer   (warning overlay + hard-reset callback)
 *
 * Security notes
 *   · Locale codes are whitelisted — never echoed raw.
 *   · fetch() uses credentials:'same-origin'; no cross-origin requests.
 *   · t() always returns a plain string — callers must use textContent.
 */

'use strict';

/* exported KioskUtils */
var KioskUtils = (function () {

  /* ── Constants ──────────────────────────────────────────────── */
  var ALLOWED_LOCALES = Object.freeze(['it', 'en', 'de', 'fr', 'es']);
  var DEFAULT_LOCALE  = 'it';
  var SESSION_KEY     = 'kiosk_locale';
  var INACTIVITY_MS   = 60000;   /* 60 s  → trigger callback  */
  var WARNING_MS      = 50000;   /* 50 s  → show overlay      */

  /* ── Private state ──────────────────────────────────────────── */
  var _strings      = {};
  var _timerWarning = null;
  var _timerAction  = null;
  var _onTimeout    = null;

  /* ══════════════════════════════════════════════════════════════
     LOCALE HELPERS
     ══════════════════════════════════════════════════════════════ */

  /**
   * Returns true only for codes in ALLOWED_LOCALES.
   * Constant-time indexOf is fine here — list is tiny and not a
   * secret comparison; we just need to reject unknowns safely.
   *
   * @param   {string}  code
   * @returns {boolean}
   */
  function isAllowedLocale(code) {
    return typeof code === 'string' &&
           ALLOWED_LOCALES.indexOf(code) !== -1;
  }

  /**
   * Writes the chosen locale to sessionStorage.
   * Silently ignores unknown codes and storage exceptions.
   *
   * @param {string} code
   */
  function saveLocale(code) {
    if (!isAllowedLocale(code)) { return; }
    try {
      sessionStorage.setItem(SESSION_KEY, code);
    } catch (_) { /* storage blocked — not fatal */ }
  }

  /**
   * Reads the locale from sessionStorage.
   * Falls back to DEFAULT_LOCALE when absent, invalid, or blocked.
   *
   * @returns {string}
   */
  function getSavedLocale() {
    var stored = null;
    try {
      stored = sessionStorage.getItem(SESSION_KEY);
    } catch (_) { /* storage blocked */ }
    return isAllowedLocale(stored) ? stored : DEFAULT_LOCALE;
  }

  /**
   * Clears the saved locale from sessionStorage.
   * Call when resetting to the language-selection screen.
   */
  function clearLocale() {
    try {
      sessionStorage.removeItem(SESSION_KEY);
    } catch (_) { /* storage blocked */ }
  }

  /**
   * Fetches and parses the JSON locale file for the given code.
   * Populates the internal _strings map used by t().
   *
   * @param   {string}          code — e.g. 'it', 'en'
   * @returns {Promise<Object>}
   * @throws  {Error}           on network failure or non-OK response
   */
  async function loadLocale(code) {
    if (!isAllowedLocale(code)) { code = DEFAULT_LOCALE; }

    /* Build a safe, same-origin URL — no user input is interpolated
       beyond the whitelisted code string.                          */
    var url = '../lang/' + code + '.json';

    var response;
    try {
      response = await fetch(url, {
        method      : 'GET',
        credentials : 'same-origin',
        headers     : { 'Accept': 'application/json' },
      });
    } catch (networkErr) {
      throw new Error('[KioskUtils] Network error loading locale "' +
                      code + '": ' + networkErr.message);
    }

    if (!response.ok) {
      throw new Error('[KioskUtils] HTTP ' + response.status +
                      ' loading locale "' + code + '"');
    }

    var data;
    try {
      data = await response.json();
    } catch (parseErr) {
      throw new Error('[KioskUtils] Invalid JSON in locale "' + code + '"');
    }

    if (typeof data !== 'object' || data === null || Array.isArray(data)) {
      throw new Error('[KioskUtils] Locale "' + code + '" is not a JSON object');
    }

    _strings = data;
    return _strings;
  }

  /* ══════════════════════════════════════════════════════════════
     i18n
     ══════════════════════════════════════════════════════════════ */

  /**
   * Resolves a dot-notation key against the loaded strings.
   * Returns the key itself (never throws) when the path is absent,
   * so missing translations are visible in the UI rather than silent.
   *
   * @param   {string} key — e.g. 'exitBar.question'
   * @returns {string}
   */
  function t(key) {
    if (typeof key !== 'string' || key === '') { return ''; }
    var parts  = key.split('.');
    var cursor = _strings;
    for (var i = 0; i < parts.length; i++) {
      if (cursor === null || typeof cursor !== 'object') { return key; }
      cursor = Object.prototype.hasOwnProperty.call(cursor, parts[i])
               ? cursor[parts[i]]
               : undefined;
    }
    return typeof cursor === 'string' ? cursor : key;
  }

  /* ══════════════════════════════════════════════════════════════
     INACTIVITY TIMER
     ══════════════════════════════════════════════════════════════ */

  /** Hides the overlay and restarts both timers. */
  function _reset() {
    clearTimeout(_timerWarning);
    clearTimeout(_timerAction);

    var overlay = document.getElementById('inactivity-warning');
    if (overlay) { overlay.hidden = true; }

    _timerWarning = setTimeout(_showWarning, WARNING_MS);
    _timerAction  = setTimeout(_fire,        INACTIVITY_MS);
  }

  function _showWarning() {
    var overlay = document.getElementById('inactivity-warning');
    if (overlay) { overlay.hidden = false; }
  }

  function _fire() {
    if (typeof _onTimeout === 'function') { _onTimeout(); }
  }

  /**
   * Starts the inactivity watcher.
   * Any touch, click, or keydown resets the countdown.
   *
   * @param {Function} onTimeout — called when 60 s of inactivity elapses
   */
  function initInactivityTimer(onTimeout) {
    _onTimeout = onTimeout;
    _reset();
    document.addEventListener('touchstart', _reset, { passive: true });
    document.addEventListener('click',      _reset, { passive: true });
    document.addEventListener('keydown',    _reset, { passive: true });
  }

  /**
   * Dismisses the warning overlay and resets the countdown.
   * Bind to the "Continue" button inside #inactivity-warning.
   */
  function dismissInactivityWarning() {
    _reset();
  }

  /* ── Public API ─────────────────────────────────────────────── */
  return Object.freeze({
    ALLOWED_LOCALES         : ALLOWED_LOCALES,
    DEFAULT_LOCALE          : DEFAULT_LOCALE,
    isAllowedLocale         : isAllowedLocale,
    saveLocale              : saveLocale,
    getSavedLocale          : getSavedLocale,
    clearLocale             : clearLocale,
    loadLocale              : loadLocale,
    t                       : t,
    initInactivityTimer     : initInactivityTimer,
    dismissInactivityWarning: dismissInactivityWarning,
  });

}());