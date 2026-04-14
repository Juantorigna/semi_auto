/**
 * confirm.js — Screen 4: Confirm / Payment
 *
 * Responsibilities
 *   · Reads booking data + balance from sessionStorage (set by match.js).
 *   · If balance > 0 → shows the payment view and calls the Android bridge
 *     to collect payment via the Stripe Terminal SDK.
 *   · If balance = 0 → shows the "no amount due" view with auto-advance.
 *   · Receives payment results from the Android bridge via a global callback.
 *   · On success → auto-advance to the next screen after 2 seconds.
 *   · On failure → shows error with a retry button.
 *
 * Android Bridge
 *   The Kotlin Android app exposes `window.AndroidBridge` with:
 *     - collectPayment(amountCents: int) — starts Stripe Terminal payment
 *     - cancelPayment()                 — cancels an in-progress payment
 *
 *   The Android app calls back via:
 *     - window.onPaymentResult(success: boolean, message: string)
 *
 *   When running in a plain browser (no Android shell), the bridge is absent.
 *   The UI will show an error message in that case.
 *
 * Security
 *   · All DOM writes use textContent — never innerHTML.
 *   · No user-controlled fetch targets.
 *   · Balance read from sessionStorage is re-validated as a number.
 *
 * Depends on: utils.js (KioskUtils — must load first)
 */

'use strict';

(function () {

  /* ── Constants ── */
  var HOME_URL = 'checkout.html';
  var NEXT_URL = 'match.html';        /* Go back to match on cancel */
  var DONE_URL = 'checkout.html';     /* After success → reset (until key-drop screen exists) */
  var AUTO_ADVANCE_MS = 2500;

  /* ── DOM references (resolved in boot) ── */
  var dom = {};

  /* ── State ── */
  var balanceCents   = 0;
  var paymentActive  = false;
  var advanceTimer   = null;

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

  function formatCents(cents) {
    var euros = cents / 100;
    return '\u20AC ' + euros.toFixed(2).replace('.', ',');
  }

  /* ══════════════════════════════════════════════════════════════
     PAYMENT FLOW
     ══════════════════════════════════════════════════════════════ */

  function startPayment() {
    /* Check if the Android bridge is available */
    if (typeof window.AndroidBridge === 'undefined' ||
        typeof window.AndroidBridge.collectPayment !== 'function') {
      showError(KioskUtils.t('confirm.errorBridge'));
      return;
    }

    paymentActive = true;
    dom.paymentStatus.textContent = KioskUtils.t('confirm.waiting');
    dom.paymentStatus.classList.remove('processing');

    /* Call the Android bridge — it will call back via onPaymentResult */
    window.AndroidBridge.collectPayment(balanceCents);
  }

  function cancelPayment() {
    if (paymentActive && typeof window.AndroidBridge !== 'undefined' &&
        typeof window.AndroidBridge.cancelPayment === 'function') {
      window.AndroidBridge.cancelPayment();
    }
    paymentActive = false;
    window.location.href = NEXT_URL;
  }

  function retryPayment() {
    showView('view-payment');
    startPayment();
  }

  /* ══════════════════════════════════════════════════════════════
     CALLBACKS FROM ANDROID BRIDGE
     ══════════════════════════════════════════════════════════════ */

  /**
   * Called by the Android Kotlin code after payment completes.
   *
   * @param {boolean} success — true if payment was captured
   * @param {string}  message — human-readable status or error detail
   */
  window.onPaymentResult = function (success, message) {
    paymentActive = false;

    if (success) {
      showView('view-success');
      scheduleAdvance();
    } else {
      showError(message || KioskUtils.t('confirm.error'));
    }
  };

  /**
   * Called by the Android Kotlin code to update the status message
   * during the payment flow (e.g. "Insert card", "Processing…").
   *
   * @param {string} status — status text to display
   */
  window.onPaymentStatusUpdate = function (status) {
    if (dom.paymentStatus) {
      dom.paymentStatus.textContent = status;
      dom.paymentStatus.classList.add('processing');
    }
  };

  /* ══════════════════════════════════════════════════════════════
     ERROR & NAVIGATION
     ══════════════════════════════════════════════════════════════ */

  function showError(msg) {
    dom.errorMessage.textContent = msg || '';
    showView('view-error');
  }

  function scheduleAdvance() {
    if (advanceTimer) { clearTimeout(advanceTimer); }
    advanceTimer = setTimeout(function () {
      /* Navigate to next screen (key-drop or checkout complete).
         For now, go back to Screen 1 until those screens are built. */
      window.location.href = DONE_URL;
    }, AUTO_ADVANCE_MS);
  }

  function goHome() {
    if (advanceTimer) { clearTimeout(advanceTimer); }
    KioskUtils.clearLocale();
    window.location.href = HOME_URL;
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

  function resolveLocale() {
    return KioskUtils.getSavedLocale();
  }

  /* ══════════════════════════════════════════════════════════════
     EVENT BINDING
     ══════════════════════════════════════════════════════════════ */

  function bindEvents() {
    dom.btnCancel.addEventListener('click', cancelPayment);
    dom.btnRetry.addEventListener('click', retryPayment);

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
    dom.amountValue            = document.getElementById('amount-value');
    dom.paymentStatus          = document.getElementById('payment-status');
    dom.btnCancel              = document.getElementById('btn-cancel');
    dom.btnRetry               = document.getElementById('btn-retry');
    dom.errorMessage           = document.getElementById('error-message');
    dom.btnInactivityContinue  = document.getElementById('btn-inactivity-continue');

    /* Resolve locale */
    var locale = resolveLocale();

    try {
      await KioskUtils.loadLocale(locale);
    } catch (err) {
      console.error('[confirm] Failed to load locale:', err);
      if (locale !== KioskUtils.DEFAULT_LOCALE) {
        try {
          await KioskUtils.loadLocale(KioskUtils.DEFAULT_LOCALE);
        } catch (_) { /* static fallback */ }
      }
    }

    document.documentElement.lang = locale;
    applyTranslations();
    bindEvents();

    /* Read balance from sessionStorage */
    var rawBalance = sessionStorage.getItem('kiosk_balance_cents');
    balanceCents = parseInt(rawBalance, 10);
    if (isNaN(balanceCents) || balanceCents < 0) { balanceCents = 0; }

    /* Display amount */
    dom.amountValue.textContent = formatCents(balanceCents);

    /* Branch: payment needed vs. no balance due */
    if (balanceCents > 0) {
      showView('view-payment');
      startPayment();
    } else {
      showView('view-no-due');
      scheduleAdvance();
    }

    /* Inactivity → reset to language screen */
    KioskUtils.initInactivityTimer(goHome);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }

}());
