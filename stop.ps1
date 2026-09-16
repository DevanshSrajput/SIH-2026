<#
.SYNOPSIS
    Stops all project services.

.DESCRIPTION
    Finds and kills all processes spawned by start.ps1.
#>

Write-Host "Stopping all services..." -ForegroundColor Yellow

# Kill by port
$ports = @(5000, 8080, 5173)
foreach ($port in $ports) {
    $connections = Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue |
                   Where-Object { $_.State -eq "Listen" }
    foreach ($conn in $connections) {
        $proc = Get-Process -Id $conn.OwningProcess -ErrorAction SilentlyContinue
        if ($proc) {
            Write-Host "  Killing $($proc.ProcessName) (PID $($proc.Id)) on port $port" -ForegroundColor Red
            Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
        }
    }
}

# Also kill mvnw/java processes
Get-Process -Name "java" -ErrorAction SilentlyContinue |
    Where-Object { $_.MainWindowTitle -match "spring-boot" -or $_.CommandLine -match "spring-boot" } |
    ForEach-Object {
        Write-Host "  Killing $($_.ProcessName) (PID $($_.Id))" -ForegroundColor Red
        Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue
    }

Write-Host "Done." -ForegroundColor Green
