param(
    [int]$Port = 7660,
    [switch]$Launch,
    [string]$Serial = "",
    [string[]]$ExtraArgs = @()
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Apk = Join-Path $Root "build\GTA-Sixty-Spatial-Quest3.apk"
$Pkg = "com.gtasixty.spatial"

if (-not (Test-Path -LiteralPath $Apk)) {
    Write-Host "[Spatial Install] APK not found; building first..."
    & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "build-quest-spatial-apk.ps1")
}

$online = @([regex]::Matches(((& adb devices) -join "`n"), '(?m)^(\S+)\s+device\s*$') |
    ForEach-Object { $_.Groups[1].Value })
if ($online.Count -eq 0) {
    throw "No authorized Quest 3 connected via ADB."
}
if (-not $Serial) {
    if ($online.Count -gt 1) {
        throw "More than one ADB device is connected ($($online -join ', ')). Re-run with -Serial <serial>."
    }
    $Serial = $online[0]
}
Write-Host "[Spatial Install] Target device: $Serial"

$runningPid = ((& adb -s $Serial shell "pidof $Pkg" 2>$null) -join "").Trim()
if ($runningPid) {
    Write-Host "[Spatial Install] Stopping any active spatial stream cleanly before upgrade..."
    & adb -s $Serial shell "am broadcast -a $Pkg.STOP_STREAM -p $Pkg" | Out-Null
    Start-Sleep -Milliseconds 1500
}

Write-Host "[Spatial Install] Installing $Apk alongside com.gtasixty.quest..."
& adb -s $Serial install -r -g $Apk
if ($LASTEXITCODE -ne 0) { throw "adb install failed" }

Write-Host "[Spatial Install] Setting up USB-C tunnel (adb reverse tcp:$Port tcp:$Port)..."
& adb -s $Serial reverse ("tcp:{0}" -f $Port) ("tcp:{0}" -f $Port)

Write-Host "[Spatial Install] Configuring performance clocks (GPU/CPU level 4)..."
& adb -s $Serial shell "setprop debug.oculus.refreshRate 120; setprop debug.oculus.gpuLevel 4; setprop debug.oculus.cpuLevel 4" 2>$null

if ($Launch) {
    Write-Host "[Spatial Install] Launching GTA-Sixty Spatial on Quest 3..."
    $startArgs = @("-s", $Serial, "shell", "am", "start", "-n", "$Pkg/.SpatialMainActivity")
    if ($ExtraArgs.Count -gt 0) {
        $startArgs += $ExtraArgs
    }
    & adb @startArgs
}

Write-Host "[Spatial Install] Done! Open Unknown Sources -> GTA-Sixty Spatial on Quest 3."
