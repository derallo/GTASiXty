param(
    [switch]$Clean
)

# Builds quest-spatial-app/src/main/jniLibs/arm64-v8a/libgtahandtracker.so from
# quest-spatial-app/src/main/cpp/hand_tracker_jni.cpp using the Android NDK clang.
#
# The Metal/Spatial Gradle build has no externalNativeBuild on purpose: this one small library is
# compiled by this script and dropped into jniLibs, so `gradle assembleDebug` stays a plain Java/Kotlin
# build. SpatialMainActivity degrades gracefully (SDK body-tracking fallback path) when the .so is
# missing, so forgetting to run this script never breaks the APK.
#
# Usage:
#   pwsh -File scripts\build-hand-tracker.ps1

$ErrorActionPreference = "Stop"

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$AppDir = Join-Path $Root "quest-spatial-app"
$CppDir = Join-Path $AppDir "src\main\cpp"
$Src = Join-Path $CppDir "hand_tracker_jni.cpp"
$Header = Join-Path $CppDir "openxr.h"
$OutDir = Join-Path $AppDir "src\main\jniLibs\arm64-v8a"
$OutSo = Join-Path $OutDir "libgtahandtracker.so"

if (-not (Test-Path -LiteralPath $Src)) { throw "Missing source: $Src" }
if (-not (Test-Path -LiteralPath $Header)) {
    throw "Missing OpenXR header: $Header (download from KhronosGroup/OpenXR-SDK include/openxr/openxr.h)"
}

function Find-Ndk {
    foreach ($env in @($env:ANDROID_NDK_HOME, $env:ANDROID_NDK_ROOT)) {
        if ($env -and (Test-Path -LiteralPath $env)) { return (Resolve-Path $env).Path }
    }
    $sdkRoots = @()
    if ($env:ANDROID_HOME) { $sdkRoots += $env:ANDROID_HOME }
    if ($env:ANDROID_SDK_ROOT) { $sdkRoots += $env:ANDROID_SDK_ROOT }
    $sdkRoots += (Join-Path $env:LOCALAPPDATA "Android\Sdk")
    foreach ($sdk in $sdkRoots) {
        $ndkRoot = Join-Path $sdk "ndk"
        if (-not (Test-Path -LiteralPath $ndkRoot)) { continue }
        $candidate = Get-ChildItem $ndkRoot -Directory |
            Where-Object { Test-Path (Join-Path $_.FullName "toolchains\llvm\prebuilt\windows-x86_64\bin") } |
            Sort-Object Name -Descending |
            Select-Object -First 1
        if ($candidate) { return $candidate.FullName }
    }
    return $null
}

$Ndk = Find-Ndk
if (-not $Ndk) {
    throw "Android NDK not found. Install it (SDK Manager -> NDK, or set ANDROID_NDK_HOME)."
}

$BinDir = Join-Path $Ndk "toolchains\llvm\prebuilt\windows-x86_64\bin"
$Clang = Join-Path $BinDir "aarch64-linux-android29-clang++.cmd"
$Strip = Join-Path $BinDir "llvm-strip.exe"
if (-not (Test-Path -LiteralPath $Clang)) { throw "NDK clang++ not found at $Clang" }

if ($Clean -and (Test-Path -LiteralPath $OutSo)) { Remove-Item -Force $OutSo }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

Write-Host "[GTA Hands] NDK: $Ndk"
Write-Host "[GTA Hands] Compiling hand_tracker_jni.cpp -> $OutSo"

# -static-libstdc++ is REQUIRED: the APK does not ship libc++_shared.so, so a dynamically linked
# libc++ makes dlopen() fail with "library libc++_shared.so not found".
# -llog resolves __android_log_print at link time; --no-undefined proves nothing is left dangling.
$args = @(
    "-shared", "-fPIC", "-O2", "-std=c++17",
    "-fno-exceptions", "-fno-rtti", "-fvisibility=hidden",
    "-static-libstdc++", "-static-libgcc",
    "-Wall", "-Wextra", "-Wno-unused-parameter",
    "-Wl,-z,max-page-size=16384",
    "-Wl,--no-undefined",
    "-Wl,-soname,libgtahandtracker.so",
    "-I", $CppDir,
    "-o", $OutSo,
    $Src,
    "-llog"
)
& $Clang @args
if ($LASTEXITCODE -ne 0) { throw "clang++ failed with exit code $LASTEXITCODE" }

if (Test-Path -LiteralPath $Strip) { & $Strip --strip-unneeded $OutSo | Out-Null }

$info = Get-Item -LiteralPath $OutSo
Write-Host "[GTA Hands] Built $($info.FullName) ($([math]::Round($info.Length / 1024.0, 1)) KiB)"

# Symbol sanity check: the JNI entry points must be exported.
$strings = & (Join-Path $BinDir "llvm-nm.exe") -D --defined-only $OutSo 2>$null
$missing = @()
foreach ($fn in @(
    "Java_com_gtasixty_spatial_HandTrackerJni_nativeInit",
    "Java_com_gtasixty_spatial_HandTrackerJni_nativeLocate",
    "Java_com_gtasixty_spatial_HandTrackerJni_nativeShutdown",
    "Java_com_gtasixty_spatial_HandTrackerJni_nativeIsReady"
)) {
    if (-not ($strings | Select-String -SimpleMatch $fn -Quiet)) { $missing += $fn }
}
if ($missing.Count -gt 0) {
    throw "Missing exported JNI symbols: $($missing -join ', ')"
}
Write-Host "[GTA Hands] Exported JNI symbols OK"
Write-Host "[GTA Hands] Success"
