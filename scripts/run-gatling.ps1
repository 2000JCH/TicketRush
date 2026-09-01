<#
.SYNOPSIS
  Run GoldenPathSimulation. Wrapper around `gradlew gatlingRun` that passes -D system
  properties correctly (Windows PowerShell mangles inline `-Dfoo.bar=baz` args).

.EXAMPLE
  # chaos test traffic — burst (70% at once) + trickle over RampSeconds, so traffic keeps
  # flowing past the failure-injection point instead of finishing all at once
  powershell -File scripts\run-gatling.ps1 -EventId 158 -SectionId 277 -Users 150 -RampSeconds 40

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
    [double]$BurstRatio = 0.7
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
    "--console=plain"
)

Push-Location $backendDir
try {
    & .\gradlew.bat @gradleArgs
} finally {
    Pop-Location
}
