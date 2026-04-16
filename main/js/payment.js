/**
 * payment.js — Screen 4: Stripe Terminal Payment
 *
 * Responsibilities
 *   · Reads registration data from sessionStorage (set by match.js).
 *   · POSTs to create-payment-intent.php with plate + key.
 *   · Server computes balance — amount is NEVER sourced from client.
 *   · If balance = 0 → auto-skips to key-drop screen.
 *   · If balance > 0 → shows tap-card UI, calls KioskBridge.initiatePayment().
 *   · Listens for window.onPaymentSuccess / window.onPaymentFailure
 *     (called by native Android bridge after Stripe Terminal flow).
 *   · Retry on failure. Cancel returns to match screen.
 *   · Inactivity timeout → clearLocale() + redirect to checkout.html.
 *
 * Security
 *   · Amount displayed is from server response only.
 *   · All DOM writes use textContent — never innerHTML.
 *   · fetch() uses credentials:'same-origin', POST + JSON body.
 *   · Registration data from sessionStorage is treated as untrusted
 *     display hints — only plate + key are sent to server for re-lookup.
 *
 * Depends on: utils.js (KioskUtils — must load first)
 */

'use strict';

(function () {

  /* ── Constants ── */
  var HOME_URL        = 'checkout.html';
  var BACK_URL        = 'match.html';
  var NEXT_URL        = 'keydrop.html';         /* Screen 5 — Key drop */
  var API_CREATE_PI   = '../../app/api/create-payment-intent.php';
  var SESSION_KEY     = 'kiosk_registration';
  var SUCCESS_DELAY   = 2000;                    /* ms before auto-advance */
  var MAX_RETRIES     = 3;

  /* ── DOM references ── */
  var dom = {};

  /* ── State ── */
  var registration       = null;    /* parsed from sessionStorage */
  var paymentIntentId    = null;
  var registrationRef    = null;
  var serverAmountCents  = 0;
  var serverClientSecret = '';      /* passed to native bridge — never to display */
  var retryCount         = 0;

  /* ══════════════════════════════════════════════════════════════
     VIEW MANAGEMENT
     ══════════════════════════════════════════════════════════════ */

  function showView(id) {
    var views = document.querySelectorAll('.view');
    for (var i = 0; i < views.length; i++) {
      views[i].classList.remove('active');
    }
    var target = document.getElementById(id);
    if (target) { target.classList.add('active'); }
  }

  /* ══════════════════════════════════════════════════════════════
     CURRENCY FORMATTING
     ══════════════════════════════════════════════════════════════ */

  function formatCentsToEuro(cents) {
    var euros = (cents / 100).toFixed(2).replace('.', ',');
    return '\u20AC ' + euros;
  }

  /* ══════════════════════════════════════════════════════════════
     LOAD REGISTRATION FROM SESSION
     ══════════════════════════════════════════════════════════════ */

  function loadRegistration() {
    var raw = null;

    try {
      raw = sessionStorage.getItem(SESSION_KEY);
    } catch (_) { /* storage blocked */ }

    if (!raw) {
      console.error('[payment] No registration in sessionStorage');
      return null;
    }

    try {
      return JSON.parse(raw);
    } catch (_) {
      console.error('[payment] Invalid JSON in sessionStorage');
      return null;
    }
  }

  /* ══════════════════════════════════════════════════════════════
     CREATE PAYMENT INTENT — server-side
     ══════════════════════════════════════════════════════════════ */

  async function createPaymentIntent() {
    if (!registration || !registration.Plate || !registration.Chiavetta) {
      console.error('[payment] Missing plate or key in registration data');
      showFailure(KioskUtils.t('payment.errorNoData'));
      return;
    }

    showView('view-preparing');

    var plate = String(registration.Plate).toUpperCase().replace(/[^A-Z0-9]/g, '');
    var key   = String(registration.Chiavetta).replace(/[^0-9]/g, '');

    var response;
    try {
      response = await fetch(API_CREATE_PI, {
        method      : 'POST',
        credentials : 'same-origin',
        headers     : {
          'Content-Type' : 'application/json',
          'Accept'       : 'application/json',
        },
        body: JSON.stringify({
          plate      : plate,
          key_number : key,
        }),
      });
    } catch (networkErr) {
      console.error('[payment] Network error:', networkErr);
      showFailure(KioskUtils.t('payment.errorNetwork'));
      return;
    }

    if (!response.ok) {
      console.error('[payment] HTTP error:', response.status);
      showFailure(KioskUtils.t('payment.errorServer'));
      return;
    }

    var data;
    try {
      data = await response.json();
    } catch (parseErr) {
      console.error('[payment] Invalid JSON response');
      showFailure(KioskUtils.t('payment.errorServer'));
      return;
    }

    /* ── Balance = 0 → skip payment entirely ── */
    if (data.balance_zero === true || data.amount_cents === 0) {
      registrationRef = data.registration_ref || '';
      storePaymentResult('skipped', null);
      goNext();
      return;
    }

    /* ── Balance > 0 → show tap card ── */
    if (!data.client_secret || !data.payment_intent_id || !data.amount_cents) {
      console.error('[payment] Incomplete PI response');
      showFailure(KioskUtils.t('payment.errorServer'));
      return;
    }

    paymentIntentId    = data.payment_intent_id;
    registrationRef    = data.registration_ref || '';
    serverAmountCents  = data.amount_cents;
    serverClientSecret = data.client_secret;

    dom.amountValue.textContent = formatCentsToEuro(serverAmountCents);

    showView('view-tap');
    initiateNativePayment();
  }

  /* ══════════════════════════════════════════════════════════════
     NATIVE BRIDGE — call Android KioskBridge
     ══════════════════════════════════════════════════════════════ */

  function initiateNativePayment() {
    if (typeof window.KioskBridge !== 'undefined' &&
        typeof window.KioskBridge.initiatePayment === 'function') {
      /* Pass clientSecret (not amountCents) — amount is owned by server/Terminal */
      window.KioskBridge.initiatePayment(serverClientSecret, registrationRef);
    } else {
      console.warn('[payment] KioskBridge not available — running in browser?');
      /* In dev/browser mode, simulate a delayed failure so the UI is testable */
    }
  }

  function cancelNativePayment() {
    if (typeof window.KioskBridge !== 'undefined' &&
        typeof window.KioskBridge.cancelPayment === 'function') {
      window.KioskBridge.cancelPayment();
    }
  }

  /* ══════════════════════════════════════════════════════════════
     PAYMENT CALLBACKS — called by native bridge via evaluateJavascript
     ══════════════════════════════════════════════════════════════ */

  /**
   * Called by native bridge on successful payment.
   * @param {string} piId — PaymentIntent ID confirmed by Stripe
   */
  window.onPaymentSuccess = function (piId) {
    paymentIntentId = piId || paymentIntentId;
    retryCount = 0;

    storePaymentResult('paid', paymentIntentId);
    showView('view-success');

    setTimeout(goNext, SUCCESS_DELAY);
  };

  /**
   * Called by native bridge on payment failure.
   * @param {string} errorMsg — human-readable error from SDK
   */
  window.onPaymentFailure = function (errorMsg) {
    console.error('[payment] Native payment failed:', errorMsg);
    showFailure(errorMsg || KioskUtils.t('payment.failedBody'));
  };

  /* ══════════════════════════════════════════════════════════════
     FAILURE / RETRY
     ══════════════════════════════════════════════════════════════ */

  function showFailure(message) {
    if (message && typeof message === 'string') {
      dom.failedMessage.textContent = message;
    }

    retryCount++;

    /* After max retries, disable retry button */
    if (retryCount >= MAX_RETRIES) {
      dom.btnRetry.disabled = true;
      dom.btnRetry.style.opacity = '0.5';
    }

    showView('view-failed');
  }

  function handleRetry() {
    if (retryCount >= MAX_RETRIES) { return; }
    createPaymentIntent();
  }

  /* ══════════════════════════════════════════════════════════════
     SESSION STORAGE — pass payment result to next screen
     ══════════════════════════════════════════════════════════════ */

  function storePaymentResult(method, piId) {
    try {
      sessionStorage.setItem('kiosk_payment', JSON.stringify({
        method           : method,
        payment_intent_id: piId,
        registration_ref : registrationRef,
        amount_cents     : serverAmountCents,
      }));
    } catch (_) { /* storage blocked */ }
  }

  /* ══════════════════════════════════════════════════════════════
     NAVIGATION
     ══════════════════════════════════════════════════════════════ */

  function goHome() {
    cancelNativePayment();
    KioskUtils.clearLocale();
    cleanupSession();
    window.location.href = HOME_URL;
  }

  function goBack() {
    cancelNativePayment();
    window.location.href = BACK_URL;
  }

  function goNext() {
    window.location.href = NEXT_URL;
  }

  function cleanupSession() {
    try {
      sessionStorage.removeItem(SESSION_KEY);
      sessionStorage.removeItem('kiosk_payment');
    } catch (_) { /* storage blocked */ }
  }

  /* ══════════════════════════════════════════════════════════════
     i18n
     ══════════════════════════════════════════════════════════════ */

  function applyTranslations() {
    var nodes = document.querySelectorAll('[data-i18n]');
    for (var i = 0; i < nodes.length; i++) {
      var el  = nodes[i];
      var key = el.getAttribute('data-i18n');
      var val = KioskUtils.t(key);
      if (val && val !== key) {
        el.textContent = val;
      }
    }
  }

  /* ══════════════════════════════════════════════════════════════
     LOCALE RESOLUTION
     ══════════════════════════════════════════════════════════════ */

  function resolveLocale() {
    return KioskUtils.getSavedLocale();
  }

  /* ══════════════════════════════════════════════════════════════
     EVENT BINDING
     ══════════════════════════════════════════════════════════════ */

  function bindEvents() {
    dom.btnCancel.addEventListener('click', goBack);
    dom.btnFailedBack.addEventListener('click', goBack);
    dom.btnRetry.addEventListener('click', handleRetry);

    if (dom.btnInactivityContinue) {
      dom.btnInactivityContinue.addEventListener('click', function () {
        KioskUtils.dismissInactivityWarning();
      });
    }
  }

  /* ══════════════════════════════════════════════════════════════
     BOOT
     ══════════════════════════════════════════════════════════════ */

  async function boot() {
    dom.amountValue           = document.getElementById('amount-value');
    dom.btnCancel             = document.getElementById('btn-cancel');
    dom.btnFailedBack         = document.getElementById('btn-failed-back');
    dom.btnRetry              = document.getElementById('btn-retry');
    dom.failedMessage         = document.getElementById('failed-message');
    dom.btnInactivityContinue = document.getElementById('btn-inactivity-continue');

    /* Resolve locale */
    var locale = resolveLocale();

    try {
      await KioskUtils.loadLocale(locale);
    } catch (err) {
      console.error('[payment] Failed to load locale:', err);
      if (locale !== KioskUtils.DEFAULT_LOCALE) {
        try {
          await KioskUtils.loadLocale(KioskUtils.DEFAULT_LOCALE);
        } catch (_) { /* static fallback */ }
      }
    }

    document.documentElement.lang = locale;
    applyTranslations();

    bindEvents();

    /* Load registration — if missing, go back */
    registration = loadRegistration();

    if (!registration) {
      goBack();
      return;
    }

    /* Inactivity → reset to language screen */
    KioskUtils.initInactivityTimer(goHome);

    /* Start payment flow */
    createPaymentIntent();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }

}());