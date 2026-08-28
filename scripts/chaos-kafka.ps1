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
Write-Host "[chaos] stopping $Container for ${DurationSec}s (then auto-restart)..." -ForegroundColor Yellow
Write-Host "        kafka-connect may need a manual restart afterwards if it drops the connector:" -ForegroundColor DarkGray
Write-Host "        docker compose restart kafka-connect" -ForegroundColor DarkGray

docker run --rm -v /var/run/docker.sock:/var/run/docker.sock gaiaadm/pumba:0.11.6 `
    --log-level info stop --restart --duration "${DurationSec}s" --time $GraceSec $Container

Write-Host "[chaos] $Container restarted. Watch outbox_events drain + consumer lag return to 0." -ForegroundColor Green
