param(
    [int]$Port = 7660,
    [switch]$Launch,
    [string]$Serial = "",
    [string[]]$ExtraArgs = @(),
    [switch]$KeepLegacy
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Apk = Join-Path $Root "build\GTA-Sixty-Quest3.apk"
# Keep package ID com.gtasixty.spatial so existing GTA-Sixty Spatial settings & tokens migrate cleanly.
$Pkg = "com.gtasixty.spatial"
$LegacyPkg = "com.gtasixty.quest"

if (-not (Test-Path -LiteralPath $Apk)) {
    Write-Host "[Quest Install] APK not found; building first..."
    & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "build-quest-apk.ps1")
}

$online = @([regex]::Matches(((& adb devices) -join "`n"), '(?m)^(\S+)\s+device\s*$') |
    ForEach-Object { $_.Groups[1].Value })
if ($online.Count -eq 0) {
    throw "No authorized Quest 3 connected via ADB. Plug in USB-C (or 'adb connect <quest-ip>:5555') and accept the USB debugging prompt inside the headset."
}
if (-not $Serial) {
    if ($online.Count -gt 1) {
        throw "More than one ADB device is connected ($($online -join ', ')). Re-run with -Serial <serial>."
    }
    $Serial = $online[0]
}
Write-Host "[Quest Install] Target device: $Serial"

if (-not $KeepLegacy) {
    $legacyInstalled = ((& adb -s $Serial shell "pm list packages $LegacyPkg" 2>$null) -join "").Trim()
    if ($legacyInstalled -match [regex]::Escape($LegacyPkg)) {
        Write-Host "[Quest Install] Removing legacy flat app ($LegacyPkg)..."
        & adb -s $Serial shell "am force-stop $LegacyPkg" 2>$null | Out-Null
        & adb -s $Serial uninstall $LegacyPkg | Out-Null
    }
}

$runningPid = ((& adb -s $Serial shell "pidof $Pkg" 2>$null) -join "").Trim()
if ($runningPid) {
    Write-Host "[Quest Install] Stopping any active GTA-Sixty stream cleanly before upgrade..."
    & adb -s $Serial shell "am broadcast -a $Pkg.STOP_STREAM -p $Pkg" | Out-Null
    Start-Sleep -Milliseconds 1500
}

Write-Host "[Quest Install] Installing $Apk (retaining existing settings)..."
& adb -s $Serial install -r -g $Apk
if ($LASTEXITCODE -ne 0) { throw "adb install failed" }

Write-Host "[Quest Install] Setting up USB-C tunnel (adb reverse tcp:$Port tcp:$Port)..."
& adb -s $Serial reverse ("tcp:{0}" -f $Port) ("tcp:{0}" -f $Port)

Write-Host "[Quest Install] Configuring 120 Hz display & performance clocks (GPU/CPU level 4)..."
& adb -s $Serial shell "setprop debug.oculus.refreshRate 120; setprop debug.oculus.gpuLevel 4; setprop debug.oculus.cpuLevel 4" 2>$null

if ($Launch) {
    Write-Host "[Quest Install] Launching GTA-Sixty on Quest 3..."
    $startArgs = @("-s", $Serial, "shell", "am", "start", "-n", "$Pkg/.SpatialMainActivity")
    if ($ExtraArgs.Count -gt 0) {
        $startArgs += $ExtraArgs
    }
    & adb @startArgs
}

Write-Host "[Quest Install] Done! Open Unknown Sources -> GTA-Sixty on Quest 3."
