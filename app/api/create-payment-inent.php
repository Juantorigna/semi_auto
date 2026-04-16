<?php
/**
 * create-payment-intent.php — Stripe PaymentIntent Creator
 *
 * POST JSON: { "plate": "AB123CD", "key_number": "17" }
 *
 * Looks up the active registration in `registrati`, computes the balance
 * server-side (total stay charge minus amounts already paid), then creates
 * a Stripe PaymentIntent for the remaining balance.
 *
 * The amount is NEVER accepted from the client — always derived from the DB.
 *
 * Returns: {
 *   "client_secret":     "pi_xxx_secret_xxx",
 *   "payment_intent_id": "pi_xxx",
 *   "amount_cents":      1500,
 *   "currency":          "eur",
 *   "registration_ref":  "REG-2025-00042"
 * }
 *
 * Security:
 *   - PDO prepared statements (parameterised queries)
 *   - Input validated and sanitised
 *   - Amount computed server-side only
 *   - Stripe secret key from env file outside web root
 *   - Security headers on every response
 *   - CORS restricted to same origin
 */

declare(strict_types=1);

/* ── Security headers ──────────────────────────────────────── */
header('Content-Type: application/json; charset=utf-8');
header('X-Content-Type-Options: nosniff');
header('X-Frame-Options: DENY');
header('Cache-Control: no-store, no-cache, must-revalidate');
header('Pragma: no-cache');
header('Referrer-Policy: no-referrer');

/* ── CORS — restrict to same origin ────────────────────────── */
$allowedOrigin = 'https://areacamperbergamo.it';
$requestOrigin = $_SERVER['HTTP_ORIGIN'] ?? '';

if ($requestOrigin === $allowedOrigin) {
    header('Access-Control-Allow-Origin: ' . $allowedOrigin);
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

/* ══════════════════════════════════════════════════════════════
   LOAD ENV FILES
   ══════════════════════════════════════════════════════════════ */

/**
 * Parses a simple KEY=VALUE env file.
 * Skips comments (#) and blank lines.
 *
 * @param  string $path  Absolute path to the env file
 * @param  string $label Label for error logging
 * @return array<string,string>
 */
function loadEnvFile(string $path, string $label): array
{
    $resolved = realpath($path);

    if ($resolved === false || !is_file($resolved)) {
        error_log("[create-payment-intent.php] $label not found at: $path");
        http_response_code(500);
        echo json_encode(['error' => 'Server configuration error']);
        exit;
    }

    $lines = file($resolved, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES);

    if ($lines === false) {
        error_log("[create-payment-intent.php] Could not read $label");
        http_response_code(500);
        echo json_encode(['error' => 'Server configuration error']);
        exit;
    }

    $vars = [];

    foreach ($lines as $line) {
        $line = trim($line);

        if ($line === '' || $line[0] === '#') {
            continue;
        }

        $eqPos = strpos($line, '=');

        if ($eqPos === false) {
            continue;
        }

        $key = trim(substr($line, 0, $eqPos));
        $val = trim(substr($line, $eqPos + 1));
        $vars[$key] = $val;
    }

    return $vars;
}

/* ── DB credentials ── */
$dbEnv  = loadEnvFile(__DIR__ . '/../../../db.env', 'db.env');
$dbHost = $dbEnv['DB_HOST'] ?? '';
$dbName = $dbEnv['DB_NAME'] ?? '';
$dbUser = $dbEnv['DB_USER'] ?? '';
$dbPass = $dbEnv['DB_PASS'] ?? '';

if ($dbHost === '' || $dbName === '' || $dbUser === '' || $dbPass === '') {
    error_log('[create-payment-intent.php] Incomplete DB credentials in db.env');
    http_response_code(500);
    echo json_encode(['error' => 'Server configuration error']);
    exit;
}

/* ── Stripe credentials ── */
$stripeEnv       = loadEnvFile(__DIR__ . '/../../../stripe.env', 'stripe.env');
$stripeSecretKey = $stripeEnv['STRIPE_SECRET_KEY'] ?? '';

if ($stripeSecretKey === '') {
    error_log('[create-payment-intent.php] STRIPE_SECRET_KEY missing from stripe.env');
    http_response_code(500);
    echo json_encode(['error' => 'Server configuration error']);
    exit;
}

/* Validate key prefix */
if (
    strncmp($stripeSecretKey, 'sk_live_', 8) !== 0 &&
    strncmp($stripeSecretKey, 'sk_test_', 8) !== 0
) {
    error_log('[create-payment-intent.php] STRIPE_SECRET_KEY has unexpected prefix');
    http_response_code(500);
    echo json_encode(['error' => 'Server configuration error']);
    exit;
}

/* ══════════════════════════════════════════════════════════════
   READ AND VALIDATE INPUT
   ══════════════════════════════════════════════════════════════ */

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

$plate = isset($input['plate']) ? trim((string) $input['plate']) : '';
$key   = isset($input['key_number']) ? trim((string) $input['key_number']) : '';

/* Sanitise plate: uppercase, A-Z 0-9 only, max 20 chars */
$plate = strtoupper($plate);
$plate = preg_replace('/[^A-Z0-9]/', '', $plate);

if ($plate === '' || mb_strlen($plate, 'UTF-8') > 20) {
    http_response_code(400);
    echo json_encode(['error' => 'Invalid license plate']);
    exit;
}

/* Sanitise key: digits only, max 3 chars */
$key = preg_replace('/[^0-9]/', '', $key);

if ($key === '' || mb_strlen($key, 'UTF-8') > 3) {
    http_response_code(400);
    echo json_encode(['error' => 'Invalid key number']);
    exit;
}

/* ══════════════════════════════════════════════════════════════
   DATABASE — look up registration and compute balance
   ══════════════════════════════════════════════════════════════ */

try {
    $dsn = 'mysql:host=' . $dbHost . ';dbname=' . $dbName . ';charset=utf8mb4';
    $pdo = new PDO($dsn, $dbUser, $dbPass, [
        PDO::ATTR_ERRMODE            => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
        PDO::ATTR_EMULATE_PREPARES   => false,
    ]);
} catch (PDOException $e) {
    error_log('[create-payment-intent.php] DB connection failed: ' . $e->getMessage());
    http_response_code(500);
    echo json_encode(['error' => 'Database connection error']);
    exit;
}

try {
    $sql = '
        SELECT
            `Registration Code`,
            `Name`,
            `Corrente`,
            `Amount`,
            `AmountAdvance`,
            `Has Car`,
            `Arrival DateTime`,
            `Total Charge`
        FROM `registrati`
        WHERE UPPER(`Plate`) = :plate
          AND `Chiavetta`    = :key
          AND `Active`       = :active
        LIMIT 1
    ';

    $stmt = $pdo->prepare($sql);
    $stmt->execute([
        ':plate'  => $plate,
        ':key'    => $key,
        ':active' => 'Y',
    ]);

    $registration = $stmt->fetch();

} catch (PDOException $e) {
    error_log('[create-payment-intent.php] Query failed: ' . $e->getMessage());
    http_response_code(500);
    echo json_encode(['error' => 'Database query error']);
    exit;
}

if ($registration === false) {
    http_response_code(404);
    echo json_encode(['error' => 'No active registration found']);
    exit;
}

/* ══════════════════════════════════════════════════════════════
   BILLING CALCULATION — server-side (mirrors kiosk JS logic)

   Rates per 24 h cycle (repeating 9-day pattern):
     Days 1–3: €18/day   (hourly overage: €3/h, cap at €18 after 6 h)
     Days 4–9: €15/day   (hourly overage: €3/h, cap at €15 after 5 h)
   Electricity: 4 A = free, 7 A = €6/day, 10 A = €10/day
   Extra car/trailer: €5/day
   ══════════════════════════════════════════════════════════════ */

/**
 * Compute wall-clock hours between two DateTimes,
 * compensating for DST shifts (Europe/Rome).
 */
function getWallClockHours(DateTimeInterface $start, DateTimeInterface $end): float
{
    $elapsed  = ($end->getTimestamp() - $start->getTimestamp()) / 3600.0;
    $dstShift = ($start->getOffset() - $end->getOffset()) / 3600.0;
    return $elapsed + $dstShift;
}

function calculateDailyRate(float $hoursElapsed): float
{
    $cycleDay = (int) floor($hoursElapsed / 24) % 9;
    return $cycleDay < 3 ? 18.0 : 15.0;
}

function calculateAdditionalHoursRate(float $hoursElapsed, float $additionalHours): float
{
    $cycleDay               = (int) floor($hoursElapsed / 24) % 9;
    $ratePerHour            = 3.0;
    $maxHoursBeforeFullRate = $cycleDay < 3 ? 6 : 5;
    $fullDayRate            = $cycleDay < 3 ? 18.0 : 15.0;

    if ($additionalHours <= $maxHoursBeforeFullRate) {
        return $additionalHours * $ratePerHour;
    }

    return $fullDayRate;
}

function calculateAmpersCharge(float $corrente): float
{
    switch ((int) $corrente) {
        case 4:  return 0.0;
        case 7:  return 6.0;
        case 10: return 10.0;
        default: return 0.0;
    }
}

function calculateTotalStayCharge(
    DateTimeInterface $arrival,
    DateTimeInterface $departure,
    bool $hasCar,
    float $corrente
): float {
    $totalHours     = (int) ceil(getWallClockHours($arrival, $departure));
    $totalCharge    = 0.0;
    $hoursRemaining = $totalHours;
    $carCharge      = 0.0;
    $ampersCharge   = 0.0;

    while ($hoursRemaining > 0) {
        if ($hoursRemaining >= 24) {
            $totalCharge    += calculateDailyRate((float) ($totalHours - $hoursRemaining));
            $hoursRemaining -= 24;
            $ampersCharge   += calculateAmpersCharge($corrente);
            if ($hasCar) {
                $carCharge += 5.0;
            }
        } else {
            $totalCharge  += calculateAdditionalHoursRate(
                (float) ($totalHours - $hoursRemaining),
                (float) $hoursRemaining
            );
            $ampersCharge += calculateAmpersCharge($corrente);
            if ($hasCar) {
                $carCharge += min(5.0, (float) $hoursRemaining);
            }
            break;
        }
    }

    return $totalCharge + $carCharge + $ampersCharge;
}

/* ── Compute balance ── */

$arrivalStr = $registration['Arrival DateTime'] ?? '';
$rome       = new DateTimeZone('Europe/Rome');

try {
    $arrivalDt   = new DateTimeImmutable($arrivalStr, $rome);
    $departureDt = new DateTimeImmutable('now', $rome);
} catch (Exception $e) {
    error_log('[create-payment-intent.php] Date parse error: ' . $e->getMessage());
    http_response_code(500);
    echo json_encode(['error' => 'Invalid arrival date in registration']);
    exit;
}

$corrente = (float) ($registration['Corrente'] ?? 0);
$hasCar   = in_array(
    strtolower(trim((string) ($registration['Has Car'] ?? ''))),
    ['yes', 'si', 'sì', 'y', '1'],
    true
);

$totalCharge = calculateTotalStayCharge($arrivalDt, $departureDt, $hasCar, $corrente);
$paidAmount  = (float) ($registration['Amount'] ?? 0);
$paidAdvance = (float) ($registration['AmountAdvance'] ?? 0);
$alreadyPaid = $paidAmount + $paidAdvance;
$balance     = $totalCharge - $alreadyPaid;

/* If nothing owed, no PaymentIntent needed */
if ($balance <= 0) {
    echo json_encode([
        'amount_cents'      => 0,
        'currency'          => 'eur',
        'registration_ref'  => $registration['Registration Code'],
        'balance_zero'      => true,
    ]);
    exit;
}

/* Stripe requires integer cents, minimum 50 cents for EUR */
$amountCents = (int) round($balance * 100);

if ($amountCents < 50) {
    $amountCents = 50;
}

/* ══════════════════════════════════════════════════════════════
   STRIPE — create PaymentIntent
   ══════════════════════════════════════════════════════════════ */

$autoloadPath = realpath(__DIR__ . '/../../../vendor/autoload.php');

if ($autoloadPath === false || !is_file($autoloadPath)) {
    error_log('[create-payment-intent.php] Composer autoload.php not found');
    http_response_code(500);
    echo json_encode(['error' => 'Server configuration error']);
    exit;
}

require_once $autoloadPath;

try {
    $stripe = new \Stripe\StripeClient($stripeSecretKey);

    $paymentIntent = $stripe->paymentIntents->create([
        'amount'               => $amountCents,
        'currency'             => 'eur',
        'payment_method_types' => ['card_present'],
        'capture_method'       => 'automatic',
        'description'          => 'Campsite checkout — ' . ($registration['Name'] ?? 'Guest'),
        'metadata'             => [
            'registration_ref' => $registration['Registration Code'],
            'plate'            => $plate,
            'key_number'       => $key,
            'source'           => 'kiosk_self_checkout',
        ],
    ]);

    echo json_encode([
        'client_secret'     => $paymentIntent->client_secret,
        'payment_intent_id' => $paymentIntent->id,
        'amount_cents'      => $amountCents,
        'currency'          => 'eur',
        'registration_ref'  => $registration['Registration Code'],
    ]);

} catch (\Stripe\Exception\AuthenticationException $e) {
    error_log('[create-payment-intent.php] Stripe auth failed: ' . $e->getMessage());
    http_response_code(500);
    echo json_encode(['error' => 'Payment provider authentication error']);
    exit;
} catch (\Stripe\Exception\InvalidRequestException $e) {
    error_log('[create-payment-intent.php] Stripe invalid request: ' . $e->getMessage());
    http_response_code(500);
    echo json_encode(['error' => 'Payment configuration error']);
    exit;
} catch (\Stripe\Exception\ApiConnectionException $e) {
    error_log('[create-payment-intent.php] Stripe unreachable: ' . $e->getMessage());
    http_response_code(503);
    echo json_encode(['error' => 'Payment provider temporarily unavailable']);
    exit;
} catch (\Stripe\Exception\ApiErrorException $e) {
    error_log('[create-payment-intent.php] Stripe API error: ' . $e->getMessage());
    http_response_code(500);
    echo json_encode(['error' => 'Payment provider error']);
    exit;
} catch (Exception $e) {
    error_log('[create-payment-intent.php] Unexpected error: ' . $e->getMessage());
    http_response_code(500);
    echo json_encode(['error' => 'Internal server error']);
    exit;
}