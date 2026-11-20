param(
    [int]$Port = 7660,
    [switch]$Launch,
    # Target a specific device/emulator. Only needed when more than one is attached; matching ADB
    # targets by "the one that happens to be plugged in" breaks as soon as an emulator is running.
    [string]$Serial = ""
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Apk = Join-Path $Root "build\GTA-Sixty-Quest3.apk"

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

Write-Host "[Quest Install] Installing $Apk..."
& adb -s $Serial install -r -g $Apk
if ($LASTEXITCODE -ne 0) { throw "adb install failed" }

Write-Host "[Quest Install] Setting up USB-C tunnel (adb reverse tcp:$Port tcp:$Port)..."
& adb -s $Serial reverse ("tcp:{0}" -f $Port) ("tcp:{0}" -f $Port)

if ($Launch) {
    Write-Host "[Quest Install] Launching GTA-Sixty on Quest 3..."
    & adb -s $Serial shell am start -n "com.gtasixty.quest/.MainActivity"
}

Write-Host "[Quest Install] Done! Open Unknown Sources -> GTA-Sixty on Quest 3 (use 127.0.0.1 over USB-C, or tap LAN over Wi-Fi)."
