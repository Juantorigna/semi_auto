<?php
/**
 * stripe-config.php — Stripe API credentials
 *
 * SECURITY: This file must NEVER be web-accessible.
 *   - Place it outside the web root, or
 *   - Block via web.config <hiddenSegments>
 *
 * To configure: copy this file and fill in real keys.
 * The .env approach (like db.env) would also work — this uses
 * constants for simplicity since the file is already outside web root.
 */

declare(strict_types=1);

/* ── Stripe API Keys ──────────────────────────────────────── */

/**
 * Test-mode keys (start with sk_test_ / pk_test_).
 * Replace with live keys (sk_live_ / pk_live_) for production.
 */
define('STRIPE_SECRET_KEY',      'sk_test_REPLACE_WITH_YOUR_TEST_SECRET_KEY');
define('STRIPE_PUBLISHABLE_KEY', 'pk_test_REPLACE_WITH_YOUR_TEST_PUBLISHABLE_KEY');

/* ── Stripe Terminal Location ─────────────────────────────── */

/**
 * A Stripe Terminal Location ID. Create one in the Stripe Dashboard
 * under Terminal > Locations, or via the API:
 *
 *   \Stripe\Terminal\Location::create([
 *       'display_name' => 'Area Camper Bergamo — Kiosk',
 *       'address' => [
 *           'line1'       => 'Via ...',
 *           'city'        => 'Bergamo',
 *           'country'     => 'IT',
 *           'postal_code' => '24100',
 *       ],
 *   ]);
 */
define('STRIPE_TERMINAL_LOCATION', 'tml_REPLACE_WITH_YOUR_LOCATION_ID');
