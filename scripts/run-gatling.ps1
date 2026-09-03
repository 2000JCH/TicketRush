<#
.SYNOPSIS
  Run GoldenPathSimulation. Wrapper around `gradlew gatlingRun` that passes -D system
  properties correctly (Windows PowerShell mangles inline `-Dfoo.bar=baz` args).

.EXAMPLE
  # chaos test traffic — burst (70% at once) + trickle over RampSeconds, so traffic keeps
  # flowing past the failure-injection point instead of finishing all at once
  powershell -File scripts\run-gatling.ps1 -EventId 158 -SectionId 277 -Users 150 -RampSeconds 40

  # chaos test for the Grafana screenshot run — add a steady tail so the graph shows the full
  # "burst -> outage -> recovery -> back to green" arc (not just up to recovery)
  powershell -File scripts\run-gatling.ps1 -EventId 158 -SectionId 277 -Users 150 -RampSeconds 40 -TailSeconds 120

  # distributed-lock benchmark (all group holds, everyone hits queue-entry at the same instant
  # like a real ticket-opening rush — this is what actually creates lock contention)
  powershell -File scripts\run-gatling.ps1 -EventId 158 -SectionId 277 -Users 300 -GroupHoldRatio 1.0 -InjectMode atonce
#>
param(
    [Parameter(Mandatory = $true)][long]$EventId,
    [Parameter(Mandatory = $true)][long]$SectionId,
    [string]$BaseUrl = "http://localhost:8080",
    [int]$Users = 150,
    [int]$RampSeconds = 30,
    [double]$GroupHoldRatio = 0.3,
    [ValidateSet("chaos", "atonce")][string]$InjectMode = "chaos",
    [double]$BurstRatio = 0.7,
    # chaos 모드에서 버스트+트리클 뒤로 이어붙이는 꼬리 부하(장애 복구 후 그래프가 정상으로 내려가는 걸
    # 스크린샷에 담기 위함). 0이면 꺼짐(기본).
    [int]$TailSeconds = 0,
    [double]$TailUsersPerSec = 2
)

$ErrorActionPreference = "Stop"
$backendDir = Join-Path $PSScriptRoot "..\ticketrush-backend"

$gradleArgs = @(
    "gatlingRun",
    "--simulation", "simulation.GoldenPathSimulation",
    "-DbaseUrl=$BaseUrl",
    "-Devent.id=$EventId",
    "-Dsection.id=$SectionId",
    "-Dusers=$Users",
    "-Dramp.seconds=$RampSeconds",
    "-Dgroup.hold.ratio=$GroupHoldRatio",
    "-Dinject.mode=$InjectMode",
    "-Dburst.ratio=$BurstRatio",
    "-Dtail.seconds=$TailSeconds",
    "-Dtail.users.per.sec=$TailUsersPerSec",
    "--console=plain"
)

Push-Location $backendDir
try {
    & .\gradlew.bat @gradleArgs
} finally {
    Pop-Location
}
