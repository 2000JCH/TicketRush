<#
.SYNOPSIS
  Run GoldenPathSimulation. Wrapper around `gradlew gatlingRun` that passes -D system
  properties correctly (Windows PowerShell mangles inline `-Dfoo.bar=baz` args).

.EXAMPLE
  # chaos test traffic
  powershell -File scripts\run-gatling.ps1 -EventId 158 -SectionId 277 -Users 150 -RampSeconds 30

  # distributed-lock benchmark (all group holds)
  powershell -File scripts\run-gatling.ps1 -EventId 158 -SectionId 277 -Users 300 -RampSeconds 60 -GroupHoldRatio 1.0
#>
param(
    [Parameter(Mandatory = $true)][long]$EventId,
    [Parameter(Mandatory = $true)][long]$SectionId,
    [string]$BaseUrl = "http://localhost:8080",
    [int]$Users = 150,
    [int]$RampSeconds = 30,
    [double]$GroupHoldRatio = 0.3
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
    "--console=plain"
)

Push-Location $backendDir
try {
    & .\gradlew.bat @gradleArgs
} finally {
    Pop-Location
}
