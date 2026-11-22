<#
.SYNOPSIS
  Capture and summarise a GTA-Sixty telemetry window (one side of an A/B).

.DESCRIPTION
  Starts the PC streamer with --run-seconds so it stops cleanly (killing it skips
  rtc.close()/api.stop() and can leave a stale virtual pad on the console), then parses the
  perf-telemetry.log lines written during that window.

  Two capture kinds:
    * default        - measures the console -> PC source (cadence, duplicates, RTT) and, if a
                       headset connects, the PC -> Quest path as well. Samples before the first
                       headset frame are excluded from the end-to-end metrics.
    * -Headless      - no headset at all: source cadence only. Fast and repeatable; this is the
                       right mode for answering "did the console's cadence change?".

  The display-mode A/B (Xbox Settings > TV & display options > Video modes):
    1. console on the old settings, TV/console free:
         scripts\bench.ps1 -Label vrr-on-llm-on -Headless -Seconds 90
    2. change the two Xbox settings, let the console settle:
         scripts\bench.ps1 -Label vrr-off-llm-off -Headless -Seconds 90
    3. scripts\bench-compare.ps1 -A build\bench\<...>-vrr-on-llm-on.json `
                                 -B build\bench\<...>-vrr-off-llm-off.json

.NOTES
  Starting a remote-play session takes the console away from the TV. Run it when nobody is
  playing on the console, or the TV session drops to the dashboard (notes 10.5 / 12).
#>
param(
    [Parameter(Mandatory = $true)][string]$Label,
    [int]$Seconds = 90,
    [switch]$Headless,
    [ValidateSet('h264', 'hevc')][string]$Codec = 'h264',
    [int]$Bitrate = 120,
    [double]$Sharpness = 0.12,
    [int]$XboxBitrate = 50,
    [string]$ServerId = 'auto',
    [switch]$NoFg,
    # Analyse an existing telemetry file instead of starting a streamer (no console needed).
    [string]$FromLog,
    # With -FromLog, keep only samples where a headset was actually receiving.
    [switch]$ConnectedOnly,
    # With -FromLog, keep only the newest N matching samples (0 = all).
    [int]$MaxSamples = 0
)

$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Exe = Join-Path $Root 'build\GTA-Sixty.exe'
$Telemetry = Join-Path $Root 'build\perf-telemetry.log'
$OutDir = Join-Path $Root 'build\bench'

if (-not (Test-Path -LiteralPath $Exe)) { throw "Missing $Exe - run scripts\build.ps1 first." }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$slug = ($Label -replace '[^A-Za-z0-9._-]+', '-').Trim('-')
$base = Join-Path $OutDir "$stamp-$slug"
$runLog = "$base.run.log"
$errLog = "$base.run.err.log"

if ($FromLog) {
    if (-not (Test-Path -LiteralPath $FromLog)) { throw "Missing telemetry file $FromLog" }
    $Telemetry = (Resolve-Path -LiteralPath $FromLog).Path
    $offset = 0
    $cleanStop = $true
    $elapsed = 0.0
    $started = (Get-Item -LiteralPath $Telemetry).LastWriteTime
    $streamArgs = @("(analysed from log $Telemetry)")
    Write-Host "[bench] analysing existing telemetry: $Telemetry"
} else {
    # Two streamers cannot share 7660, and a leftover one would contaminate the capture.
    $portBusy = $false
    try {
        $probe = [System.Net.Sockets.TcpClient]::new()
        $portBusy = $probe.ConnectAsync('127.0.0.1', 7660).Wait(300)
        $probe.Dispose()
    } catch {}
    if ($portBusy) {
        throw "Something is already listening on TCP 7660. Stop the streamer first (Stop-Process -Name 'GTA-Sixty'), then re-run."
    }

    if (Test-Path -LiteralPath $Telemetry) { $offset = (Get-Item -LiteralPath $Telemetry).Length }

    $streamArgs = @(
        '--connect', $ServerId,
        '--quest-server',
        '--bitrate', "$Bitrate",
        '--sharpness', "$Sharpness",
        '--codec', $Codec,
        '--xbox-bitrate', "$XboxBitrate",
        '--run-seconds', "$Seconds"
    )
    if ($Headless) { $streamArgs += '--headless' }
    if ($NoFg) { $streamArgs += '--no-fg' }

    Write-Host "[bench] label=$Label  seconds=$Seconds  headless=$Headless  codec=$Codec  bitrate=$Bitrate"
    Write-Host "[bench] $Exe $($streamArgs -join ' ')"

    $started = Get-Date
    $proc = Start-Process -FilePath $Exe -ArgumentList $streamArgs -WorkingDirectory $Root -PassThru `
        -RedirectStandardOutput $runLog -RedirectStandardError $errLog -WindowStyle Hidden

    $deadline = $started.AddSeconds($Seconds + 240)
    while (-not $proc.HasExited -and (Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 500
        $proc.Refresh()
    }
    $cleanStop = $proc.HasExited
    if (-not $proc.HasExited) {
        Write-Warning "[bench] streamer did not stop within $($Seconds + 240)s; killing it (unclean teardown)"
        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    }
    $elapsed = [math]::Round(((Get-Date) - $started).TotalSeconds, 1)
}

# Read only the bytes written during this run.
$lines = @()
if (Test-Path -LiteralPath $Telemetry) {
    $fs = [System.IO.File]::Open($Telemetry, 'Open', 'Read', 'ReadWrite')
    try {
        if ($fs.Length -gt $offset) {
            $fs.Seek($offset, 'Begin') | Out-Null
            $reader = [System.IO.StreamReader]::new($fs)
            $lines = $reader.ReadToEnd() -split "`r?`n"
            $reader.Dispose()
        }
    } finally { $fs.Dispose() }
}

$mainPattern = '^in=(?<in>[\d.]+) \((?<xmbps>[\d.]+)M (?<w>\d+)x(?<h>\d+)\) uniq=(?<uniq>[\d.]+) out=(?<out>[\d.]+) fps \[(?<mode>[^\]]+)\] -> (?<ow>\d+)x(?<oh>\d+)@(?<ofps>\d+) \| pcLat=(?<pc>[\d.]+)ms dec=(?<dec>[\d.]+)ms decLag=(?<dl>[\d.]+)/(?<dlmax>[\d.]+)ms gpu=(?<gpu>[\d.]+)ms\(up=(?<up>[\d.]+),flow=(?<flow>[\d.]+),fg\+up=(?<fgu>[\d.]+)\) nvenc=(?<enc>[\d.]+)ms tcp=(?<tcp>[\d.]+)/(?<tcpmax>[\d.]+)ms \| out=(?<omb>[\d.]+) Mbps qDrop=(?<qd>\d+) tcpDrop=(?<td>\d+) qMax=(?<qm>\d+) rtt=(?<rtt>[\d.]+)ms'
$extraPattern = 'dup=(?<dup>[\d.]+)% runs=(?<r1>\d+)/(?<r2>\d+)/(?<r3>\d+) flips=(?<flips>\d+)'
$questPattern = 'quest=(?<qmin>[\d.]+)/(?<qavg>[\d.]+)ms'

$rows = [System.Collections.Generic.List[object]]::new()
$i = 0
foreach ($line in $lines) {
    $m = [regex]::Match($line, $mainPattern)
    if (-not $m.Success) { continue }
    $i++
    $e = [regex]::Match($line, $extraPattern)
    $q = [regex]::Match($line, $questPattern)
    $rows.Add([pscustomobject]@{
        t          = $i
        in         = [double]$m.Groups['in'].Value
        xboxMbps   = [double]$m.Groups['xmbps'].Value
        inW        = [int]$m.Groups['w'].Value
        inH        = [int]$m.Groups['h'].Value
        uniq       = [double]$m.Groups['uniq'].Value
        outFps     = [double]$m.Groups['out'].Value
        mode       = $m.Groups['mode'].Value
        outW       = [int]$m.Groups['ow'].Value
        outH       = [int]$m.Groups['oh'].Value
        pcLat      = [double]$m.Groups['pc'].Value
        dec        = [double]$m.Groups['dec'].Value
        decLagMax  = [double]$m.Groups['dlmax'].Value
        gpu        = [double]$m.Groups['gpu'].Value
        nvenc      = [double]$m.Groups['enc'].Value
        tcpMax     = [double]$m.Groups['tcpmax'].Value
        outMbps    = [double]$m.Groups['omb'].Value
        qDrop      = [int]$m.Groups['qd'].Value
        tcpDrop    = [int]$m.Groups['td'].Value
        qMax       = [int]$m.Groups['qm'].Value
        rtt        = [double]$m.Groups['rtt'].Value
        questAvg   = if ($q.Success) { [double]$q.Groups['qavg'].Value } else { 0.0 }
        dupPct     = if ($e.Success) { [double]$e.Groups['dup'].Value } else { -1.0 }
        run1       = if ($e.Success) { [int]$e.Groups['r1'].Value } else { -1 }
        run2       = if ($e.Success) { [int]$e.Groups['r2'].Value } else { -1 }
        run3       = if ($e.Success) { [int]$e.Groups['r3'].Value } else { -1 }
        flips      = if ($e.Success) { [int]$e.Groups['flips'].Value } else { 0 }
    })
}

if ($rows.Count -eq 0) {
    throw "No telemetry lines were written between offset $offset and EOF. See $errLog."
}
if ($ConnectedOnly) { $rows = @($rows | Where-Object { $_.outMbps -gt 0.001 }) }
if ($rows.Count -eq 0) { throw "No samples with a connected headset were found in $Telemetry." }
if ($FromLog -and $MaxSamples -gt 0 -and $rows.Count -gt $MaxSamples) {
    $rows = @($rows | Select-Object -Last $MaxSamples)
    $i = 0
    foreach ($r in $rows) { $i++; $r.t = $i }
}

$csv = "$base.csv"
$rows | Export-Csv -LiteralPath $csv -NoTypeInformation

function Measure-Series([double[]]$Values) {
    if ($null -eq $Values -or $Values.Count -eq 0) {
        return [ordered]@{ n = 0; mean = 0.0; min = 0.0; p5 = 0.0; p50 = 0.0; p95 = 0.0; max = 0.0 }
    }
    $s = @($Values | Sort-Object)
    $n = $s.Count
    $p = {
        param([double]$pct)
        $idx = [int][math]::Floor(($pct / 100.0) * ($n - 1))
        $idx = [math]::Max(0, [math]::Min($idx, $n - 1))
        [double]$s[$idx]
    }
    $sum = 0.0; foreach ($v in $s) { $sum += $v }
    [ordered]@{
        n    = $n
        mean = [math]::Round($sum / $n, 3)
        min  = [math]::Round([double]$s[0], 3)
        p5   = [math]::Round((& $p 5), 3)
        p50  = [math]::Round((& $p 50), 3)
        p95  = [math]::Round((& $p 95), 3)
        max  = [math]::Round([double]$s[$n - 1], 3)
    }
}

$all = $rows
$connected = @($rows | Where-Object { $_.outMbps -gt 0.001 })
$sourceOnly = ($connected.Count -eq 0)

# Cadence: on a 60 Hz source, a kept frame followed by exactly one repeat is 30 fps content.
$dupValues = @($rows | Where-Object { $_.dupPct -ge 0 } | ForEach-Object { $_.dupPct })
$dupAvailable = $dupValues.Count -gt 0
$runTotals = [ordered]@{ run0 = 0; run1 = 0; run2 = 0; run3 = 0 }
foreach ($r in $rows) {
    if ($r.run1 -lt 0) { continue }
    $runTotals.run0 += [math]::Max(0, [int][math]::Round($r.uniq * 2) - $r.run1 - $r.run2 - $r.run3)
    $runTotals.run1 += $r.run1
    $runTotals.run2 += $r.run2
    $runTotals.run3 += $r.run3
}

$modeShares = [ordered]@{}
foreach ($g in ($rows | Group-Object mode | Sort-Object Count -Descending)) {
    $modeShares[$g.Name] = [math]::Round(100.0 * $g.Count / $rows.Count, 1)
}

$nativeBelow50 = @($rows | Where-Object { $_.mode -notlike 'FG*' -and $_.uniq -lt 50 })
$deadband = @($rows | Where-Object { $_.mode -notlike 'FG*' -and $_.uniq -ge 34 -and $_.uniq -le 46 })
$sampleSeconds = [math]::Round($rows.Count * 2.0, 0)
$flipTotal = ($rows | Measure-Object -Property flips -Sum).Sum
$meanIn = ($rows | Measure-Object -Property in -Average).Average
$meanUniq = ($rows | Measure-Object -Property uniq -Average).Average
$contentRatio = if ($meanIn -gt 0) { [math]::Round(100.0 * $meanUniq / $meanIn, 1) } else { 0.0 }

$summary = [ordered]@{
    label       = $Label
    stamp       = $stamp
    started     = $started.ToString('o')
    wallSeconds = $elapsed
    cleanStop   = $cleanStop
    headless    = [bool]$Headless
    sourceOnly  = $sourceOnly
    args        = ($streamArgs -join ' ')
    codec       = $Codec
    bitrate     = $Bitrate
    samples     = $rows.Count
    sampleSecs  = $sampleSeconds
    dupAvailable = $dupAvailable
    metrics     = [ordered]@{
        in         = Measure-Series @($rows | ForEach-Object { $_.in })
        uniq       = Measure-Series @($rows | ForEach-Object { $_.uniq })
        outFps     = Measure-Series @($connected | ForEach-Object { $_.outFps })
        pcLat      = Measure-Series @($connected | ForEach-Object { $_.pcLat })
        dec        = Measure-Series @($rows | ForEach-Object { $_.dec })
        nvenc      = Measure-Series @($connected | ForEach-Object { $_.nvenc })
        gpu        = Measure-Series @($connected | ForEach-Object { $_.gpu })
        decLagMax  = Measure-Series @($rows | ForEach-Object { $_.decLagMax })
        rtt        = Measure-Series @($rows | ForEach-Object { $_.rtt })
        outMbps    = Measure-Series @($connected | ForEach-Object { $_.outMbps })
        xboxMbps   = Measure-Series @($rows | ForEach-Object { $_.xboxMbps })
        dupPct     = if ($dupAvailable) { Measure-Series @($dupValues) } else { [ordered]@{ n = 0; mean = $null; min = $null; p5 = $null; p50 = $null; p95 = $null; max = $null } }
        questMs    = Measure-Series @($connected | Where-Object { $_.questAvg -gt 0 } | ForEach-Object { $_.questAvg })
    }
    cadence     = [ordered]@{
        contentRatioPct = $contentRatio
        runs            = $runTotals
        modeSharesPct   = $modeShares
        modeFlips       = $flipTotal
        flipsPerMin     = [math]::Round(($flipTotal / [math]::Max(1, $sampleSeconds)) * 60.0, 2)
    }
    health      = [ordered]@{
        nativeBelow50Pct = [math]::Round(100.0 * $nativeBelow50.Count / $rows.Count, 1)
        deadbandPct      = [math]::Round(100.0 * $deadband.Count / $rows.Count, 1)
        # qDrop and tcpDrop are cumulative counters in the streamer, so the capture's delta is
        # last - first (not a sum, which would count the same early drops repeatedly).
        qDropTotal       = [math]::Max(0, [int]($rows[-1].qDrop - $rows[0].qDrop))
        tcpDropTotal     = [math]::Max(0, [int]($rows[-1].tcpDrop - $rows[0].tcpDrop))
        maxQMax          = ($rows | Measure-Object -Property qMax -Maximum).Maximum
        maxTcpMs         = ($rows | Measure-Object -Property tcpMax -Maximum).Maximum
        maxDecLagMs      = ($rows | Measure-Object -Property decLagMax -Maximum).Maximum
    }
    files       = [ordered]@{ csv = $csv; runLog = $runLog; errLog = $errLog }
}

$json = "$base.json"
$summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $json -Encoding utf8

$inv = [System.Globalization.CultureInfo]::InvariantCulture
function F([object]$v, [string]$fmt = '0.00') { ([double]$v).ToString($fmt, $inv) }

$md = [System.Collections.Generic.List[string]]::new()
$md.Add("# GTA-Sixty benchmark - $Label")
$md.Add('')
$md.Add("- captured: $($started.ToString('yyyy-MM-dd HH:mm:ss'))")
$md.Add("- window: $($rows.Count) samples (~$sampleSeconds s), wall $elapsed s, clean stop: $cleanStop")
if ($sourceOnly) { $md.Add('- **source-only capture**: no headset client was connected, so PC encode / Quest latency is not measured') }
$md.Add("- command: ``$($streamArgs -join ' ')``")
$md.Add('')
$md.Add('## Cadence (the part a console display-mode change can move)')
$md.Add('')
$md.Add('| metric | mean | p5 | p50 | p95 | max |')
$md.Add('|---|---|---|---|---|---|')
foreach ($k in 'in', 'uniq', 'dupPct', 'rtt', 'xboxMbps') {
    $s = $summary.metrics[$k]
    if ($k -eq 'dupPct' -and -not $dupAvailable) {
        $md.Add("| $k | - | - | - | - | - | (legacy line, no dup= field)")
        continue
    }
    $md.Add("| $k | $(F $s.mean) | $(F $s.p5) | $(F $s.p50) | $(F $s.p95) | $(F $s.max) |")
}
$md.Add('')
$md.Add("content ratio (uniq/in): **$($summary.cadence.contentRatioPct)%**  -  repeat runs (0/1/2/3+ drops per kept frame): $($runTotals.run0)/$($runTotals.run1)/$($runTotals.run2)/$($runTotals.run3)")
$md.Add("mode share: $((($modeShares.GetEnumerator() | ForEach-Object { "$($_.Key) $($_.Value)%" }) -join ', '))  -  flips: $flipTotal ($($summary.cadence.flipsPerMin)/min)")
$md.Add('')
$md.Add('## Output path')
$md.Add('')
$md.Add('| metric | mean | p5 | p50 | p95 | max |')
$md.Add('|---|---|---|---|---|---|')
foreach ($k in 'outFps', 'pcLat', 'dec', 'nvenc', 'gpu', 'outMbps', 'questMs') {
    $s = $summary.metrics[$k]
    if ($s.n -eq 0) { $md.Add("| $k | - | - | - | - | - |"); continue }
    $md.Add("| $k | $(F $s.mean) | $(F $s.p5) | $(F $s.p50) | $(F $s.p95) | $(F $s.max) |")
}
$md.Add('')
$md.Add('## Health')
$md.Add('')
$md.Add("- samples with native passthrough below 50 fps: **$($summary.health.nativeBelow50Pct)%**")
$md.Add("- samples stuck in the 34-46 fps FG dead band: **$($summary.health.deadbandPct)%**")
$md.Add("- qDrop total $($summary.health.qDropTotal), tcpDrop total $($summary.health.tcpDropTotal), qMax peak $($summary.health.maxQMax)")
$md.Add("- worst TCP send $($summary.health.maxTcpMs) ms, worst decLag $($summary.health.maxDecLagMs) ms")
$md.Add('')
$md.Add("Raw: ``$csv``")
$mdPath = "$base.md"
$md -join "`n" | Set-Content -LiteralPath $mdPath -Encoding utf8

Write-Host ''
Write-Host "[bench] json : $json"
Write-Host "[bench] md   : $mdPath"
Write-Host "[bench] csv  : $csv"
Write-Host "[bench] in/uniq: $(F $summary.metrics['in'].mean) / $(F $summary.metrics['uniq'].mean) fps   dup $(F $summary.metrics['dupPct'].mean)%   pcLat $(F $summary.metrics['pcLat'].mean) ms"
Write-Host ($md -join "`n")
