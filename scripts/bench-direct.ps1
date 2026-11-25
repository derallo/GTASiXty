<#
.SYNOPSIS
  Automated Direct-Mode A/B benchmark across Quest 3 GPU enhancement modes.

.DESCRIPTION
  Cycles through Direct Mode GPU enhancement modes (0=Off, 1=RCAS, 2=SuperRes, 3=FG60, 4=FG60+SR)
  on a connected Quest 3, capturing GTA60Direct stream/GPU telemetry, VrApi GPU/clock/temp stats,
  SurfaceFlinger nanosecond presentation intervals, and Android gfxinfo jank counters.

  Outputs CSV + Markdown summaries to build/bench/<stamp>-<label>.{csv,md}.
#>
param(
    [string]$Serial = "",
    [int]$SecondsPerMode = 15,
    [string]$Modes = "0,1,2,3,4",
    [int]$Look = 0,
    [string]$Label = "direct-ab"
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$OutDir = Join-Path $Root "build\bench"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Resolve-QuestSerial([string]$Requested) {
    if ($Requested) {
        if ($Requested -match '^\d+\.\d+\.\d+\.\d+:\d+$') {
            & adb connect $Requested | Out-Null
            Start-Sleep -Milliseconds 400
        }
        return $Requested
    }
    $devLines = @(& adb devices -l)
    foreach ($line in $devLines) {
        if ($line -match '^(\S+)\s+device\b' -and $line -match 'Quest') {
            return $Matches[1]
        }
    }
    foreach ($line in $devLines) {
        if ($line -match '^(\S+)\s+device\b') {
            $cand = $Matches[1]
            $model = (& adb -s $cand shell getprop ro.product.model 2>$null).Trim()
            if ($model -match 'Quest') { return $cand }
        }
    }
    throw "No Quest 3 found via ADB. Connect USB-C or run 'adb connect <quest-ip>:5555' first."
}

$Serial = Resolve-QuestSerial $Serial
$ModeList = @($Modes -split '[,\s@()]+' | Where-Object { $_ -match '^\d+$' } | ForEach-Object { [int]$_ })
if ($ModeList.Count -eq 0) { throw "No valid numeric mode IDs found in -Modes '$Modes'" }
Write-Host "[bench-direct] Target Quest 3: $Serial | Modes=$($ModeList -join ',') | Look=$Look | Window=${SecondsPerMode}s"

$ModeNames = @{
    0 = 'Off'
    1 = 'RCAS'
    2 = 'SuperRes'
    3 = 'FG60'
    4 = 'FG60+SR'
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$slug = ($Label -replace '[^A-Za-z0-9._-]+', '-').Trim('-')
$base = Join-Path $OutDir "$stamp-$slug"
$csvPath = "$base.csv"
$mdPath = "$base.md"

$results = @()

try {
foreach ($m in $ModeList) {
    $mName = if ($ModeNames.ContainsKey($m)) { $ModeNames[$m] } else { "Mode$m" }
    Write-Host "`n[bench-direct] === Testing enhance=$m ($mName) look=$Look ==="

    & adb -s $Serial shell "am force-stop com.gtasixty.spatial" | Out-Null
    $preState = ((& adb -s $Serial shell "dumpsys vrpowermanager | grep 'State:' | head -n 1") -join '').Trim()
    if ($preState -match 'HEADSET_UNMOUNTED') {
        & adb -s $Serial shell "input keyevent KEYCODE_SLEEP; sleep 1" | Out-Null
    }
    & adb -s $Serial logcat -c
    & adb -s $Serial shell "input keyevent KEYCODE_WAKEUP; cmd display set-user-preferred-display-mode 4128 2208 120.00001 0; setprop debug.oculus.refreshRate 120; setprop debug.oculus.gpuLevel 4; setprop debug.oculus.cpuLevel 4; am start -n com.gtasixty.spatial/.SpatialMainActivity --ez direct true --ez directStart true --ei enhance $m --ei look $Look" | Out-Null

    # Wait for streaming to start (up to 25 s)
    $readyAt = $null
    for ($w = 0; $w -lt 25; $w++) {
        Start-Sleep -Seconds 1
        if ($w -eq 15) {
            & adb -s $Serial shell "input keyevent KEYCODE_WAKEUP" | Out-Null
        }
        $checkLog = (& adb -s $Serial logcat -d -s GTA60Direct:V) -join "`n"
        if ($checkLog -match 'js: direct: streaming') {
            $readyAt = Get-Date
            break
        }
    }
    if (-not $readyAt) {
        Write-Warning "[bench-direct] Mode $mName did not reach streaming within 25s; capturing available logs."
    } else {
        # Allow first rendered frame(s) to initialize WebGL2 pipeline before checking startup logs
        Start-Sleep -Seconds 2
        $initLog = (& adb -s $Serial logcat -d -s GTA60Direct:V) -join "`n"
        if ($m -gt 0 -and $initLog -notmatch 'WebGL2 Adreno GPU pipeline v3 initialized') {
            Write-Warning "[bench-direct] Mode ${mName}: expected 'WebGL2 Adreno GPU pipeline v3 initialized' in init log"
        }
        if ($initLog -match '(?i)(webgl error|shader.*fail|compileShader.*fail|linkProgram.*fail)') {
            Write-Warning "[bench-direct] Mode ${mName}: potential WebGL/shader error in init log"
        }

        $vrState = ((& adb -s $Serial shell "dumpsys vrpowermanager | grep 'State:' | head -n 1") -join '').Trim()
        if ($vrState -notmatch 'HEADSET_MOUNTED') {
            if ($vrState -match 'STANDBY') {
                & adb -s $Serial shell "input keyevent KEYCODE_WAKEUP" | Out-Null
            } else {
                # Reset Horizon OS's 15s unmounted AutoSleepTime once before capture
                & adb -s $Serial shell "input keyevent KEYCODE_SLEEP; sleep 1; input keyevent KEYCODE_WAKEUP" | Out-Null
            }
            $capSec = [math]::Min($SecondsPerMode, 10)
            Write-Host "[bench-direct] Unmounted headset detected ($vrState); reset 15s timer & capped capture to ${capSec}s"
            Start-Sleep -Seconds 2
        } else {
            $capSec = $SecondsPerMode
        }

        # Clear logcat after settle, then capture uninterrupted steady state
        & adb -s $Serial logcat -c
        Start-Sleep -Seconds $capSec
    }

    # Capture SurfaceFlinger latency + gfxinfo while still active
    $sfLayerRaw = & adb -s $Serial shell "dumpsys SurfaceFlinger --list | grep -E 'com\.gtasixty\.spatial/com\.gtasixty\.spatial\.(MainActivity|SpatialMainActivity)#' | tail -n 1"
    $sfLayer = ($sfLayerRaw | Out-String).Trim()
    $sfFps = 0.0; $sfP50 = 0.0; $sfP95 = 0.0; $sfLe17 = 0.0
    if ($sfLayer) {
        $sfDump = & adb -s $Serial shell ("dumpsys SurfaceFlinger --latency '{0}'" -f $sfLayer)
        $sfLines = ($sfDump -split "`r?`n") | Where-Object { $_ -match '^\d+\s+\d+\s+\d+' }
        $prevTs = [int64]0
        $deltas = @()
        foreach ($sl in $sfLines) {
            $cols = $sl.Trim() -split '\s+'
            if ($cols.Count -ge 2) {
                $ts = [int64]$cols[1]
                if ($prevTs -gt 0 -and $ts -gt $prevTs) {
                    $dMs = ($ts - $prevTs) / 1e6
                    if ($dMs -lt 250.0) { $deltas += $dMs }
                }
                $prevTs = $ts
            }
        }
        if ($deltas.Count -gt 3) {
            $sortedD = $deltas | Sort-Object
            $sumS = ($deltas | Measure-Object -Sum).Sum / 1000.0
            if ($sumS -gt 0) { $sfFps = [math]::Round($deltas.Count / $sumS, 1) }
            $sfP50 = [math]::Round($sortedD[[Math]::Min($sortedD.Count - 1, [int][Math]::Floor($sortedD.Count * 0.50))], 2)
            $sfP95 = [math]::Round($sortedD[[Math]::Min($sortedD.Count - 1, [int][Math]::Floor($sortedD.Count * 0.95))], 2)
            $le17Count = @($deltas | Where-Object { $_ -le 17.5 }).Count
            $sfLe17 = [math]::Round(($le17Count * 100.0) / $deltas.Count, 1)
        }
    }

    $gfxText = (& adb -s $Serial shell "dumpsys gfxinfo com.gtasixty.spatial") -join "`n"
    $jankPct = "n/a"; $gfxP95 = "n/a"; $hiInput = 0
    if ($gfxText -match 'Janky frames:\s+\d+\s+\(([\d\.]+)%\)') { $jankPct = $Matches[1] }
    if ($gfxText -match '95th percentile:\s+(\d+ms)') { $gfxP95 = $Matches[1] }
    if ($gfxText -match 'Number High input latency:\s+(\d+)') { $hiInput = [int]$Matches[1] }

    $rawLog = & adb -s $Serial logcat -d -v time -s GTA60Direct:V GTA60:V VrApi:V
    $rawLog | Set-Content ("$base-mode$m-$mName.log")

    $perfLines = @($rawLog | Where-Object { $_ -match 'js: perf:' })
    # Skip initial perf sample if enough samples exist
    if ($perfLines.Count -gt 5) {
        $perfLines = $perfLines[1..($perfLines.Count - 1)]
    }

    $inList = @(); $uniqList = @(); $outList = @(); $fgList = @()
    $gpuList = @(); $decList = @(); $jbList = @(); $mbpsList = @(); $rttList = @()
    $pboSum = 0; $lostMax = 0; $dropMax = 0; $dupList = @()

    foreach ($pl in $perfLines) {
        if ($pl -match 'perf:\s+\S+\s+([\d\.]+)fps\s+uniq=([\d\.]+)fps\s+out=([\d\.]+)fps\(fg=([\d\.]+),dup=(\d+)%,mad=[\d\.]+,(?:pbo=(\d+),)?gpu=([\d\.]+)ms,mode=[^)]+\)\s+([\d\.]+)Mbps\s+dec=([\d\.]+)ms\s+jb=([\d\.]+)ms\([^\)]+\)\s+rtt=([\d\.]+)ms\s+lost=(\d+)\s+drop=(\d+)') {
            $inList += [double]$Matches[1]
            $uniqList += [double]$Matches[2]
            $outList += [double]$Matches[3]
            $fgList += [double]$Matches[4]
            $dupList += [double]$Matches[5]
            if ($Matches[6]) { $pboSum += [int]$Matches[6] }
            $gpuList += [double]$Matches[7]
            $mbpsList += [double]$Matches[8]
            $decList += [double]$Matches[9]
            $jbList += [double]$Matches[10]
            $rttList += [double]$Matches[11]
            $lostMax = [math]::Max($lostMax, [int]$Matches[12])
            $dropMax = [math]::Max($dropMax, [int]$Matches[13])
        }
    }

    $vrLines = @($rawLog | Where-Object { $_ -match 'I/VrApi\s*\(' -and $_ -match 'GPU%=' })
    $vrGpuPct = @(); $vrGpuMhz = @(); $vrTemp = @(); $vrStale = 0
    foreach ($vl in $vrLines) {
        if ($vl -match 'Stale=(\d+),') { $vrStale += [int]$Matches[1] }
        if ($vl -match ',\d+/(\d+)MHz,') {
            $mhz = [int]$Matches[1]
            if ($mhz -gt 0) { $vrGpuMhz += $mhz }
        }
        if ($vl -match 'Temp=([\d\.]+)C/') { $vrTemp += [double]$Matches[1] }
        if ($vl -match 'GPU%=([\d\.]+),') { $vrGpuPct += [double]$Matches[1] }
    }

    function Avg($arr, [int]$digits = 2) {
        if ($arr.Count -eq 0) { return 0.0 }
        return [math]::Round(($arr | Measure-Object -Average).Average, $digits)
    }

    $row = [PSCustomObject]@{
        Mode         = $m
        Name         = $mName
        Samples      = $perfLines.Count
        InFps        = Avg $inList 1
        UniqFps      = Avg $uniqList 1
        OutFps       = Avg $outList 1
        FgFps        = Avg $fgList 1
        DupPct       = Avg $dupList 0
        PboPct       = if ($perfLines.Count -gt 0) { [math]::Round(($pboSum * 100.0) / $perfLines.Count, 0) } else { 0 }
        GpuMs        = Avg $gpuList 2
        DecMs        = Avg $decList 2
        JbMs         = Avg $jbList 1
        Mbps         = Avg $mbpsList 1
        RttMs        = Avg $rttList 1
        Lost         = $lostMax
        Drop         = $dropMax
        SfFps        = $sfFps
        SfP50Ms      = $sfP50
        SfP95Ms      = $sfP95
        SfLe17Pct    = $sfLe17
        VrGpuPct     = if ($vrGpuPct.Count -gt 0) { Avg $vrGpuPct 2 } else { 'n/a' }
        VrGpuMhz     = if ($vrGpuMhz.Count -gt 0) { Avg $vrGpuMhz 0 } else { 0 }
        VrTempC      = if ($vrTemp.Count -gt 0) { Avg $vrTemp 1 } else { 'n/a' }
        VrStale      = $vrStale
        JankPct      = $jankPct
        GfxP95       = $gfxP95
        HighInLat    = $hiInput
    }
    $results += $row
    $vrStr = if ($row.VrGpuPct -eq 'n/a') { 'n/a' } else { "$($row.VrGpuPct)@$($row.VrGpuMhz)MHz" }
    Write-Host ("[bench-direct] {0,-8} n={1} uniq={2} out={3} fg={4} gpu={5}ms dec={6}ms jb={7}ms sfFps={8} VrGpu={9}" -f `
        $row.Name, $row.Samples, $row.UniqFps, $row.OutFps, $row.FgFps, $row.GpuMs, $row.DecMs, $row.JbMs, $row.SfFps, $vrStr)
}
} finally {
    # Leave app cleanly stopped after benchmark
    & adb -s $Serial shell "am force-stop com.gtasixty.spatial" | Out-Null
}

$results | Export-Csv -NoTypeInformation -Path $csvPath

$md = New-Object System.Collections.Generic.List[string]
$md.Add("# Direct Mode Benchmark ($stamp / $Label)")
$md.Add("")
$md.Add("- Device: $Serial | Look: $Look | Window per mode: ${SecondsPerMode}s")
$md.Add("")
$md.Add("| Mode | n | In FPS | Uniq FPS | Out FPS | FG FPS | Dup% | PBO% | GPU (ms) | Dec (ms) | JB (ms) | Mbps | SF FPS | SF p50/p95 (ms) | VrApi GPU | Temp (C) | Drop/Lost |")
$md.Add("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---:|---:|---|")
foreach ($r in $results) {
    $vrCell = if ($r.VrGpuPct -eq 'n/a') { 'n/a' } else { "$($r.VrGpuPct) @ $($r.VrGpuMhz)MHz" }
    $md.Add("| $($r.Name) | $($r.Samples) | $($r.InFps) | $($r.UniqFps) | $($r.OutFps) | $($r.FgFps) | $($r.DupPct)% | $($r.PboPct)% | $($r.GpuMs) | $($r.DecMs) | $($r.JbMs) | $($r.Mbps) | $($r.SfFps) | $($r.SfP50Ms) / $($r.SfP95Ms) | $vrCell | $($r.VrTempC) | $($r.Drop)/$($r.Lost) |")
}
$md -join "`r`n" | Set-Content -Path $mdPath

Write-Host "`n[bench-direct] Reports written:"
Write-Host "  CSV: $csvPath"
Write-Host "  MD : $mdPath"
Write-Host ""
Get-Content $mdPath
