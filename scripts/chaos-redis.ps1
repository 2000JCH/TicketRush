<#
.SYNOPSIS
  Chaos scenario 1 — kill Redis for a while, then let it come back (decisions.md 8번).

.DESCRIPTION
  Uses Pumba to SIGTERM the ticketrush-redis container, wait -DurationSec, then restart it.
  Run this WHILE a Gatling load is in flight (scripts/run-gatling.ps1) and watch the Grafana
  dashboard (http://localhost:3000, "TicketRush — 부하/카오스 관찰").

  Expected (decisions.md 1번): in-flight seat holds may be lost, but PAYMENT_REQUESTED /
  PAYMENT_CONFIRMED seats are rebuilt from MySQL on reconnect -> no oversell. Verify afterwards
  by comparing confirmed reservations against seat capacity.

.EXAMPLE
  powershell -File scripts\chaos-redis.ps1
  powershell -File scripts\chaos-redis.ps1 -DurationSec 90
#>
param(
    [int]$DurationSec = 60,
    [string]$Container = "ticketrush-redis",
    [int]$GraceSec = 3
)

$ErrorActionPreference = "Stop"

# PowerShell's PATH on this machine doesn't resolve "docker" (only Git Bash's does) -- an unresolved
# "docker" makes PowerShell fall back to Windows' "open with" file picker instead of erroring, which
# looks like the script silently hanging. Resolve the real docker.exe explicitly so this works from
# a plain PowerShell terminal too, not just from inside this Claude Code session.
$dockerCmd = Get-Command docker -ErrorAction SilentlyContinue
$dockerExe = if ($dockerCmd -and $dockerCmd.Source -like "*.exe") { $dockerCmd.Source } else { "C:\Program Files\Docker\Docker\resources\bin\docker.exe" }
if (-not (Test-Path $dockerExe)) { throw "docker.exe not found (tried PATH and $dockerExe) -- is Docker Desktop installed/running?" }

# Bracket the outage with UTC timestamps so the exact stop/restart moments can be dropped onto the
# Grafana timeline as annotations (needed for the portfolio screenshots). Pumba does the actual
# stop ~1s after it starts and the restart right when it returns, so these bracket the real events
# within ~1s. Also appended to scripts/chaos-timeline.log for easy copy/paste afterwards.
$logFile = Join-Path $PSScriptRoot "chaos-timeline.log"
$stopAt = [DateTime]::UtcNow
Write-Host "[chaos] STOP  $Container ~ $($stopAt.ToString('yyyy-MM-dd HH:mm:ss')) UTC (for ${DurationSec}s, then auto-restart)..." -ForegroundColor Yellow
Add-Content $logFile "redis STOP  ~ $($stopAt.ToString('o'))"

& $dockerExe run --rm -v /var/run/docker.sock:/var/run/docker.sock gaiaadm/pumba:0.11.6 `
    --log-level info stop --restart --duration "${DurationSec}s" --time $GraceSec $Container

$restartAt = [DateTime]::UtcNow
Write-Host "[chaos] RESTART $Container ~ $($restartAt.ToString('yyyy-MM-dd HH:mm:ss')) UTC. Give the app a few seconds to reconnect + rebuild." -ForegroundColor Green
Add-Content $logFile "redis RESTART ~ $($restartAt.ToString('o'))"
