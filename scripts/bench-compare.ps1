<#
.SYNOPSIS
  Compare two bench.ps1 summaries (A = baseline, B = changed settings) and write a report.

.EXAMPLE
  scripts\bench-compare.ps1 -A build\bench\20261121-0700-vrr-on-llm-on.json `
                            -B build\bench\20261121-0710-vrr-off-llm-off.json
#>
param(
    [Parameter(Mandatory = $true)][string]$A,
    [Parameter(Mandatory = $true)][string]$B,
    [string]$Out
)

$ErrorActionPreference = 'Stop'
$inv = [System.Globalization.CultureInfo]::InvariantCulture
function F([object]$v, [string]$fmt = '0.00') { ([double]$v).ToString($fmt, $inv) }
function Pct([double]$from, [double]$to) {
    if ([math]::Abs($from) -lt 1e-9) { return 'n/a' }
    $d = (($to - $from) / $from) * 100.0
    $sign = if ($d -ge 0) { '+' } else { '' }
    "$sign$($d.ToString('0.0', $inv))%"
}
function Pts([double]$from, [double]$to) {
    $d = $to - $from
    $sign = if ($d -ge 0) { '+' } else { '' }
    "$sign$($d.ToString('0.0', $inv)) pts"
}

$ja = Get-Content -LiteralPath $A -Raw | ConvertFrom-Json
$jb = Get-Content -LiteralPath $B -Raw | ConvertFrom-Json

# PowerShell 7 ConvertFrom-Json keeps ordered dictionaries as PSCustomObject; helper to fetch nested.
function M($s, $k, $field) { $s.metrics.$k.$field }

$md = [System.Collections.Generic.List[string]]::new()
$md.Add("# A/B: $($ja.label)  vs  $($jb.label)")
$md.Add('')
$md.Add("| | A: $($ja.label) | B: $($jb.label) | delta |")
$md.Add('|---|---|---|---|')

$cadenceRows = @(
    @{ name = 'source fps (in)'; a = (M $ja 'in' 'mean'); b = (M $jb 'in' 'mean'); fmt = '0.00'; kind = 'pct' },
    @{ name = 'unique content fps (uniq)'; a = (M $ja 'uniq' 'mean'); b = (M $jb 'uniq' 'mean'); fmt = '0.00'; kind = 'pct' },
    @{ name = 'duplicate frames (%)'; a = if ($ja.dupAvailable) { (M $ja 'dupPct' 'mean') } else { $null }; b = if ($jb.dupAvailable) { (M $jb 'dupPct' 'mean') } else { $null }; fmt = '0.0'; kind = 'pts' },
    @{ name = 'content ratio uniq/in (%)'; a = $ja.cadence.contentRatioPct; b = $jb.cadence.contentRatioPct; fmt = '0.0'; kind = 'pts' },
    @{ name = 'rtt (ms)'; a = (M $ja 'rtt' 'mean'); b = (M $jb 'rtt' 'mean'); fmt = '0.0'; kind = 'pct' },
    @{ name = 'xbox bitrate (Mbps)'; a = (M $ja 'xboxMbps' 'mean'); b = (M $jb 'xboxMbps' 'mean'); fmt = '0.0'; kind = 'pct' }
)
foreach ($r in $cadenceRows) {
    if ($null -eq $r.a -or $null -eq $r.b) {
        $md.Add("| $($r.name) | - | - | n/a |")
        continue
    }
    $delta = if ($r.kind -eq 'pts') { Pts $r.a $r.b } else { Pct $r.a $r.b }
    $md.Add("| $($r.name) | $((F $r.a $r.fmt)) | $((F $r.b $r.fmt)) | $delta |")
}

$md.Add('')
$md.Add('## Mode mix / judder exposure')
$md.Add('')
$md.Add("| | A | B |")
$md.Add('|---|---|---|')
$md.Add("| native passthrough < 50 fps | $($ja.health.nativeBelow50Pct)% | $($jb.health.nativeBelow50Pct)% |")
$md.Add("| 34-46 fps FG dead band | $($ja.health.deadbandPct)% | $($jb.health.deadbandPct)% |")
$md.Add("| mode flips / min | $($ja.cadence.flipsPerMin) | $($jb.cadence.flipsPerMin) |")
$modeKeys = @(@($ja.cadence.modeSharesPct.PSObject.Properties.Name) + @($jb.cadence.modeSharesPct.PSObject.Properties.Name) | Select-Object -Unique)
$modea = $ja.cadence.modeSharesPct
$modeb = $jb.cadence.modeSharesPct
foreach ($k in $modeKeys) {
    $va = if ($modea.PSObject.Properties.Name -contains $k) { $modea.$k } else { 0 }
    $vb = if ($modeb.PSObject.Properties.Name -contains $k) { $modeb.$k } else { 0 }
    $md.Add("| mode $k (% of samples) | $va | $vb |")
}

$md.Add('')
$md.Add('## PC path (should be invariant to a console display-mode change)')
$md.Add('')
$md.Add("| | A | B | delta |")
$md.Add('|---|---|---|---|')
foreach ($pair in @(@('pcLat', 'pc latency ms'), @('dec', 'decode ms'), @('nvenc', 'NVENC ms'), @('gpu', 'GPU ms'), @('outFps', 'out fps'))) {
    $k = $pair[0]; $n = $pair[1]
    $va = M $ja $k 'mean'; $vb = M $jb $k 'mean'
    $md.Add("| $n | $(F $va '0.00') | $(F $vb '0.00') | $(Pct $va $vb) |")
}

$md.Add('')
$md.Add('## Verdict')
$md.Add('')

$dupA = [double](M $ja 'dupPct' 'mean')
$dupB = [double](M $jb 'dupPct' 'mean')
$dupComparable = [bool]$ja.dupAvailable -and [bool]$jb.dupAvailable
$pcA = [double](M $ja 'pcLat' 'mean')
$pcB = [double](M $jb 'pcLat' 'mean')
$cadenceMoved = $false
if ($dupComparable) {
    $cadenceMoved = ([math]::Abs($dupB - $dupA) -ge 5.0) -or `
        ([math]::Abs([double]$jb.cadence.contentRatioPct - [double]$ja.cadence.contentRatioPct) -ge 5.0)
}
$pcMoved = ($pcA -gt 0.01 -and [math]::Abs($pcB - $pcA) / $pcA -gt 0.08)

if ($ja.sourceOnly -and $jb.sourceOnly) {
    $md.Add('- Both captures are **source-only**: the numbers describe what the console sent, not Quest-side latency. Re-run with a headset for the end-to-end view.')
}
if (-not $dupComparable) {
    $md.Add('- Duplicate/cadence comparison is unavailable: at least one capture predates the `dup=`/`runs=` telemetry fields. Re-capture both sides with this build.')
} elseif ($cadenceMoved) {
    $md.Add("- **Cadence moved** (duplicate ratio $(F $dupA '0.0')% -> $(F $dupB '0.0')%, content ratio $($ja.cadence.contentRatioPct)% -> $($jb.cadence.contentRatioPct)%). This is the part the Quest app actually experiences: fewer unique frames per second means the frame-generation gate sees a lower content rate.")
} else {
    $md.Add("- **Cadence did not move materially** (duplicate ratio $(F $dupA '0.0')% -> $(F $dupB '0.0')%). The console is feeding the same content rate, so the Quest path has no new work to do.")
}
if ($pcMoved) {
    $md.Add("- PC latency changed by $(Pct $pcA $pcB). That is unexpected for a console-side setting; check for a different codec/bitrate/resolution in the two commands before attributing it to the setting.")
} elseif ($pcA -gt 0.01) {
    $md.Add("- PC latency is unchanged within noise ($(F $pcA '0.00') -> $(F $pcB '0.00') ms), which is the expected result: decode + NVENC do the same work per frame regardless of what the console's display timing is doing.")
}
$md.Add("- Remember the TV is downstream of the console capture: motion smoothing on the TV cannot change these frames. A VRR/ALLM change can only reach the stream through console presentation cadence.")

$text = $md -join "`n"
if (-not $Out) {
    $Out = [System.IO.Path]::ChangeExtension($B, '.compare.md')
}
$text | Set-Content -LiteralPath $Out -Encoding utf8
Write-Host $text
Write-Host ''
Write-Host "[compare] written to $Out"

