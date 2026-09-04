<#
.SYNOPSIS
  Run CapacitySimulation (한계 테스트 — test-plan.md 4번). Wrapper around `gradlew gatlingRun`
  that passes -D system properties correctly (Windows PowerShell mangles inline -Dfoo.bar=baz).

.DESCRIPTION
  동시 사용자 수를 계단식으로 끌어올려 "이 스택은 동시 몇 명까지 버티나"를 찾는다.
  2단계로 쓴다:
    1차 (범위 찾기, 버림):  -Mode double -LevelSeconds 30 -Max 1600
    2차 (정밀, 기록):       -Mode linear -Start <구간하한> -Step 100 -Max <구간상한> -LevelSeconds 60

  baseUrl 기본값이 :8080 (app 컨테이너 직접) — nginx rate limit이 단일 호스트 부하를 전부 429로
  막으므로 한계 테스트는 nginx를 경유하지 않는다 (docker-compose.rehearsal.yml [nginx] 주석).

.EXAMPLE
  # 1차 — 범위 찾기
  powershell -File scripts\run-capacity.ps1 -EventId 42 -SectionId 55 -Mode double -Max 1600 -LevelSeconds 30

  # 2차 — 1차가 600~800에서 깨졌다면 그 구간만 정밀
  powershell -File scripts\run-capacity.ps1 -EventId 43 -SectionId 56 -Mode linear -Start 600 -Step 50 -Max 800 -LevelSeconds 60
#>
param(
    [Parameter(Mandatory = $true)][long]$EventId,
    [Parameter(Mandatory = $true)][long]$SectionId,
    [string]$BaseUrl = "http://localhost:8080",
    [ValidateSet("linear", "double")][string]$Mode = "linear",
    [int]$Start = 100,
    [int]$Max = 1000,
    [int]$Step = 100,
    [int]$LevelSeconds = 60,
    [int]$RampSeconds = 10,
    [double]$GroupHoldRatio = 0.3
)

$ErrorActionPreference = "Stop"
$backendDir = Join-Path $PSScriptRoot "..\ticketrush-backend"

$gradleArgs = @(
    "gatlingRun",
    "--simulation", "simulation.CapacitySimulation",
    "-DbaseUrl=$BaseUrl",
    "-Devent.id=$EventId",
    "-Dsection.id=$SectionId",
    "-Dcapacity.mode=$Mode",
    "-Dcapacity.start=$Start",
    "-Dcapacity.max=$Max",
    "-Dcapacity.step=$Step",
    "-Dcapacity.level.seconds=$LevelSeconds",
    "-Dcapacity.ramp.seconds=$RampSeconds",
    "-Dgroup.hold.ratio=$GroupHoldRatio",
    "--console=plain"
)

Push-Location $backendDir
try {
    & .\gradlew.bat @gradleArgs
} finally {
    Pop-Location
}
