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

# --- Port resolution -------------------------------------------------------
# The Vite dev proxy targets REACT_APP_PROXY_TARGET (frontend\.env.local). If the
# backend starts on a different port, the frontend comes up but never reaches the
# API. Read the proxy target and start the backend on exactly that port.
$frontendEnvLocal = Join-Path $frontendRoot ".env.local"
$frontendCfg = Read-EnvFile $frontendEnvLocal
$proxyTarget = $frontendCfg["REACT_APP_PROXY_TARGET"]
if (-not $proxyTarget) { $proxyTarget = "http://127.0.0.1:8080" }

$backendPort = 8080
try {
    $parsedTarget = [System.Uri]$proxyTarget
    if ($parsedTarget.Port -gt 0) { $backendPort = $parsedTarget.Port }
} catch {
    Write-Host "Could not parse REACT_APP_PROXY_TARGET ('$proxyTarget'), falling back to :8080" -ForegroundColor Yellow
    $proxyTarget = "http://127.0.0.1:8080"
}

$env:PORT = "$backendPort"
# Keep Vite on the same target even if it is launched without reading .env.local.
$env:REACT_APP_PROXY_TARGET = $proxyTarget

$portSourceLabel = "default"
if ($frontendCfg.ContainsKey("REACT_APP_PROXY_TARGET")) { $portSourceLabel = "frontend\.env.local" }
Write-Host "Backend port: $backendPort (from $portSourceLabel)" -ForegroundColor Cyan

$portBusy = $null
try {
    $portBusy = Get-NetTCPConnection -LocalPort $backendPort -State Listen -ErrorAction Stop | Select-Object -First 1
} catch {
    $portBusy = $null
}
if ($portBusy) {
    $ownerPid = $portBusy.OwningProcess
    $ownerName = "<unknown>"
    try { $ownerName = (Get-Process -Id $ownerPid -ErrorAction Stop).ProcessName } catch { }
    Write-Host ""
    Write-Host "ERROR: port $backendPort is already in use by PID $ownerPid ($ownerName)." -ForegroundColor Red
    Write-Host "       The backend cannot start there and the frontend proxy would hang." -ForegroundColor Red
    Write-Host "       Stop that process, or point frontend\.env.local REACT_APP_PROXY_TARGET at a free port." -ForegroundColor Yellow
    Write-Host ""
    exit 1
}

Write-Host "Starting backend on :$backendPort ..." -ForegroundColor Cyan
Start-Process powershell -ArgumentList "-NoExit", "-Command", "`$env:PORT='$backendPort'; cd '$backendRoot'; .\gradlew.bat bootRun"

# Wait for the backend to answer before starting Vite. Starting the proxy against a
# dead upstream leaves sockets hanging and the SPA never finishes loading.
$healthUrl = "http://127.0.0.1:$backendPort/health"
Write-Host "Waiting for backend at $healthUrl ..." -ForegroundColor Cyan
$backendUp = $false
for ($i = 0; $i -lt 90; $i++) {
    Start-Sleep -Seconds 2
    try {
        $resp = Invoke-WebRequest -Uri $healthUrl -TimeoutSec 3 -UseBasicParsing -ErrorAction Stop
        if ($resp.StatusCode -eq 200) { $backendUp = $true; break }
    } catch { }
}
if ($backendUp) {
    Write-Host "Backend is up." -ForegroundColor Green
} else {
    Write-Host "Backend did not answer within 180 s - starting the frontend anyway." -ForegroundColor Yellow
    Write-Host "Check the backend window; API calls will fail until it is up." -ForegroundColor Yellow
}

Write-Host "Starting frontend on :5173 ..." -ForegroundColor Cyan
Start-Process powershell -ArgumentList "-NoExit", "-Command", "`$env:REACT_APP_PROXY_TARGET='$proxyTarget'; cd '$frontendRoot'; npm run dev"

Write-Host ""
Write-Host "Open:   http://localhost:5173" -ForegroundColor Green
Write-Host "Health: $healthUrl" -ForegroundColor Green
Write-Host "Login:  admin@bankintel.local / admin123" -ForegroundColor Green
Write-Host ""
Write-Host "Docs: docs\CODE_MAP.md and docs\APP_MAP.md" -ForegroundColor Cyan
