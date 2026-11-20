param(
    [int]$Port = 7660,
    [switch]$Launch
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Apk = Join-Path $Root "build\GTASixty-Quest3.apk"

if (-not (Test-Path -LiteralPath $Apk)) {
    Write-Host "[Quest Install] APK not found; building first..."
    & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "build-quest-apk.ps1")
}

$devs = (& adb devices) -join "`n"
if ($devs -notmatch '(?m)^\S+\s+device\s*$') {
    throw "No authorized Quest 3 connected via ADB. Plug in USB-C and accept the USB debugging prompt inside the headset."
}

Write-Host "[Quest Install] Installing $Apk..."
& adb install -r -g $Apk
if ($LASTEXITCODE -ne 0) { throw "adb install failed" }

Write-Host "[Quest Install] Setting up USB-C tunnel (adb reverse tcp:$Port tcp:$Port)..."
& adb reverse ("tcp:{0}" -f $Port) ("tcp:{0}" -f $Port)

if ($Launch) {
    Write-Host "[Quest Install] Launching GTASixty on Quest 3..."
    & adb shell am start -n "com.gtasixty.quest/.MainActivity"
}

Write-Host "[Quest Install] Done! Open Unknown Sources -> GTASixty on Quest 3 (use 127.0.0.1 over USB-C, or tap LAN over Wi-Fi)."
