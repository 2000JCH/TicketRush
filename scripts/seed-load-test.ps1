<#
.SYNOPSIS
  Seed data for load / chaos testing (pure REST, no docker/db access):
  approve an ORGANIZER -> register an event with one SEATED section -> pre-create/login BUYER
  accounts -> wait until the event opens.

.DESCRIPTION
  GoldenPathSimulation (Gatling) needs one OPEN event with a SEATED section.
  EventRequest.openAt has @Future validation, so we register with openAt = now + OpenLeadSeconds
  and then wait that long. Seat ids are discovered by the simulation itself from GET /seats,
  so this script does not need to touch MySQL.

  BUYER accounts are also pre-created and logged in here (signup + login, BCrypt cost paid now)
  and written to src/gatling/resources/data/buyers.csv (email,password,accessToken). This mirrors
  how real ticketing works -- users are already signed up and logged in long before the rush --
  and keeps login latency out of the measured Gatling window (it was masking lock-contention
  signal in the distributed-lock benchmark and skewing the capacity test's bottleneck). Access
  tokens default to a 30 min TTL (jwt.access-token-expiration), comfortably longer than the wait
  + a single Gatling run. GoldenPathSimulation reads this CSV as a circular feeder, so give
  -BuyerCount at least as large as the biggest -Users you plan to run against this event.

  Re-runnable: each run creates a fresh event AND a fresh buyer pool (buyers.csv is overwritten).
  Reuse the printed event.id across multiple Gatling runs (e.g. the redis vs db lock benchmark)
  until seats run low; buyers.csv is reused automatically since Gatling reads it at run time.

.EXAMPLE
  powershell -File scripts\seed-load-test.ps1
  powershell -File scripts\seed-load-test.ps1 -Rows 15 -SeatsPerRow 30 -OpenLeadSeconds 90 -BuyerCount 300
#>
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$AdminEmail = "admin@ticketrush.com",
    [string]$AdminPassword = "admin1234",
    [int]$Rows = 15,
    [int]$SeatsPerRow = 30,
    [int]$Price = 100000,
    [int]$OpenLeadSeconds = 90,
    [int]$BuyerCount = 300,
    [switch]$NoWait
)

$ErrorActionPreference = "Stop"
$ts = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$organizerEmail = "seed-organizer-$ts@ticketrush.test"
$organizerPassword = "seedorg1234"

function Post($path, $body, $token) {
    $headers = @{ "Content-Type" = "application/json" }
    if ($token) { $headers["Authorization"] = "Bearer $token" }
    return Invoke-RestMethod -Uri "$BaseUrl$path" -Method Post -Headers $headers -Body ($body | ConvertTo-Json -Depth 10)
}
function Get_($path, $token) {
    return Invoke-RestMethod -Uri "$BaseUrl$path" -Method Get -Headers @{ "Authorization" = "Bearer $token" }
}
function Patch_($path, $token) {
    return Invoke-RestMethod -Uri "$BaseUrl$path" -Method Patch -Headers @{ "Authorization" = "Bearer $token" }
}

Write-Host "1) ADMIN login" -ForegroundColor Cyan
$adminToken = (Post "/api/v1/auth/login" @{ email = $AdminEmail; password = $AdminPassword } $null).accessToken

Write-Host "2) ORGANIZER signup: $organizerEmail" -ForegroundColor Cyan
Post "/api/v1/auth/signup" @{ email = $organizerEmail; password = $organizerPassword; role = "ORGANIZER" } $null | Out-Null

Write-Host "3) ADMIN approves ORGANIZER" -ForegroundColor Cyan
$pending = Get_ "/api/v1/admin/accounts/pending" $adminToken
$target = $pending | Where-Object { $_.email -eq $organizerEmail } | Select-Object -First 1
if (-not $target) { throw "organizer $organizerEmail not found in pending list" }
Patch_ "/api/v1/admin/accounts/$($target.accountId)/approve" $adminToken | Out-Null

Write-Host "4) ORGANIZER login" -ForegroundColor Cyan
$orgToken = (Post "/api/v1/auth/login" @{ email = $organizerEmail; password = $organizerPassword } $null).accessToken

$openAt = (Get-Date).AddSeconds($OpenLeadSeconds)
Write-Host "5) register event (openAt = $($openAt.ToString('HH:mm:ss')))" -ForegroundColor Cyan
$event = Post "/api/v1/events" @{
    name     = "load-test-$ts"
    openAt   = $openAt.ToString("yyyy-MM-ddTHH:mm:ss")
    sections = @(
        @{ name = "LOAD-A"; type = "SEATED"; price = $Price; rowCount = $Rows; seatsPerRow = $SeatsPerRow }
    )
} $orgToken
$eventId = $event.id
$sectionId = ($event.sections | Select-Object -First 1).id
$seatTotal = $Rows * $SeatsPerRow

Write-Host "6) pre-creating $BuyerCount BUYER accounts (signup + login now, so Gatling skips both)" -ForegroundColor Cyan
$buyerPassword = "loadtest1234"
$buyerRows = New-Object System.Collections.Generic.List[string]
$buyerRows.Add("email,password,accessToken")
for ($i = 0; $i -lt $BuyerCount; $i++) {
    $buyerEmail = "load-buyer-$ts-$i@ticketrush.test"
    Post "/api/v1/auth/signup" @{ email = $buyerEmail; password = $buyerPassword; role = "BUYER" } $null | Out-Null
    $buyerToken = (Post "/api/v1/auth/login" @{ email = $buyerEmail; password = $buyerPassword } $null).accessToken
    $buyerRows.Add("$buyerEmail,$buyerPassword,$buyerToken")
    if (($i + 1) % 50 -eq 0) { Write-Host "   ...$($i + 1)/$BuyerCount" -ForegroundColor DarkGray }
}
$dataDir = Join-Path $PSScriptRoot "..\ticketrush-backend\src\gatling\resources\data"
New-Item -ItemType Directory -Force -Path $dataDir | Out-Null
$csvPath = Join-Path $dataDir "buyers.csv"
Set-Content -Path $csvPath -Value $buyerRows -Encoding utf8
Write-Host "   buyers.csv written: $csvPath"

if (-not $NoWait) {
    $waitMs = ($openAt - (Get-Date)).TotalMilliseconds + 3000
    if ($waitMs -gt 0) {
        Write-Host "7) waiting $([math]::Round($waitMs/1000))s for the event to open..." -ForegroundColor Cyan
        Start-Sleep -Milliseconds $waitMs
    }
}

Write-Host ""
Write-Host "==== seed done ====" -ForegroundColor Green
Write-Host "eventId    = $eventId"
Write-Host "sectionId  = $sectionId"
Write-Host "seats      = $seatTotal  ($Rows rows x $SeatsPerRow)"
Write-Host "buyers     = $BuyerCount  (buyers.csv, accessToken pre-fetched)"
Write-Host "opens at   = $($openAt.ToString('HH:mm:ss'))" $(if ($NoWait) { "(NoWait: start Gatling after this time)" })
Write-Host ""
Write-Host "Gatling run:" -ForegroundColor Yellow
Write-Host "  powershell -File scripts\run-gatling.ps1 -EventId $eventId -SectionId $sectionId -Users 150 -RampSeconds 30"
Write-Host "  (make sure -BuyerCount >= -Users, otherwise Gatling reuses buyer accounts across VUs)" -ForegroundColor DarkGray
