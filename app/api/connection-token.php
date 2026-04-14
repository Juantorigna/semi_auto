<?php
/**
 * connection-token.php — Stripe Terminal Connection Token Provider
 *
 * GET → returns {"secret": "pst_test_..."}
 *
 * The Stripe Terminal SDK on the Android app calls this endpoint
 * to obtain a short-lived connection token. This token allows the
 * SDK to communicate with Stripe's servers on behalf of your account.
 *
 * Security:
 *   - Uses Stripe secret key (server-side only, never exposed to client)
 *   - Security headers on every response
 *   - CORS restricted to same-origin
 */

declare(strict_types=1);

/* ── Security headers ──────────────────────────────────────── */
header('Content-Type: application/json; charset=utf-8');
header('X-Content-Type-Options: nosniff');
header('X-Frame-Options: DENY');
header('Cache-Control: no-store, no-cache, must-revalidate');
header('Pragma: no-cache');
header('Referrer-Policy: no-referrer');

/* ── CORS — same-origin + Android app ─────────────────────── */
$allowedOrigins = [
    'https://areacamperbergamo.it',
];
$requestOrigin = $_SERVER['HTTP_ORIGIN'] ?? '';

if (in_array($requestOrigin, $allowedOrigins, true)) {
    header('Access-Control-Allow-Origin: ' . $requestOrigin);
    header('Access-Control-Allow-Methods: GET, OPTIONS');
    header('Access-Control-Allow-Headers: Content-Type, Accept');
    header('Access-Control-Max-Age: 3600');
}

/* ── Handle preflight ──────────────────────────────────────── */
if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(204);
    exit;
}

/* ── Only GET allowed ──────────────────────────────────────── */
if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    http_response_code(405);
    echo json_encode(['error' => 'Method not allowed']);
    exit;
}

/* ── Load Stripe config ────────────────────────────────────── */
$configPath = realpath(__DIR__ . '/../private/stripe-config.php');

if ($configPath === false || !is_file($configPath)) {
    error_log('[connection-token.php] stripe-config.php not found');
    http_response_code(500);
    echo json_encode(['error' => 'Server configuration error']);
    exit;
}

require_once $configPath;

/* ── Load Stripe PHP SDK ───────────────────────────────────── */
$autoloadPath = realpath(__DIR__ . '/../../vendor/autoload.php');

if ($autoloadPath === false || !is_file($autoloadPath)) {
    error_log('[connection-token.php] Stripe SDK not installed (vendor/autoload.php missing)');
    http_response_code(500);
    echo json_encode(['error' => 'Stripe SDK not installed']);
    exit;
}

require_once $autoloadPath;

/* ── Create connection token ───────────────────────────────── */
try {
    \Stripe\Stripe::setApiKey(STRIPE_SECRET_KEY);

    $connectionToken = \Stripe\Terminal\ConnectionToken::create();

    echo json_encode([
        'secret' => $connectionToken->secret,
    ]);

} catch (\Stripe\Exception\ApiErrorException $e) {
    error_log('[connection-token.php] Stripe API error: ' . $e->getMessage());
    http_response_code(500);
    echo json_encode(['error' => 'Failed to create connection token']);
    exit;
} catch (\Exception $e) {
    error_log('[connection-token.php] Unexpected error: ' . $e->getMessage());
    http_response_code(500);
    echo json_encode(['error' => 'Internal server error']);
    exit;
}
