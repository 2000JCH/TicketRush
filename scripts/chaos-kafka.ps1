<#
.SYNOPSIS
  Chaos scenario 2 — take the Kafka broker down for a while, then let it come back (decisions.md 8번).

.DESCRIPTION
  Uses Pumba to SIGTERM the ticketrush-kafka container, wait -DurationSec, then restart it.
  Run this WHILE a Gatling load is in flight and there is payment-failure traffic (so the
  outbox -> Debezium -> Kafka path is exercised).

  Expected (decisions.md 8번): payment confirmation (DB tx + outbox insert) keeps succeeding and
  user responses do NOT fail while the broker is down. Debezium just can't publish, so
  outbox_events rows pile up; after the broker recovers they drain and Kafka consumer lag
  returns to 0 (watch the "Kafka Consumer Lag" panel). No events are lost.

.EXAMPLE
  powershell -File scripts\chaos-kafka.ps1
  powershell -File scripts\chaos-kafka.ps1 -DurationSec 120
#>
param(
    [int]$DurationSec = 90,
    [string]$Container = "ticketrush-kafka",
    [int]$GraceSec = 5
)

$ErrorActionPreference = "Stop"

# PowerShell's PATH on this machine doesn't resolve "docker" (only Git Bash's does) -- an unresolved
# "docker" makes PowerShell fall back to Windows' "open with" file picker instead of erroring, which
# looks like the script silently hanging. Resolve the real docker.exe explicitly so this works from
# a plain PowerShell terminal too, not just from inside this Claude Code session.
$dockerCmd = Get-Command docker -ErrorAction SilentlyContinue
$dockerExe = if ($dockerCmd -and $dockerCmd.Source -like "*.exe") { $dockerCmd.Source } else { "C:\Program Files\Docker\Docker\resources\bin\docker.exe" }
if (-not (Test-Path $dockerExe)) { throw "docker.exe not found (tried PATH and $dockerExe) -- is Docker Desktop installed/running?" }

# Bracket the outage with UTC timestamps for Grafana annotations (see chaos-redis.ps1 for why).
# Appended to scripts/chaos-timeline.log.
$logFile = Join-Path $PSScriptRoot "chaos-timeline.log"
Write-Host "[chaos] stopping $Container for ${DurationSec}s (then auto-restart)..." -ForegroundColor Yellow
Write-Host "        kafka-connect may need a manual restart afterwards if it drops the connector:" -ForegroundColor DarkGray
Write-Host "        docker compose restart kafka-connect" -ForegroundColor DarkGray

$stopAt = [DateTime]::UtcNow
Write-Host "[chaos] STOP  $Container ~ $($stopAt.ToString('yyyy-MM-dd HH:mm:ss')) UTC" -ForegroundColor Yellow
Add-Content $logFile "kafka STOP  ~ $($stopAt.ToString('o'))"

& $dockerExe run --rm -v /var/run/docker.sock:/var/run/docker.sock gaiaadm/pumba:0.11.6 `
    --log-level info stop --restart --duration "${DurationSec}s" --time $GraceSec $Container

$restartAt = [DateTime]::UtcNow
Write-Host "[chaos] RESTART $Container ~ $($restartAt.ToString('yyyy-MM-dd HH:mm:ss')) UTC. Watch outbox_events drain + consumer lag return to 0." -ForegroundColor Green
Add-Content $logFile "kafka RESTART ~ $($restartAt.ToString('o'))"
