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
Write-Host "[chaos] stopping $Container for ${DurationSec}s (then auto-restart)..." -ForegroundColor Yellow

docker run --rm -v /var/run/docker.sock:/var/run/docker.sock gaiaadm/pumba:0.11.6 `
    --log-level info stop --restart --duration "${DurationSec}s" --time $GraceSec $Container

Write-Host "[chaos] $Container restarted. Give the app a few seconds to reconnect + rebuild." -ForegroundColor Green
