<?php
/**
 * create-payment-intent.php — Create a Stripe PaymentIntent for Terminal
 *
 * POST JSON: { "amount": 2500 }
 *   - amount: integer, in cents (e.g. 2500 = €25.00)
 *
 * Returns: { "client_secret": "pi_..._secret_...", "id": "pi_..." }
 *
 * The Android app calls this endpoint before collecting payment.
 * The PaymentIntent is created with `payment_method_types: ['card_present']`
 * and `capture_method: 'automatic'` for in-person Terminal payments.
 *
 * Security:
 *   - Amount validated: must be a positive integer ≥ 50 (Stripe minimum)
 *   - Uses Stripe secret key (server-side only)
 *   - Security headers on every response
 */

declare(strict_types=1);

/* ── Security headers ──────────────────────────────────────── */
header('Content-Type: application/json; charset=utf-8');
header('X-Content-Type-Options: nosniff');
header('X-Frame-Options: DENY');
header('Cache-Control: no-store, no-cache, must-revalidate');
header('Pragma: no-cache');
header('Referrer-Policy: no-referrer');

/* ── CORS ──────────────────────────────────────────────────── */
$allowedOrigins = [
    'https://areacamperbergamo.it',
];
$requestOrigin = $_SERVER['HTTP_ORIGIN'] ?? '';

if (in_array($requestOrigin, $allowedOrigins, true)) {
    header('Access-Control-Allow-Origin: ' . $requestOrigin);
    header('Access-Control-Allow-Methods: POST, OPTIONS');
    header('Access-Control-Allow-Headers: Content-Type, Accept');
    header('Access-Control-Max-Age: 3600');
}

/* ── Handle preflight ──────────────────────────────────────── */
if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(204);
    exit;
}

/* ── Only POST allowed ─────────────────────────────────────── */
if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    http_response_code(405);
    echo json_encode(['error' => 'Method not allowed']);
    exit;
}

/* ── Load Stripe config ────────────────────────────────────── */
$configPath = realpath(__DIR__ . '/../private/stripe-config.php');

if ($configPath === false || !is_file($configPath)) {
    error_log('[create-payment-intent.php] stripe-config.php not found');
    http_response_code(500);
    echo json_encode(['error' => 'Server configuration error']);
    exit;
}

require_once $configPath;

/* ── Load Stripe PHP SDK ───────────────────────────────────── */
$autoloadPath = realpath(__DIR__ . '/../../vendor/autoload.php');

if ($autoloadPath === false || !is_file($autoloadPath)) {
    error_log('[create-payment-intent.php] Stripe SDK not installed');
    http_response_code(500);
    echo json_encode(['error' => 'Stripe SDK not installed']);
    exit;
}

require_once $autoloadPath;

/* ── Read and validate input ───────────────────────────────── */
$rawBody = file_get_contents('php://input');

if ($rawBody === false || $rawBody === '') {
    http_response_code(400);
    echo json_encode(['error' => 'Empty request body']);
    exit;
}

$input = json_decode($rawBody, true);

if (!is_array($input)) {
    http_response_code(400);
    echo json_encode(['error' => 'Invalid JSON']);
    exit;
}

/* Amount must be a positive integer (cents). Stripe minimum is 50 cents for EUR. */
$amount = isset($input['amount']) ? $input['amount'] : null;

if (!is_int($amount) && !is_float($amount)) {
    /* Try parsing as string */
    if (is_string($amount) && ctype_digit($amount)) {
        $amount = (int) $amount;
    } else {
        http_response_code(400);
        echo json_encode(['error' => 'Amount must be a positive integer (cents)']);
        exit;
    }
}

$amount = (int) $amount;

if ($amount < 50) {
    http_response_code(400);
    echo json_encode(['error' => 'Amount must be at least 50 cents']);
    exit;
}

if ($amount > 99999999) {
    http_response_code(400);
    echo json_encode(['error' => 'Amount exceeds maximum']);
    exit;
}

/* ── Create PaymentIntent ──────────────────────────────────── */
try {
    \Stripe\Stripe::setApiKey(STRIPE_SECRET_KEY);

    $paymentIntent = \Stripe\PaymentIntent::create([
        'amount'               => $amount,
        'currency'             => 'eur',
        'payment_method_types' => ['card_present'],
        'capture_method'       => 'automatic',
        'description'          => 'Area Camper Bergamo — Kiosk checkout',
    ]);

    echo json_encode([
        'id'            => $paymentIntent->id,
        'client_secret' => $paymentIntent->client_secret,
    ]);

} catch (\Stripe\Exception\ApiErrorException $e) {
    error_log('[create-payment-intent.php] Stripe API error: ' . $e->getMessage());
    http_response_code(500);
    echo json_encode(['error' => 'Failed to create payment intent']);
    exit;
} catch (\Exception $e) {
    error_log('[create-payment-intent.php] Unexpected error: ' . $e->getMessage());
    http_response_code(500);
    echo json_encode(['error' => 'Internal server error']);
    exit;
}
