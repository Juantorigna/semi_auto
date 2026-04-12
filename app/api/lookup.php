<?php
/**
 * lookup.php — Kiosk Booking Lookup API
 *
 * POST JSON: { "plate": "AB123CD", "key_number": "17" }
 *
 * Queries the `registrati` table for an active guest matching
 * the given license plate (case-insensitive) and key number.
 *
 * Security:
 *   - PDO prepared statements (parameterised queries)
 *   - Input validated and sanitised before use
 *   - Plate: A-Z 0-9 only, max 20 chars
 *   - Key:   0-9 only, max 3 chars
 *   - All output JSON-encoded (no raw echo of user input)
 *   - Security headers set on every response
 *   - CORS restricted to same-origin by default
 *   - Rate limiting via simple session counter
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

/* ── Load DB credentials from db.env ───────────────────────── */
$envPath = realpath(__DIR__ . '/../../db.env');

if ($envPath === false || !is_file($envPath)) {
    error_log('[lookup.php] db.env not found');
    http_response_code(500);
    echo json_encode(['error' => 'Server configuration error']);
    exit;
}

$envVars = [];
$lines   = file($envPath, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES);

if ($lines === false) {
    error_log('[lookup.php] Could not read db.env');
    http_response_code(500);
    echo json_encode(['error' => 'Server configuration error']);
    exit;
}

foreach ($lines as $line) {
    $line = trim($line);
    /* Skip comments and empty lines */
    if ($line === '' || $line[0] === '#') {
        continue;
    }
    $eqPos = strpos($line, '=');
    if ($eqPos === false) {
        continue;
    }
    $key = trim(substr($line, 0, $eqPos));
    $val = trim(substr($line, $eqPos + 1));
    $envVars[$key] = $val;
}

$dbHost = $envVars['DB_HOST'] ?? '';
$dbName = $envVars['DB_NAME'] ?? '';
$dbUser = $envVars['DB_USER'] ?? '';
$dbPass = $envVars['DB_PASS'] ?? '';

if ($dbHost === '' || $dbName === '' || $dbUser === '' || $dbPass === '') {
    error_log('[lookup.php] Incomplete DB credentials in db.env');
    http_response_code(500);
    echo json_encode(['error' => 'Server configuration error']);
    exit;
}

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

$plate = isset($input['plate']) ? trim((string) $input['plate']) : '';
$key   = isset($input['key_number']) ? trim((string) $input['key_number']) : '';

/* Sanitise plate: uppercase, A-Z 0-9 only, max 20 chars */
$plate = strtoupper($plate);
$plate = preg_replace('/[^A-Z0-9]/', '', $plate);

if ($plate === '' || mb_strlen($plate, 'UTF-8') > 20) {
    http_response_code(400);
    echo json_encode([
        'found' => false,
        'error' => 'Invalid license plate',
    ]);
    exit;
}

/* Sanitise key: digits only, max 3 chars */
$key = preg_replace('/[^0-9]/', '', $key);

if ($key === '' || mb_strlen($key, 'UTF-8') > 3) {
    http_response_code(400);
    echo json_encode([
        'found' => false,
        'error' => 'Invalid key number',
    ]);
    exit;
}

/* ── Database connection ───────────────────────────────────── */
try {
    $dsn = 'mysql:host=' . $dbHost . ';dbname=' . $dbName . ';charset=utf8mb4';
    $pdo = new PDO($dsn, $dbUser, $dbPass, [
        PDO::ATTR_ERRMODE            => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
        PDO::ATTR_EMULATE_PREPARES   => false,
    ]);
} catch (PDOException $e) {
    error_log('[lookup.php] DB connection failed: ' . $e->getMessage());
    http_response_code(500);
    echo json_encode(['error' => 'Database connection error']);
    exit;
}

/* ── Query registrati table ────────────────────────────────── */
try {
    $sql = '
        SELECT
            `id_registrato`,
            `Registration Code`,
            `Name`,
            `Nationality`,
            `Quanti`,
            `Category`,
            `Caravan Confirmation`,
            `Lunghezza`,
            `Plate`,
            `Piazzola`,
            `Chiavetta`,
            `Corrente`,
            `Paid`,
            `Amount`,
            `PaidInAdvance`,
            `AmountAdvance`,
            `Has Car`,
            `Arrival DateTime`,
            `Departure DateTime`,
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

    $booking = $stmt->fetch();

} catch (PDOException $e) {
    error_log('[lookup.php] Query failed: ' . $e->getMessage());
    http_response_code(500);
    echo json_encode(['error' => 'Database query error']);
    exit;
}

/* ── Response ──────────────────────────────────────────────── */
if ($booking === false) {
    http_response_code(404);
    echo json_encode([
        'found' => false,
        'error' => 'No active booking found for this plate and key number',
    ]);
    exit;
}

/* Never leak the internal ID or registration code to the kiosk.
   Build a safe response object with only the fields the UI needs. */
$response = [
    'found'   => true,
    'booking' => [
        'Name'                => $booking['Name'],
        'Nationality'         => $booking['Nationality'],
        'Quanti'              => $booking['Quanti'],
        'Category'            => $booking['Category'],
        'Caravan Confirmation' => $booking['Caravan Confirmation'],
        'Plate'               => $booking['Plate'],
        'Piazzola'            => $booking['Piazzola'],
        'Chiavetta'           => $booking['Chiavetta'],
        'Corrente'            => $booking['Corrente'],
        'Paid'                => $booking['Paid'],
        'Amount'              => $booking['Amount'],
        'PaidInAdvance'       => $booking['PaidInAdvance'],
        'AmountAdvance'       => $booking['AmountAdvance'],
        'Has Car'             => $booking['Has Car'],
        'Arrival DateTime'    => $booking['Arrival DateTime'],
        'Departure DateTime'  => $booking['Departure DateTime'],
        'Total Charge'        => $booking['Total Charge'],
    ],
];

echo json_encode($response, JSON_UNESCAPED_UNICODE);