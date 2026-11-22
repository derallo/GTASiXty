<#
.SYNOPSIS
  Self-test for bench.ps1 + bench-compare.ps1 using synthetic telemetry (no console, no headset).

.DESCRIPTION
  Writes two synthetic perf-telemetry logs that differ only in cadence - A is 60 fps of unique
  content, B is 30 fps content repeated inside a 60 Hz stream - then checks that the capture parser
  and the comparison reach the expected conclusions:
    * duplicate ratio moves from ~0% to ~50%
    * unique content fps halves
    * the PC path (decode / NVENC / pcLat) is unchanged

  Run this after changing the telemetry format or the parser. It cannot prove anything about the
  console; it only proves the measurement pipeline.
#>
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Out = Join-Path $Root 'build\bench'
New-Item -ItemType Directory -Force -Path $Out | Out-Null

function New-Log([string]$path, [double]$uniq, [double]$dup, [int]$run1, [string]$mode, [int]$flips) {
    $lines = [System.Collections.Generic.List[string]]::new()
    $lines.Add('# synthetic telemetry for bench-selftest')
    for ($i = 0; $i -lt 40; $i++) {
        $line = "in=59.9 (23.4M 2560x1440) uniq={0:F1} out=59.9 fps [{1}] -> 2560x1440@60 | pcLat=9.96ms dec=3.50ms decLag=0.0/0.0ms gpu=5.15ms(up=0.31,flow=4.68,fg+up=0.16) nvenc=5.09ms tcp=0.04/0.26ms | out=79.2 Mbps qDrop=0 tcpDrop=0 qMax=1 rtt=17.0ms | quest=10.08/10.08ms enc[s=0.10 p=2.10 w=2.60 l=0.10 c=0.10] dup={2:F1}% runs={3}/0/0 flips={4}" -f $uniq, $mode, $dup, $run1, $flips
        $lines.Add($line)
    }
    $lines -join "`n" | Set-Content -LiteralPath $path -Encoding ascii
}

$logA = Join-Path $Out 'selftest-vrr-on.log'
$logB = Join-Path $Out 'selftest-vrr-off.log'
New-Log $logA 59.8 0.2 0 'RCAS' 0
New-Log $logB 29.9 49.9 60 'FG' 0

$ps = Join-Path $PSHOME 'powershell.exe'
& $ps -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Root 'scripts\bench.ps1') `
    -Label 'selftest-vrr-on' -FromLog $logA | Out-Null
& $ps -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Root 'scripts\bench.ps1') `
    -Label 'selftest-vrr-off' -FromLog $logB | Out-Null

$jsonA = Get-ChildItem $Out -Filter '*-selftest-vrr-on.json' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
$jsonB = Get-ChildItem $Out -Filter '*-selftest-vrr-off.json' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $jsonA -or -not $jsonB) { throw 'bench.ps1 did not produce JSON summaries' }

$report = Join-Path $Out 'selftest-compare.md'
& $ps -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Root 'scripts\bench-compare.ps1') `
    -A $jsonA.FullName -B $jsonB.FullName -Out $report | Out-Null

$ja = Get-Content $jsonA.FullName -Raw | ConvertFrom-Json
$jb = Get-Content $jsonB.FullName -Raw | ConvertFrom-Json

$fails = [System.Collections.Generic.List[string]]::new()
if ([math]::Abs([double]$ja.metrics.dupPct.mean - 0.2) -gt 0.5) { $fails.Add("A dupPct = $($ja.metrics.dupPct.mean), expected ~0.2") }
if ([math]::Abs([double]$jb.metrics.dupPct.mean - 49.9) -gt 0.5) { $fails.Add("B dupPct = $($jb.metrics.dupPct.mean), expected ~49.9") }
if ([math]::Abs([double]$ja.metrics.uniq.mean - 59.8) -gt 0.5) { $fails.Add("A uniq = $($ja.metrics.uniq.mean), expected ~59.8") }
if ([math]::Abs([double]$jb.metrics.uniq.mean - 29.9) -gt 0.5) { $fails.Add("B uniq = $($jb.metrics.uniq.mean), expected ~29.9") }
if ([math]::Abs([double]$ja.metrics.pcLat.mean - 9.96) -gt 0.05) { $fails.Add("A pcLat = $($ja.metrics.pcLat.mean), expected 9.96") }
if ([math]::Abs([double]$jb.metrics.pcLat.mean - 9.96) -gt 0.05) { $fails.Add("B pcLat = $($jb.metrics.pcLat.mean), expected 9.96") }
$text = Get-Content $report -Raw
if ($text -notmatch 'Cadence moved') { $fails.Add('compare report did not flag the cadence change') }
if ($text -notmatch 'PC latency is unchanged') { $fails.Add('compare report did not recognise the invariant PC path') }

if ($fails.Count -gt 0) {
    $fails | ForEach-Object { Write-Host "[selftest] FAIL: $_" -ForegroundColor Red }
    throw "bench selftest failed ($($fails.Count) checks)"
}
Write-Host "[selftest] PASS - cadence detection, PC-path invariance and comparison all work."
Write-Host "[selftest] report: $report"
