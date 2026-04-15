/**
 * match.js — Screen 3: License Plate + Key Number Entry & Lookup
 *
 * Responsibilities
 *   · On-screen keyboard drives two input fields (plate, key).
 *   · Tapping an input field sets it as the active target.
 *   · "Search" POSTs to the lookup API with plate + key.
 *   · On match  → populates the result card and switches to view-result.
 *   · No match  → switches to view-nomatch with a 5-second countdown
 *                  that redirects to checkout.html (language selection).
 *   · "Back" on the result view returns to entry.
 *   · "Confirm" on the result view navigates to the next screen (TBD).
 *   · "Try again" on no-match cancels countdown and returns to entry.
 *   · Inactivity timeout → clearLocale() + redirect to checkout.html.
 *
 * Security
 *   · Plate input is sanitised to A-Z 0-9 only, max 10 chars.
 *   · Key input is sanitised to 0-9 only, max 3 chars.
 *   · All DOM writes use textContent — never innerHTML.
 *   · fetch() uses credentials:'same-origin', POST + JSON body.
 *   · API URL is a relative same-origin path — no user-controlled URLs.
 *
 * Depends on: utils.js (KioskUtils — must load first)
 */

'use strict';

(function () {

  /* ── Constants ── */
  var HOME_URL       = 'checkout.html';
  var NEXT_URL       = 'confirm.html';       /* Screen 4 — TBD */
  var API_LOOKUP_URL = '../../app/api/lookup.php';
  var PLATE_MAX      = 10;
  var KEY_MAX        = 3;
  var PLATE_REGEX    = /^[A-Z0-9]+$/;
  var KEY_REGEX      = /^[0-9]+$/;
  var COUNTDOWN_SEC  = 5;

  /* ── DOM references (resolved in boot) ── */
  var dom = {};

  /* ── State ── */
  var activeField       = null;   /* 'plate' | 'key' */
  var countdownTimer    = null;
  var countdownValue    = 0;
  var currentBooking    = null;   /* stashed for confirm navigation */
  var currentBalance    = 0;      /* computed balance for current booking */

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
     INPUT FOCUS MANAGEMENT
     ══════════════════════════════════════════════════════════════ */

  function setActiveField(field) {
    activeField = field;
    dom.inputPlate.classList.toggle('focused', field === 'plate');
    dom.inputKey.classList.toggle('focused', field === 'key');
  }

  /* ══════════════════════════════════════════════════════════════
     ON-SCREEN KEYBOARD
     ══════════════════════════════════════════════════════════════ */

  function handleKeyPress(char) {
    if (!activeField) { return; }

    if (activeField === 'plate') {
      var plate = dom.inputPlate.value;
      if (plate.length < PLATE_MAX) {
        dom.inputPlate.value = plate + char.toUpperCase();
      }
      clearFieldError('plate');
    } else if (activeField === 'key') {
      /* Key field only accepts digits */
      if (!/^[0-9]$/.test(char)) { return; }
      var key = dom.inputKey.value;
      if (key.length < KEY_MAX) {
        dom.inputKey.value = key + char;
      }
      clearFieldError('key');
    }

    updateSearchButton();
  }

  function handleBackspace() {
    if (!activeField) { return; }

    if (activeField === 'plate') {
      dom.inputPlate.value = dom.inputPlate.value.slice(0, -1);
    } else if (activeField === 'key') {
      dom.inputKey.value = dom.inputKey.value.slice(0, -1);
    }

    updateSearchButton();
  }

  /* ══════════════════════════════════════════════════════════════
     VALIDATION
     ══════════════════════════════════════════════════════════════ */

  function clearFieldError(field) {
    if (field === 'plate') {
      dom.errorPlate.textContent = '';
      dom.inputPlate.classList.remove('error');
    } else {
      dom.errorKey.textContent = '';
      dom.inputKey.classList.remove('error');
    }
  }

  function validate() {
    var plate = dom.inputPlate.value.trim();
    var key   = dom.inputKey.value.trim();
    var valid = true;

    if (plate.length === 0) {
      dom.errorPlate.textContent = KioskUtils.t('match.errorPlateEmpty');
      dom.inputPlate.classList.add('error');
      valid = false;
    } else if (!PLATE_REGEX.test(plate)) {
      dom.errorPlate.textContent = KioskUtils.t('match.errorPlateInvalid');
      dom.inputPlate.classList.add('error');
      valid = false;
    } else {
      clearFieldError('plate');
    }

    if (key.length === 0) {
      dom.errorKey.textContent = KioskUtils.t('match.errorKeyEmpty');
      dom.inputKey.classList.add('error');
      valid = false;
    } else if (!KEY_REGEX.test(key)) {
      dom.errorKey.textContent = KioskUtils.t('match.errorKeyInvalid');
      dom.inputKey.classList.add('error');
      valid = false;
    } else {
      clearFieldError('key');
    }

    return valid;
  }

  function updateSearchButton() {
    var plate = dom.inputPlate.value.trim();
    var key   = dom.inputKey.value.trim();
    dom.btnSearch.disabled = (plate.length === 0 || key.length === 0);
  }

  /* ══════════════════════════════════════════════════════════════
     API LOOKUP
     ══════════════════════════════════════════════════════════════ */

  async function doLookup() {
    if (!validate()) { return; }

    var plate = dom.inputPlate.value.trim().toUpperCase();
    var key   = dom.inputKey.value.trim();

    dom.loadingOverlay.hidden = false;

    var response;
    try {
      response = await fetch(API_LOOKUP_URL, {
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
      dom.loadingOverlay.hidden = true;
      console.error('[match] Network error:', networkErr);
      showNoMatch();
      return;
    }

    dom.loadingOverlay.hidden = true;

    if (!response.ok) {
      if (response.status === 404) {
        showNoMatch();
        return;
      }
      console.error('[match] HTTP error:', response.status);
      showNoMatch();
      return;
    }

    var data;
    try {
      data = await response.json();
    } catch (parseErr) {
      console.error('[match] Invalid JSON response');
      showNoMatch();
      return;
    }

    if (data && data.found === true && data.booking) {
      showResult(data.booking);
    } else {
      showNoMatch();
    }
  }

  /* ══════════════════════════════════════════════════════════════
     RESULT VIEW — populate table
     ══════════════════════════════════════════════════════════════ */

  /**
   * Safely escapes a value for textContent insertion.
   * Returns '—' for null/undefined/empty.
   */
  function safe(val) {
    if (val === null || val === undefined || val === '') { return '\u2014'; }
    return String(val);
  }

  function formatDate(dateStr) {
    if (!dateStr) { return '\u2014'; }
    /* Expect ISO date or datetime — extract date part */
    var d = new Date(dateStr);
    if (isNaN(d.getTime())) { return safe(dateStr); }
    return d.toLocaleDateString('it-IT', {
      day: '2-digit', month: '2-digit', year: 'numeric',
    });
  }

  /* Format a DB datetime value showing both date and time in Rome timezone */
  function formatDateTime(dateStr) {
    if (!dateStr) { return '\u2014'; }
    var d = new Date(dateStr);
    if (isNaN(d.getTime())) { return safe(dateStr); }
    return d.toLocaleString('it-IT', {
      day: '2-digit', month: '2-digit', year: 'numeric',
      hour: '2-digit', minute: '2-digit',
      timeZone: 'Europe/Rome',
    });
  }

  /* Current date+time in the Rome timezone (handles DST automatically) */
  function romaDateTimeNow() {
    return new Date().toLocaleString('it-IT', {
      day: '2-digit', month: '2-digit', year: 'numeric',
      hour: '2-digit', minute: '2-digit',
      timeZone: 'Europe/Rome',
    });
  }

  /* Show the numeric amperage (4, 7, 10) or "No" if absent/zero */
  function formatElectricity(val) {
    if (val === null || val === undefined || val === '' || val === 0 || val === '0') {
      return KioskUtils.t('match.no');
    }
    var n = parseFloat(val);
    if (!isNaN(n) && n > 0) { return String(n) + ' A'; }
    var s = String(val).trim().toLowerCase();
    if (s === 'no' || s === 'n' || s === 'false') {
      return KioskUtils.t('match.no');
    }
    return safe(val);
  }

  function formatCurrency(val) {
    if (val === null || val === undefined) { return '\u2014'; }
    var num = parseFloat(val);
    if (isNaN(num)) { return safe(val); }
    return '\u20AC ' + num.toFixed(2).replace('.', ',');
  }

  function yesNo(val) {
    if (!val) { return 'no'; }
    var s = String(val).trim().toLowerCase();
    return (s === 'yes' || s === 'si' || s === 'sì' || s === 'y' || s === '1')
      ? 'yes' : 'no';
  }

  /* ══════════════════════════════════════════════════════════════
     BILLING CALCULATION (ported from gestionale edit.js)
     ══════════════════════════════════════════════════════════════ */

  function getWallClockHours(start, end) {
    var elapsed  = (end - start) / (1000 * 60 * 60);
    var dstShift = (start.getTimezoneOffset() - end.getTimezoneOffset()) / 60;
    return elapsed + dstShift;
  }

  function calculateDailyRate(hoursElapsed) {
    var cycleDay = Math.floor(hoursElapsed / 24) % 9;
    return cycleDay < 3 ? 18 : 15;
  }

  function calculateAdditionalHoursRate(hoursElapsed, additionalHours) {
    var cycleDay               = Math.floor(hoursElapsed / 24) % 9;
    var ratePerHour            = 3;
    var maxHoursBeforeFullRate = (cycleDay < 3) ? 6 : 5;
    var fullDayRate            = (cycleDay < 3) ? 18 : 15;

    if (additionalHours <= maxHoursBeforeFullRate) {
      return additionalHours * ratePerHour;
    } else {
      return fullDayRate;
    }
  }

  function calculateAmpersCharge(corrente) {
    switch (corrente) {
      case 4:   return 0;
      case 7:   return 6;
      case 10:  return 10;
      default:  return 0;
    }
  }

  function calculateTotalStayCharge(arrivalDate, departureDate, hasCar, corrente) {
    var totalHours     = Math.ceil(getWallClockHours(arrivalDate, departureDate));
    var totalCharge    = 0;
    var hoursRemaining = totalHours;
    var carCharge      = 0;
    var ampersCharge   = 0;
    var selectedAmpers = parseFloat(corrente) || 0;

    while (hoursRemaining > 0) {
      if (hoursRemaining >= 24) {
        totalCharge    += calculateDailyRate(totalHours - hoursRemaining);
        hoursRemaining -= 24;
        ampersCharge   += calculateAmpersCharge(selectedAmpers);
        if (hasCar) { carCharge += 5; }
      } else {
        totalCharge  += calculateAdditionalHoursRate(totalHours - hoursRemaining, hoursRemaining);
        ampersCharge += calculateAmpersCharge(selectedAmpers);
        if (hasCar) { carCharge += Math.min(5, hoursRemaining); }
        break;
      }
    }

    return totalCharge + carCharge + ampersCharge;
  }

  function showResult(booking) {
    currentBooking = booking;

    var tbody = dom.infoTbody;
    /* Clear previous rows */
    while (tbody.firstChild) { tbody.removeChild(tbody.firstChild); }

    var paidAdvance = (parseFloat(booking.Amount) || 0) + (parseFloat(booking.AmountAdvance) || 0);

    /* Calculate total stay charge from arrival to now */
    var arrivalDate   = new Date(booking['Arrival DateTime']);
    var departureDate = new Date();
    var hasCar        = yesNo(booking['Has Car']) === 'yes';
    var corrente      = parseFloat(booking.Corrente) || 0;
    var totalCharge   = calculateTotalStayCharge(arrivalDate, departureDate, hasCar, corrente);

    /* Stash computed values for the confirm/payment screen */
    currentBalance    = totalCharge - paidAdvance;
    if (currentBalance < 0) { currentBalance = 0; }

    var rows = [
      { label: KioskUtils.t('match.infoName'),        value: safe(booking.Name) },
      { label: KioskUtils.t('match.infoPlate'),        value: safe(booking.Plate) },
      { label: KioskUtils.t('match.infoKey'),          value: safe(booking.Chiavetta) },
      { label: KioskUtils.t('match.infoPitch'),        value: safe(booking.Piazzola) },
      { label: KioskUtils.t('match.infoArrival'),      value: formatDateTime(booking['Arrival DateTime']) },
      { label: KioskUtils.t('match.infoDeparture'),    value: romaDateTimeNow() },
      { label: KioskUtils.t('match.infoCategory'),     value: safe(booking.Category) },
      { label: KioskUtils.t('match.infoTotalCharge'),  value: formatCurrency(totalCharge),  cls: 'highlight' },
      { label: KioskUtils.t('match.infoPaidAdvance'),  value: formatCurrency(paidAdvance),              cls: '' },
      { label: KioskUtils.t('match.infoElectricity'),  value: formatElectricity(booking.Corrente) },
      { label: KioskUtils.t('match.infoHasCar'),       value: badgeHtml(booking['Has Car']) },
    ];

    for (var i = 0; i < rows.length; i++) {
      var tr = document.createElement('tr');
      if (rows[i].cls) { tr.className = rows[i].cls; }

      var th = document.createElement('th');
      th.textContent = rows[i].label;
      tr.appendChild(th);

      var td = document.createElement('td');
      /* badgeHtml returns an element; everything else is a string */
      if (rows[i].value instanceof HTMLElement) {
        td.appendChild(rows[i].value);
      } else {
        td.textContent = rows[i].value;
      }
      tr.appendChild(td);

      tbody.appendChild(tr);
    }

    /* Compute and show balance row */
    var balance  = totalCharge - paidAdvance;

    var balanceTr = document.createElement('tr');
    balanceTr.className = 'balance ' + (balance <= 0 ? 'paid' : 'due');

    var balanceTh = document.createElement('th');
    balanceTh.textContent = KioskUtils.t('match.infoBalance');
    balanceTr.appendChild(balanceTh);

    var balanceTd = document.createElement('td');
    balanceTd.textContent = formatCurrency(balance > 0 ? balance : 0);
    balanceTr.appendChild(balanceTd);

    tbody.appendChild(balanceTr);

    /* Update confirm button text based on balance */
    var confirmSpan = dom.btnResultConfirm.querySelector('span');
    if (balance > 0) {
      confirmSpan.textContent = KioskUtils.t('match.goToPayment');
    } else {
      confirmSpan.textContent = KioskUtils.t('match.continue');
    }

    showView('view-result');
  }

  /**
   * Returns a <span> badge element for yes/no values.
   * Never uses innerHTML — builds elements programmatically.
   */
  function badgeHtml(val) {
    var isYes = yesNo(val) === 'yes';
    var span  = document.createElement('span');
    span.className = 'badge ' + (isYes ? 'badge--yes' : 'badge--no');
    span.textContent = isYes
      ? KioskUtils.t('match.yes')
      : KioskUtils.t('match.no');
    return span;
  }

  /* ══════════════════════════════════════════════════════════════
     NO-MATCH VIEW — countdown + redirect
     ══════════════════════════════════════════════════════════════ */

  function showNoMatch() {
    showView('view-nomatch');
    startCountdown();
  }

  function startCountdown() {
    stopCountdown();
    countdownValue = COUNTDOWN_SEC;
    dom.countdownNumber.textContent = String(countdownValue);

    countdownTimer = setInterval(function () {
      countdownValue--;
      if (countdownValue <= 0) {
        stopCountdown();
        goHome();
        return;
      }
      dom.countdownNumber.textContent = String(countdownValue);
    }, 1000);
  }

  function stopCountdown() {
    if (countdownTimer !== null) {
      clearInterval(countdownTimer);
      countdownTimer = null;
    }
  }

  /* ══════════════════════════════════════════════════════════════
     NAVIGATION
     ══════════════════════════════════════════════════════════════ */

  function goHome() {
    stopCountdown();
    KioskUtils.clearLocale();
    window.location.href = HOME_URL;
  }

  function goBackToEntry() {
    stopCountdown();
    currentBooking = null;
    showView('view-entry');
    dom.inputPlate.value = '';
    dom.inputKey.value   = '';
    clearFieldError('plate');
    clearFieldError('key');
    updateSearchButton();
    setActiveField('plate');
  }

  function goConfirm() {
    /* Navigate to next screen — booking data + balance passed via sessionStorage */
    if (currentBooking) {
      try {
        sessionStorage.setItem('kiosk_booking', JSON.stringify(currentBooking));
        sessionStorage.setItem('kiosk_balance_cents', String(Math.round(currentBalance * 100)));
      } catch (_) { /* storage blocked */ }
    }
    window.location.href = NEXT_URL;
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
     LOCALE RESOLUTION (same pattern as exit_bar.js)
     ══════════════════════════════════════════════════════════════ */

  function resolveLocale() {
    var stored = KioskUtils.getSavedLocale();
    return stored;
  }

  /* ══════════════════════════════════════════════════════════════
     EVENT BINDING
     ══════════════════════════════════════════════════════════════ */

  function bindEvents() {
    /* Input field focus */
    dom.inputPlate.addEventListener('click', function () { setActiveField('plate'); });
    dom.inputKey.addEventListener('click',   function () { setActiveField('key'); });

    /* Keyboard keys */
    var keys = document.querySelectorAll('.key[data-key]');
    for (var i = 0; i < keys.length; i++) {
      keys[i].addEventListener('click', function (e) {
        handleKeyPress(e.currentTarget.getAttribute('data-key'));
      });
    }

    /* Backspace */
    dom.keyBackspace.addEventListener('click', handleBackspace);

    /* Search */
    dom.btnSearch.addEventListener('click', doLookup);

    /* Result actions */
    dom.btnResultBack.addEventListener('click', goBackToEntry);
    dom.btnResultConfirm.addEventListener('click', goConfirm);

    /* No-match retry */
    dom.btnRetry.addEventListener('click', goBackToEntry);

    /* Inactivity continue */
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
    dom.inputPlate            = document.getElementById('input-plate');
    dom.inputKey              = document.getElementById('input-key');
    dom.errorPlate            = document.getElementById('error-plate');
    dom.errorKey              = document.getElementById('error-key');
    dom.keyBackspace          = document.getElementById('key-backspace');
    dom.btnSearch             = document.getElementById('btn-search');
    dom.loadingOverlay        = document.getElementById('loading-overlay');
    dom.infoTbody             = document.getElementById('info-tbody');
    dom.btnResultBack         = document.getElementById('btn-result-back');
    dom.btnResultConfirm      = document.getElementById('btn-result-confirm');
    dom.btnRetry              = document.getElementById('btn-retry');
    dom.countdownNumber       = document.getElementById('countdown-number');
    dom.btnInactivityContinue = document.getElementById('btn-inactivity-continue');

    /* Resolve locale */
    var locale = resolveLocale();

    try {
      await KioskUtils.loadLocale(locale);
    } catch (err) {
      console.error('[match] Failed to load locale:', err);
      if (locale !== KioskUtils.DEFAULT_LOCALE) {
        try {
          await KioskUtils.loadLocale(KioskUtils.DEFAULT_LOCALE);
        } catch (_) { /* static fallback */ }
      }
    }

    document.documentElement.lang = locale;
    applyTranslations();

    /* Default focus to plate field */
    setActiveField('plate');

    bindEvents();

    /* Inactivity → reset to language screen */
    KioskUtils.initInactivityTimer(goHome);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }

}());