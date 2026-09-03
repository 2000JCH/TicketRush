<#
.SYNOPSIS
  Chaos scenario A-2 helper — send signed PortOne "Transaction.Failed" webhooks for a fraction of
  the PAYMENT_REQUESTED reservations, continuously, so the outbox -> Debezium -> Kafka ->
  PaymentFailedConsumer -> releaseAfterFailure path is actually exercised while Kafka is down.

.DESCRIPTION
  GoldenPathSimulation only goes as far as POST /api/v1/reservations (PAYMENT_REQUESTED). The
  outbox row is written by ReservationService.markPaymentFailed, which only runs on a
  Transaction.Failed webhook. So A-2 needs this script running alongside the Gatling load:
  every -IntervalSec it queries the DB for fresh PAYMENT_REQUESTED reservations of -EventId and
  fails a -FailRatio sample of the ones it hasn't touched yet.

  Signature: Standard Webhooks (webhook-id / webhook-timestamp / webhook-signature: "v1,<b64>"),
  HMAC-SHA256 over "<id>.<timestamp>.<rawBody>" with the base64 secret from ticketrush-backend/.env
  (PORTONE_WEBHOOK_SECRET, "whsec_" prefix stripped). Matches PaymentWebhookService.

.EXAMPLE
  powershell -File scripts\fail-payments.ps1 -EventId 1 -DurationSec 240
  powershell -File scripts\fail-payments.ps1 -EventId 1 -FailRatio 1.0 -IntervalSec 2
#>
param(
    [Parameter(Mandatory = $true)][long]$EventId,
    [double]$FailRatio = 0.7,
    [int]$IntervalSec = 3,
    [int]$DurationSec = 240,
    [string]$BaseUrl = "http://localhost:8080",
    [string]$MysqlContainer = "ticketrush-mysql"
)

$ErrorActionPreference = "Stop"

# Same PATH problem as chaos-*.ps1 — resolve docker.exe explicitly.
$dockerCmd = Get-Command docker -ErrorAction SilentlyContinue
$dockerExe = if ($dockerCmd -and $dockerCmd.Source -like "*.exe") { $dockerCmd.Source } else { "C:\Program Files\Docker\Docker\resources\bin\docker.exe" }
if (-not (Test-Path $dockerExe)) { throw "docker.exe not found (tried PATH and $dockerExe)" }

$envFile = Join-Path $PSScriptRoot "..\ticketrush-backend\.env"
$secretLine = Select-String -Path $envFile -Pattern '^\s*PORTONE_WEBHOOK_SECRET\s*=' | Select-Object -First 1
if (-not $secretLine) { throw "PORTONE_WEBHOOK_SECRET not found in $envFile" }
$secret = ($secretLine.Line -replace '^\s*PORTONE_WEBHOOK_SECRET\s*=\s*', '').Trim().Trim('"')
$secretPart = if ($secret.StartsWith("whsec_")) { $secret.Substring(6) } else { $secret }
$key = [Convert]::FromBase64String($secretPart)

function Send-FailWebhook([long]$reservationId) {
    $body = '{"type":"Transaction.Failed","data":{"paymentId":"TICKETRUSH-' + $reservationId + '"}}'
    $wid = [Guid]::NewGuid().ToString()
    $ts = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString()
    $hmac = [System.Security.Cryptography.HMACSHA256]::new($key)
    try {
        $hash = $hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes("$wid.$ts.$body"))
    } finally { $hmac.Dispose() }
    $sig = "v1," + [Convert]::ToBase64String($hash)
    $headers = @{
        "webhook-id"        = $wid
        "webhook-timestamp" = $ts
        "webhook-signature" = $sig
        "Content-Type"      = "application/json"
    }
    try {
        Invoke-RestMethod -Uri "$BaseUrl/api/v1/payments/webhook" -Method Post -Headers $headers -Body $body | Out-Null
        return $true
    } catch {
        Write-Host "  ! webhook $reservationId -> $($_.Exception.Message)" -ForegroundColor DarkYellow
        return $false
    }
}

Write-Host "[fail-payments] event=$EventId ratio=$FailRatio every ${IntervalSec}s for ${DurationSec}s" -ForegroundColor Cyan
$attempted = New-Object System.Collections.Generic.HashSet[long]
$rnd = [Random]::new()
$deadline = (Get-Date).AddSeconds($DurationSec)
$sent = 0

while ((Get-Date) -lt $deadline) {
    $raw = & $dockerExe exec -e MYSQL_PWD=root $MysqlContainer mysql -uroot ticketrush -N -B -e `
        "SELECT id FROM reservation WHERE event_id=$EventId AND status='PAYMENT_REQUESTED'" 2>$null
    $ids = @($raw | Where-Object { $_ -match '^\d+$' } | ForEach-Object { [long]$_ })
    $fresh = $ids | Where-Object { -not $attempted.Contains($_) }
    foreach ($id in $fresh) {
        [void]$attempted.Add($id)
        if ($rnd.NextDouble() -lt $FailRatio) {
            if (Send-FailWebhook $id) { $sent++ }
        }
    }
    Write-Host ("[fail-payments] {0:HH:mm:ss}  PAYMENT_REQUESTED={1}  누적 실패 전송={2}" -f (Get-Date), $ids.Count, $sent)
    Start-Sleep -Seconds $IntervalSec
}

Write-Host "[fail-payments] done — 총 $sent 건 Transaction.Failed 전송" -ForegroundColor Green
