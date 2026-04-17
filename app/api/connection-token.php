<?php
/**
 * connection-token.php — Stripe Terminal Connection Token Provider
 *
 * GET /app/api/connection-token.php
 *
 * Called by the Stripe Terminal Android SDK on every Terminal.initTerminal()
 * and whenever the SDK needs to refresh its session. Returns a short-lived
 * connection secret that authenticates the reader to this Stripe account.
 *
 * Security:
 *   - Stripe secret key loaded from stripe.env outside web root
 *   - Only GET allowed (SDK uses GET; POST also accepted for flexibility)
 *   - No user input accepted or reflected
 *   - Security headers on every response
 *   - Errors logged server-side; never exposed to client
 *   - CORS restricted to same origin
 */

declare(strict_types=1);

/* ── Security headers ──────────────────────────────────────────────────────── */
header('Content-Type: application/json; charset=utf-8');
header('X-Content-Type-Options: nosniff');
header('X-Frame-Options: DENY');
header('Cache-Control: no-store, no-cache, must-revalidate');
header('Pragma: no-cache');
header('Referrer-Policy: no-referrer');

/* ── CORS — restrict to same origin ───────────────────────────────────────── */
$allowedOrigin = 'https://areacamperbergamo.it';
$requestOrigin = $_SERVER['HTTP_ORIGIN'] ?? '';

if ($requestOrigin === $allowedOrigin) {
    header('Access-Control-Allow-Origin: ' . $allowedOrigin);
    header('Access-Control-Allow-Methods: GET, POST, OPTIONS');
    header('Access-Control-Allow-Headers: Content-Type, Accept');
    header('Access-Control-Max-Age: 3600');
}

/* ── Handle preflight ─────────────────────────────────────────────────────── */
if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(204);
    exit;
}

/* ── Only GET / POST allowed ──────────────────────────────────────────────── */
if (!in_array($_SERVER['REQUEST_METHOD'], ['GET', 'POST'], true)) {
    http_response_code(405);
    echo json_encode(['error' => 'Method not allowed']);
    exit;
}

/* ── Load Stripe credentials from stripe.env ──────────────────────────────── */
$envPath = realpath(__DIR__ . '/../../../stripe.env');

if ($envPath === false || !is_file($envPath)) {
    error_log('[connection-token.php] stripe.env not found at expected path');
    http_response_code(500);
    echo json_encode(['error' => 'Server configuration error']);
    exit;
}

$lines = file($envPath, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES);

if ($lines === false) {
    error_log('[connection-token.php] Could not read stripe.env');
    http_response_code(500);
    echo json_encode(['error' => 'Server configuration error']);
    exit;
}

$envVars = [];

foreach ($lines as $line) {
    $line = trim($line);

    if ($line === '' || $line[0] === '#') {
        continue;
    }

    $eqPos = strpos($line, '=');

    if ($eqPos === false) {
        continue;
    }

    $envKey = trim(substr($line, 0, $eqPos));
    $envVal = trim(substr($line, $eqPos + 1));
    $envVars[$envKey] = $envVal;
}

/* ── Select live or test key ──────────────────────────────────────────────── */
$useLive = false; // set false to use test key

$stripeSecretKey = $useLive
    ? ($envVars['STRIPE_SECRET_KEY'] ?? '')
    : ($envVars['STRIPE_TEST_KEY']   ?? '');

if ($stripeSecretKey === '') {
    error_log('[connection-token.php] Stripe key missing from stripe.env');
    http_response_code(500);
    echo json_encode(['error' => 'Server configuration error']);
    exit;
}

/* ── Validate key prefix — must be sk_live_ or sk_test_ ──────────────────── */
if (
    strncmp($stripeSecretKey, 'sk_live_', 8) !== 0 &&
    strncmp($stripeSecretKey, 'sk_test_', 8) !== 0
) {
    error_log('[connection-token.php] STRIPE_SECRET_KEY has unexpected prefix');
    http_response_code(500);
    echo json_encode(['error' => 'Server configuration error']);
    exit;
}

/* ── Load Stripe PHP SDK via Composer autoloader ──────────────────────────── */
$autoloadPath = realpath(__DIR__ . '/../../../vendor/autoload.php');

if ($autoloadPath === false || !is_file($autoloadPath)) {
    error_log('[connection-token.php] Composer autoload.php not found');
    http_response_code(500);
    echo json_encode(['error' => 'Server configuration error']);
    exit;
}

require_once $autoloadPath;

/* ── Create Stripe Terminal connection token ──────────────────────────────── */
try {
    $stripe = new \Stripe\StripeClient($stripeSecretKey);

    $connectionToken = $stripe->terminal->connectionTokens->create([]);

    if (empty($connectionToken->secret)) {
        error_log('[connection-token.php] Stripe returned empty secret');
        http_response_code(500);
        echo json_encode(['error' => 'Failed to create connection token']);
        exit;
    }

    echo json_encode(['secret' => $connectionToken->secret]);

} catch (\Stripe\Exception\AuthenticationException $e) {
    error_log('[connection-token.php] Stripe authentication failed: ' . $e->getMessage());
    http_response_code(500);
    echo json_encode(['error' => 'Payment provider authentication error']);
    exit;
} catch (\Stripe\Exception\ApiConnectionException $e) {
    error_log('[connection-token.php] Stripe API unreachable: ' . $e->getMessage());
    http_response_code(503);
    echo json_encode(['error' => 'Payment provider temporarily unavailable']);
    exit;
} catch (\Stripe\Exception\ApiErrorException $e) {
    error_log('[connection-token.php] Stripe API error: ' . $e->getMessage());
    http_response_code(500);
    echo json_encode(['error' => 'Payment provider error']);
    exit;
} catch (\Exception $e) {
    error_log('[connection-token.php] Unexpected error: ' . $e->getMessage());
    http_response_code(500);
    echo json_encode(['error' => 'Internal server error']);
    exit;
}