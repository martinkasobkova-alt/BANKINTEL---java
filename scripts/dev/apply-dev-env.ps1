param(
    [string]$ReferenceRoot = "",
    [switch]$UseReferenceData
)

$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$backendRoot = Join-Path $root "backend-java"
$dest = Join-Path $backendRoot ".env"

if (-not $ReferenceRoot) {
    $ReferenceRoot = Join-Path (Split-Path -Parent $root) "Bankoapp-main"
}
$referenceRootPath = Resolve-Path $ReferenceRoot -ErrorAction SilentlyContinue
if (-not $referenceRootPath) {
    Write-Host "Reference root not found: $ReferenceRoot" -ForegroundColor Red
    exit 1
}

$src = Join-Path $referenceRootPath "dev.local.env"
if (-not (Test-Path $src)) {
    Write-Host "Missing $src" -ForegroundColor Red
    exit 1
}

function Read-EnvFile($path) {
    $map = @{}
    if (-not (Test-Path $path)) { return $map }
    Get-Content $path -Encoding UTF8 | ForEach-Object {
        $line = $_.Trim()
        if (-not $line -or $line.StartsWith("#")) { return }
        $i = $line.IndexOf("=")
        if ($i -lt 1) { return }
        $key = $line.Substring(0, $i).Trim()
        $value = $line.Substring($i + 1).Trim().Trim('"').Trim("'")
        $map[$key] = $value
    }
    return $map
}

function Normalize-PathString($path) {
    return ([string]$path).Replace("\", "/")
}

$dev = Read-EnvFile $src
$existing = Read-EnvFile $dest

$secretKeys = @(
    "OPENAI_API_KEY", "OPENAI_MODEL", "OPENAI_MODEL_CHAT", "OPENAI_MODEL_PLANNER", "OPENAI_COMMENTARY",
    "ARAD_API_KEY", "FRED_API_KEY", "IMF_API_KEY", "IMF_API_KEY_SECONDARY", "IMF_SUBSCRIPTION_KEY",
    "GIE_API_KEY", "ALPHAVANTAGE_API_KEY", "TRADING_ECONOMICS_API_KEY", "TRADINGECONOMICS_API_KEY",
    "RESEND_API_KEY", "SENDER_EMAIL",
    "JWT_SECRET", "ENCRYPTION_KEY",
    "CLASSIC_SEARCH_USE_LOCAL_INDEX", "CLASSIC_SEARCH_ALLOW_LIVE_FALLBACK",
    "BANKO_HOME_RENDER_AI"
)

foreach ($key in $secretKeys) {
    if ($dev.ContainsKey($key) -and $dev[$key]) {
        $existing[$key] = $dev[$key]
    }
}

$localDataDir = Join-Path $backendRoot "data"
$localIndexDir = Join-Path $localDataDir "catalog_search_indexes"
$localMetadataDir = Join-Path $localDataDir "catalog_search_metadata"
$localFtsDb = Join-Path $localIndexDir "classic_catalog_search.sqlite"

if ($UseReferenceData) {
    $referenceDataDir = Join-Path $referenceRootPath "backend\data"
    $referenceIndexDir = Join-Path $referenceDataDir "catalog_search_indexes"
    $referenceMetadataDir = Join-Path $referenceRootPath "backend\config\catalog_search_metadata"
    $referenceFtsDb = Join-Path $referenceIndexDir "classic_catalog_search.sqlite"
    $existing["BANKINTEL_REFERENCE_ROOT"] = Normalize-PathString $referenceRootPath
    $existing["BANKINTEL_DATA_DIR"] = Normalize-PathString $referenceDataDir
    $existing["CATALOG_SEARCH_INDEX_DIR"] = Normalize-PathString $referenceIndexDir
    $existing["CATALOG_SEARCH_METADATA_DIR"] = Normalize-PathString $referenceMetadataDir
    $existing["CLASSIC_CATALOG_FTS_DB"] = Normalize-PathString $referenceFtsDb
} else {
    $existing["BANKINTEL_DATA_DIR"] = Normalize-PathString $localDataDir
    $existing["CATALOG_SEARCH_INDEX_DIR"] = Normalize-PathString $localIndexDir
    $existing["CATALOG_SEARCH_METADATA_DIR"] = Normalize-PathString $localMetadataDir
    $existing["CLASSIC_CATALOG_FTS_DB"] = Normalize-PathString $localFtsDb
    [void]$existing.Remove("BANKINTEL_REFERENCE_ROOT")
}

$existing["FRONTEND_URL"] = "http://localhost:5173"
$existing["CORS_ORIGINS"] = "http://localhost:5173,http://localhost:3000"
$existing["COOKIE_SECURE"] = "false"
$existing["COOKIE_SAME_SITE"] = "Lax"
$existing["DEV_SEED"] = "true"

$header = if ($UseReferenceData) {
    "# Auto from reference dev.local.env; data paths use read-only reference data"
} else {
    "# Auto from reference dev.local.env; data paths stay inside BankIntel-v2"
}
$lines = @($header)
foreach ($key in ($existing.Keys | Sort-Object)) {
    $lines += "$key=$($existing[$key])"
}
Set-Content -Path $dest -Value $lines -Encoding UTF8

Write-Host "OK $dest" -ForegroundColor Green
Write-Host "Data dir: $($existing['BANKINTEL_DATA_DIR'])" -ForegroundColor Cyan
Write-Host "Index:    $($existing['CATALOG_SEARCH_INDEX_DIR'])" -ForegroundColor Cyan
Write-Host "FTS DB:   $($existing['CLASSIC_CATALOG_FTS_DB'])" -ForegroundColor Cyan
