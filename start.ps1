<#
.SYNOPSIS
    Starts the full SIH project: Face Verification Service, Java Backend, React Frontend.

.DESCRIPTION
    Launches all three services in parallel, waits for each to be healthy,
    then keeps running until you press Ctrl+C.

    Services:
      1. Face Verification Service  (Python/FastAPI)  → http://localhost:5000
      2. Screening API              (Java/Spring Boot) → http://localhost:8080
      3. Officer Console            (React/Vite)       → http://localhost:5173

.PARAMETER SkipFaceService
    Skip the Python face verification service.

.PARAMETER SkipFrontend
    Skip the React frontend.

.PARAMETER EmbeddedMongo
    Use embedded MongoDB (no Docker required). Default: true.

.EXAMPLE
    .\start.ps1
    .\start.ps1 -SkipFaceService
#>

param(
    [switch]$SkipFaceService,
    [switch]$SkipFrontend,
    [switch]$EmbeddedMongo = $true
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

# ── Coloured output helpers ────────────────────────────────────────────────

function Write-Header($msg)  { Write-Host "`n═══════════════════════════════════════════════════" -ForegroundColor DarkCyan; Write-Host "  $msg" -ForegroundColor Cyan; Write-Host "═══════════════════════════════════════════════════" -ForegroundColor DarkCyan }
function Write-Step($msg)    { Write-Host "  → $msg" -ForegroundColor Yellow }
function Write-Ok($msg)      { Write-Host "  ✓ $msg" -ForegroundColor Green }
function Write-Warn($msg)    { Write-Host "  ⚠ $msg" -ForegroundColor DarkYellow }
function Write-Fail($msg)    { Write-Host "  ✗ $msg" -ForegroundColor Red }

# ── Track child processes for cleanup ──────────────────────────────────────

$script:childProcesses = @()

function Stop-AllServices {
    Write-Host "`n" -NoNewline
    Write-Header "Shutting down"
    foreach ($proc in $script:childProcesses) {
        if ($proc -and !$proc.HasExited) {
            Write-Step "Stopping $($proc.ProcessName) (PID $($proc.Id))..."
            try {
                Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
                $proc.WaitForExit(3000) | Out-Null
            } catch {}
        }
    }
    Write-Ok "All services stopped."
}

# Register cleanup on exit
Register-EngineEvent PowerShell.Exiting -Action { Stop-AllServices } | Out-Null

# ── Prerequisite checks ───────────────────────────────────────────────────

Write-Header "Checking prerequisites"

# Java
$java = Get-Command java -ErrorAction SilentlyContinue
if (-not $java) { Write-Fail "Java not found. Install JDK 21+ and add to PATH."; exit 1 }
$javaVer = (java -version 2>&1 | Select-Object -First 1) -replace '.*"(\d+).*','$1'
Write-Ok "Java $javaVer found"

# Node
$node = Get-Command node -ErrorAction SilentlyContinue
if (-not $node) { Write-Fail "Node.js not found. Install Node 20+ and add to PATH."; exit 1 }
$nodeVer = node --version
Write-Ok "Node $nodeVer found"

# Python (only if face service enabled)
if (-not $SkipFaceService) {
    $pythonExe = Join-Path $root "venv\Scripts\python.exe"
    if (-not (Test-Path $pythonExe)) {
        $pythonExe = "python"
    }
    $pyVer = & $pythonExe --version 2>&1
    Write-Ok "Python found: $pyVer"
}

Write-Host ""

# ── 1. Face Verification Service ──────────────────────────────────────────

$faceServiceProc = $null

if (-not $SkipFaceService) {
    Write-Header "Starting Face Verification Service (port 5000)"

    $faceDir = Join-Path $root "face-verification-service"
    $faceReqs = Join-Path $faceDir "requirements.txt"

    # Use project venv if available, else system python
    $pipExe = Join-Path $root "venv\Scripts\pip.exe"
    $pyExe  = Join-Path $root "venv\Scripts\python.exe"
    if (-not (Test-Path $pyExe)) {
        $pyExe = "python"
        $pipExe = "pip"
    }

    # Install dependencies if opencv not present
    $opencvCheck = & $pyExe -c "import cv2" 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Step "Installing Python dependencies (first run)..."
        & $pipExe install -r $faceReqs --quiet 2>&1 | Write-Host
        if ($LASTEXITCODE -ne 0) {
            Write-Fail "Failed to install Python dependencies."
            Write-Warn "Run manually: $pipExe install -r $faceReqs"
        } else {
            Write-Ok "Python dependencies installed"
        }
    } else {
        Write-Ok "Python dependencies already installed"
    }

    # The models are ~40MB and are not in version control, so a fresh clone has none.
    # Fetch them rather than merely warning: a face service that starts without a
    # recognition model looks healthy and answers every comparison with an error.
    $modelDir = Join-Path $faceDir "models"
    $yunetModel = Join-Path $modelDir "yunet.onnx"
    $sfaceModel = Join-Path $modelDir "face_recognizer_fast.onnx"
    if (-not (Test-Path $yunetModel) -or -not (Test-Path $sfaceModel)) {
        Write-Step "Face models not found - downloading (about 40MB, one time)..."
        Push-Location $faceDir
        & $pyExe "download_models.py"
        $downloadOk = $LASTEXITCODE -eq 0
        Pop-Location
        if ($downloadOk) {
            Write-Ok "Face models downloaded"
        } else {
            Write-Warn "Could not download the face models. Module 4 will report that it"
            Write-Warn "did not run. Fetch them by hand into $modelDir"
        }
    } else {
        Write-Ok "Face models found"
    }

    # Start the service
    Write-Step "Launching FastAPI server..."
    $faceServiceProc = Start-Process -FilePath $pyExe `
        -ArgumentList "-m", "uvicorn", "app:app", "--host", "0.0.0.0", "--port", "5000" `
        -WorkingDirectory $faceDir `
        -PassThru `
        -NoNewWindow:$false

    $script:childProcesses += $faceServiceProc
    Write-Ok "Face Verification Service started (PID $($faceServiceProc.Id))"

    # Wait for it to be ready
    $ready = $false
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Seconds 1
        try {
            $resp = Invoke-WebRequest -Uri "http://localhost:5000/health" -TimeoutSec 2 -ErrorAction Stop
            if ($resp.StatusCode -eq 200) { $ready = $true; break }
        } catch {}
    }
    if ($ready) {
        Write-Ok "Face service healthy at http://localhost:5000"
    } else {
        Write-Warn "Face service not yet responding — it may still be loading models"
    }
}

# ── 2. Java Backend ───────────────────────────────────────────────────────

Write-Header "Starting Screening API (port 8080)"

$backendDir = Join-Path $root "govtId-validation\backend"
$mvnw = Join-Path $backendDir "mvnw.cmd"

# Set face service URL for the Java backend
if (-not $SkipFaceService) {
    $env:FACE_SERVICE_URL = "http://localhost:5000"
    Write-Step "FACE_SERVICE_URL=http://localhost:5000"
}

$profileArg = if ($EmbeddedMongo) { "-Pembedded-mongo" } else { "" }

Write-Step "Launching Spring Boot (profile: $(if ($EmbeddedMongo) {'embedded-mongo'} else {'default'}))..."
$backendProc = Start-Process -FilePath $mvnw `
    -ArgumentList "spring-boot:run", $profileArg `
    -WorkingDirectory $backendDir `
    -PassThru `
    -NoNewWindow:$false

$script:childProcesses += $backendProc
Write-Ok "Backend started (PID $($backendProc.Id))"

# Wait for it to be ready
Write-Step "Waiting for API to be ready..."
$ready = $false
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Seconds 2
    try {
        $resp = Invoke-WebRequest -Uri "http://localhost:8080/actuator/health" -TimeoutSec 2 -ErrorAction Stop
        if ($resp.StatusCode -eq 200) { $ready = $true; break }
    } catch {}
    # Check if process died
    if ($backendProc.HasExited) {
        Write-Fail "Backend process exited unexpectedly."
        break
    }
}
if ($ready) {
    Write-Ok "Backend healthy at http://localhost:8080"
} else {
    Write-Warn "Backend not yet responding — check the output window"
}

# ── 3. React Frontend ─────────────────────────────────────────────────────

$frontendProc = $null

if (-not $SkipFrontend) {
    Write-Header "Starting Officer Console (port 5173)"

    $frontendDir = Join-Path $root "govtId-validation\frontend"

    # Ensure node_modules exist
    if (-not (Test-Path (Join-Path $frontendDir "node_modules"))) {
        Write-Step "Installing npm dependencies..."
        Push-Location $frontendDir
        npm install 2>&1 | Write-Host
        Pop-Location
        Write-Ok "npm dependencies installed"
    }

    Write-Step "Launching Vite dev server..."
    $frontendProc = Start-Process -FilePath "npm" `
        -ArgumentList "run", "dev" `
        -WorkingDirectory $frontendDir `
        -PassThru `
        -NoNewWindow:$false

    $script:childProcesses += $frontendProc
    Write-Ok "Frontend started (PID $($frontendProc.Id))"

    Start-Sleep -Seconds 3
    Write-Ok "Frontend at http://localhost:5173"
}

# ── Summary ───────────────────────────────────────────────────────────────

Write-Header "All services running"

Write-Host ""
Write-Host "  ┌─────────────────────────────────────────────────────────────┐" -ForegroundColor Cyan
Write-Host "  │                   SERVICE STATUS                           │" -ForegroundColor Cyan
Write-Host "  ├─────────────────────────────────────────────────────────────┤" -ForegroundColor Cyan

if (-not $SkipFaceService) {
    $faceStatus = if ($faceServiceProc -and !$faceServiceProc.HasExited) { "RUNNING" } else { "STOPPED" }
    $faceColor  = if ($faceStatus -eq "RUNNING") { "Green" } else { "Red" }
    Write-Host "  │  Face Verification   http://localhost:5000   " -NoNewline -ForegroundColor White
    Write-Host ("{0,-14}" -f $faceStatus) -ForegroundColor $faceColor
    Write-Host "  │                          YuNet + SFace (OpenCV)          │" -ForegroundColor DarkGray
}

$backStatus = if ($backendProc -and !$backendProc.HasExited) { "RUNNING" } else { "STOPPED" }
$backColor  = if ($backStatus -eq "RUNNING") { "Green" } else { "Red" }
Write-Host "  │  Screening API       http://localhost:8080   " -NoNewline -ForegroundColor White
Write-Host ("{0,-14}" -f $backStatus) -ForegroundColor $backColor
Write-Host "  │                          Swagger: /swagger-ui.html        │" -ForegroundColor DarkGray

if (-not $SkipFrontend) {
    $feStatus = if ($frontendProc -and !$frontendProc.HasExited) { "RUNNING" } else { "STOPPED" }
    $feColor  = if ($feStatus -eq "RUNNING") { "Green" } else { "Red" }
    Write-Host "  │  Officer Console     http://localhost:5173   " -NoNewline -ForegroundColor White
    Write-Host ("{0,-14}" -f $feStatus) -ForegroundColor $feColor
}

Write-Host "  └─────────────────────────────────────────────────────────────┘" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Press Ctrl+C to stop all services." -ForegroundColor DarkGray
Write-Host ""

# ── Keep alive & monitor ──────────────────────────────────────────────────

try {
    while ($true) {
        Start-Sleep -Seconds 5

        # Check if any process died
        $anyDead = $false
        foreach ($proc in $script:childProcesses) {
            if ($proc -and $proc.HasExited -and $proc.ExitCode -ne 0) {
                $anyDead = $true
                Write-Warn "$($proc.ProcessName) (PID $($proc.Id)) exited with code $($proc.ExitCode)"
            }
        }

        if ($anyDead) {
            Write-Warn "A service stopped. Press Ctrl+C to shut down everything, or restart manually."
        }
    }
} finally {
    Stop-AllServices
}
