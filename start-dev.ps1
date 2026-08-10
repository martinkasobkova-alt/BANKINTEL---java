param(
    [switch]$LocalOnly
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$backendRoot = Join-Path $root "backend-java"
$frontendRoot = Join-Path $root "frontend"

Write-Host "=== BankIntel v2 dev start ===" -ForegroundColor Cyan

$envFile = Join-Path $backendRoot ".env"
if (-not (Test-Path $envFile)) {
    Copy-Item (Join-Path $backendRoot ".env.example") $envFile
    Write-Host "Created backend-java\.env from .env.example" -ForegroundColor Yellow
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

function Resolve-ConfiguredPath($path, $baseDir) {
    if (-not $path) { return "" }
    if ([System.IO.Path]::IsPathRooted($path)) { return $path }
    return Join-Path $baseDir $path
}

$cfg = Read-EnvFile $envFile
$localDataDir = Join-Path $backendRoot "data"
$localIndexDir = Join-Path $localDataDir "catalog_search_indexes"
$localMetadataDir = Join-Path $localDataDir "catalog_search_metadata"
$localFtsDb = Join-Path $localIndexDir "classic_catalog_search.sqlite"

$referenceRoot = if ($env:BANKINTEL_REFERENCE_ROOT) {
    $env:BANKINTEL_REFERENCE_ROOT
} else {
    Join-Path (Split-Path -Parent $root) "Bankoapp-main"
}
$referenceDataDir = Join-Path $referenceRoot "backend\data"
$referenceIndexDir = Join-Path $referenceDataDir "catalog_search_indexes"
$referenceMetadataDir = Join-Path $referenceRoot "backend\config\catalog_search_metadata"
$referenceFtsDb = Join-Path $referenceIndexDir "classic_catalog_search.sqlite"

$selectedDataDir = Resolve-ConfiguredPath $cfg["BANKINTEL_DATA_DIR"] $backendRoot
$selectedIndexDir = Resolve-ConfiguredPath $cfg["CATALOG_SEARCH_INDEX_DIR"] $backendRoot
$selectedMetadataDir = Resolve-ConfiguredPath $cfg["CATALOG_SEARCH_METADATA_DIR"] $backendRoot
$selectedFtsDb = Resolve-ConfiguredPath $cfg["CLASSIC_CATALOG_FTS_DB"] $backendRoot
$dataSourceLabel = "backend-java/.env"

if (-not $selectedIndexDir) {
    if (Test-Path $localFtsDb) {
        $selectedDataDir = $localDataDir
        $selectedIndexDir = $localIndexDir
        $selectedMetadataDir = $localMetadataDir
        $selectedFtsDb = $localFtsDb
        $dataSourceLabel = "local BankIntel-v2 data"
    } elseif ((-not $LocalOnly) -and (Test-Path $referenceFtsDb)) {
        $selectedDataDir = $referenceDataDir
        $selectedIndexDir = $referenceIndexDir
        $selectedMetadataDir = $referenceMetadataDir
        $selectedFtsDb = $referenceFtsDb
        $dataSourceLabel = "read-only reference data"
    } else {
        $selectedDataDir = $localDataDir
        $selectedIndexDir = $localIndexDir
        $selectedMetadataDir = $localMetadataDir
        $selectedFtsDb = $localFtsDb
        $dataSourceLabel = "local empty data dirs"
    }
}

if (-not $selectedDataDir) { $selectedDataDir = $localDataDir }
if (-not $selectedMetadataDir) { $selectedMetadataDir = $localMetadataDir }
if (-not $selectedFtsDb) { $selectedFtsDb = Join-Path $selectedIndexDir "classic_catalog_search.sqlite" }

if ((-not $LocalOnly) -and (-not (Test-Path $selectedFtsDb)) -and (Test-Path $referenceFtsDb)) {
    $selectedDataDir = $referenceDataDir
    $selectedIndexDir = $referenceIndexDir
    $selectedMetadataDir = $referenceMetadataDir
    $selectedFtsDb = $referenceFtsDb
    $dataSourceLabel = "read-only reference data"
}

$env:BANKINTEL_DATA_DIR = Normalize-PathString $selectedDataDir
$env:CATALOG_SEARCH_INDEX_DIR = Normalize-PathString $selectedIndexDir
$env:CATALOG_SEARCH_METADATA_DIR = Normalize-PathString $selectedMetadataDir
$env:CLASSIC_CATALOG_FTS_DB = Normalize-PathString $selectedFtsDb
$env:SEARCH_ENGINE_VERSION = if ($cfg["SEARCH_ENGINE_VERSION"]) { $cfg["SEARCH_ENGINE_VERSION"] } else { "v2" }
$env:SEARCH_CATALOG_INDEX = if ($cfg["SEARCH_CATALOG_INDEX"]) { $cfg["SEARCH_CATALOG_INDEX"] } else { "sidecar" }
$env:SEARCH_SEMANTIC_RETRIEVAL_ENABLED = if ($cfg["SEARCH_SEMANTIC_RETRIEVAL_ENABLED"]) { $cfg["SEARCH_SEMANTIC_RETRIEVAL_ENABLED"] } else { "false" }
if (Test-Path (Join-Path $referenceRoot "backend")) {
    $env:BANKINTEL_REFERENCE_ROOT = Normalize-PathString $referenceRoot
}

Write-Host "Data source: $dataSourceLabel" -ForegroundColor Green
Write-Host "Index dir:   $env:CATALOG_SEARCH_INDEX_DIR" -ForegroundColor Cyan
Write-Host "FTS DB:      $env:CLASSIC_CATALOG_FTS_DB" -ForegroundColor Cyan
if (-not (Test-Path $selectedFtsDb)) {
    Write-Host "Warning: FTS DB is missing. Catalog search will use slower or empty fallbacks." -ForegroundColor Yellow
}

Write-Host "Using local profile with embedded PostgreSQL." -ForegroundColor Green
Write-Host "Starting backend on :8080 ..." -ForegroundColor Cyan
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$backendRoot'; .\gradlew.bat bootRun"

Start-Sleep -Seconds 12
Write-Host "Starting frontend on :5173 ..." -ForegroundColor Cyan
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$frontendRoot'; npm run dev"

Write-Host ""
Write-Host "Open:   http://localhost:5173" -ForegroundColor Green
Write-Host "Health: http://localhost:8080/health" -ForegroundColor Green
Write-Host "Login:  admin@bankintel.local / admin123" -ForegroundColor Green
Write-Host ""
Write-Host "Docs: docs\FUNCTIONALITY_MAP.md and docs\CODE_MAP.md" -ForegroundColor Cyan
