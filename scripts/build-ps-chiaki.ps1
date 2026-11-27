param(
    [switch]$Clean
)

$ErrorActionPreference = "Stop"

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$AppDir = Join-Path $Root "quest-spatial-app"
$CppPsDir = Join-Path $AppDir "src\main\cpp\ps"
$ThirdParty = Join-Path $Root "third_party"
$ChiakiDir = Join-Path $ThirdParty "chiaki-ng"
$BuildDir = Join-Path $ThirdParty "chiaki-build\arm64-v8a"
$ProtocDir = Join-Path $ThirdParty "protoc"
$OutDir = Join-Path $AppDir "src\main\jniLibs\arm64-v8a"
$OutSo = Join-Path $OutDir "libgtaps.so"

function Find-Ndk {
    foreach ($envVal in @($env:ANDROID_NDK_HOME, $env:ANDROID_NDK_ROOT)) {
        if ($envVal -and (Test-Path -LiteralPath $envVal)) { return (Resolve-Path $envVal).Path }
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

function Find-CMake {
    $cmd = Get-Command cmake -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $candidates = @(
        "C:\Program Files\CMake\bin\cmake.exe",
        "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe",
        "C:\Program Files (x86)\Microsoft Visual Studio\2019\BuildTools\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe"
    )
    foreach ($p in $candidates) {
        if (Test-Path -LiteralPath $p) { return $p }
    }
    return $null
}

function Find-Ninja {
    $cmd = Get-Command ninja -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $candidates = @(
        (Join-Path $env:APPDATA "Python\Python312\Scripts\ninja.exe"),
        "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\Common7\IDE\CommonExtensions\Microsoft\CMake\Ninja\ninja.exe"
    )
    foreach ($p in $candidates) {
        if (Test-Path -LiteralPath $p) { return $p }
    }
    return $null
}

function Find-Python {
    foreach ($name in @("python", "python3")) {
        $cmd = Get-Command $name -ErrorAction SilentlyContinue
        if ($cmd -and $cmd.Source -notlike "*WindowsApps*") { return $cmd.Source }
    }
    foreach ($p in @("C:\Python312\python.exe", "C:\Users\$env:USERNAME\AppData\Local\Programs\Python\Python311\python.exe")) {
        if (Test-Path -LiteralPath $p) { return $p }
    }
    return $null
}

function Ensure-Protoc {
    $cmd = Get-Command protoc -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $localProtoc = Join-Path $ProtocDir "bin\protoc.exe"
    if (Test-Path -LiteralPath $localProtoc) { return $localProtoc }

    New-Item -ItemType Directory -Force -Path $ProtocDir | Out-Null
    $zipPath = Join-Path $ProtocDir "protoc-27.3-win64.zip"
    $url = "https://github.com/protocolbuffers/protobuf/releases/download/v27.3/protoc-27.3-win64.zip"
    Write-Host "[PS Chiaki] Downloading protoc 27.3..."
    Invoke-WebRequest -Uri $url -OutFile $zipPath -UseBasicParsing
    Expand-Archive -Path $zipPath -DestinationPath $ProtocDir -Force
    Remove-Item -Force $zipPath -ErrorAction SilentlyContinue
    if (-not (Test-Path -LiteralPath $localProtoc)) {
        throw "Failed to extract protoc.exe to $localProtoc"
    }
    return $localProtoc
}

function Ensure-ChiakiNg {
    if (-not (Test-Path -LiteralPath (Join-Path $ChiakiDir "lib\CMakeLists.txt"))) {
        New-Item -ItemType Directory -Force -Path $ThirdParty | Out-Null
        Write-Host "[PS Chiaki] Cloning streetpea/chiaki-ng..."
        & git clone --depth 1 https://github.com/streetpea/chiaki-ng.git $ChiakiDir
        if ($LASTEXITCODE -ne 0) { throw "git clone chiaki-ng failed" }
    }
    $neededSubmodules = @(
        "third-party/nanopb",
        "third-party/jerasure",
        "third-party/gf-complete",
        "third-party/curl"
    )
    $missingSub = $false
    foreach ($sub in $neededSubmodules) {
        $p = Join-Path $ChiakiDir $sub
        if (-not (Test-Path -LiteralPath $p) -or ((Get-ChildItem -Force -ErrorAction SilentlyContinue $p | Measure-Object).Count -eq 0)) {
            $missingSub = $true
            break
        }
    }
    if ($missingSub) {
        Write-Host "[PS Chiaki] Initializing chiaki-ng submodules (nanopb, jerasure, gf-complete, curl)..."
        & git -C $ChiakiDir submodule update --init --depth 1 -- @neededSubmodules
        if ($LASTEXITCODE -ne 0) { throw "git submodule update failed" }
    }
}

$Ndk = Find-Ndk
if (-not $Ndk) { throw "Android NDK not found" }
$CMake = Find-CMake
if (-not $CMake) { throw "CMake not found" }
$Ninja = Find-Ninja
if (-not $Ninja) { throw "Ninja not found" }
$Python = Find-Python
if (-not $Python) { throw "Python 3 not found" }
$Protoc = Ensure-Protoc
Ensure-ChiakiNg

$BinDir = Join-Path $Ndk "toolchains\llvm\prebuilt\windows-x86_64\bin"
$Strip = Join-Path $BinDir "llvm-strip.exe"
$Nm = Join-Path $BinDir "llvm-nm.exe"
$ReadElf = Join-Path $BinDir "llvm-readelf.exe"
$Toolchain = Join-Path $Ndk "build\cmake\android.toolchain.cmake"

if ($Clean -and (Test-Path -LiteralPath $BuildDir)) {
    Remove-Item -Recurse -Force $BuildDir
}
New-Item -ItemType Directory -Force -Path $BuildDir | Out-Null
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# Ensure protoc is on PATH for nanopb_generator.py
$protocBinDir = Split-Path -Parent $Protoc
$env:PATH = "$protocBinDir;$env:PATH"

$ninjaFile = Join-Path $BuildDir "build.ninja"
$cacheFile = Join-Path $BuildDir "CMakeCache.txt"
$needConfigure = $Clean -or -not (Test-Path -LiteralPath $ninjaFile) -or (
    (Test-Path -LiteralPath $cacheFile) -and (Select-String -Path $cacheFile -SimpleMatch "PROTOC:UNINITIALIZED=protoc" -Quiet) -eq $false
)
if ($needConfigure) {
    Write-Host "[PS Chiaki] Configuring CMake (NDK: $Ndk)..."
    & $CMake `
        -G Ninja `
        -S $CppPsDir `
        -B $BuildDir `
        "-DCMAKE_MAKE_PROGRAM=$Ninja" `
        "-DCMAKE_TOOLCHAIN_FILE=$Toolchain" `
        "-DANDROID_ABI=arm64-v8a" `
        "-DANDROID_PLATFORM=android-29" `
        "-DCMAKE_BUILD_TYPE=Release" `
        "-DCHIAKI_NG_DIR=$($ChiakiDir -replace '\\','/')" `
        "-DPython3_EXECUTABLE=$($Python -replace '\\','/')" `
        "-DPROTOC=protoc"
    if ($LASTEXITCODE -ne 0) { throw "CMake configure failed ($LASTEXITCODE)" }
}

Write-Host "[PS Chiaki] Building libgtaps.so..."
& $CMake --build $BuildDir --target gtaps --parallel
if ($LASTEXITCODE -ne 0) { throw "CMake build failed ($LASTEXITCODE)" }

$BuiltSo = Join-Path $BuildDir "libgtaps.so"
if (-not (Test-Path -LiteralPath $BuiltSo)) {
    throw "Expected output missing: $BuiltSo"
}
Copy-Item -Force $BuiltSo $OutSo
if (Test-Path -LiteralPath $Strip) { & $Strip --strip-unneeded $OutSo | Out-Null }

$info = Get-Item -LiteralPath $OutSo
Write-Host "[PS Chiaki] Built $($info.FullName) ($([math]::Round($info.Length / 1024.0, 1)) KiB)"

if (Test-Path -LiteralPath $Nm) {
    $syms = & $Nm -D --defined-only $OutSo 2>$null
    $required = @(
        "Java_com_gtasixty_spatial_PsChiakiNative_nativeInit",
        "Java_com_gtasixty_spatial_PsChiakiNative_nativeDiscoverConsoles",
        "Java_com_gtasixty_spatial_PsChiakiNative_nativeWakeConsole",
        "Java_com_gtasixty_spatial_PsChiakiNative_nativeRegisterConsole",
        "Java_com_gtasixty_spatial_PsChiakiNative_nativeSessionStart",
        "Java_com_gtasixty_spatial_PsChiakiNative_nativeSessionSetControllerState",
        "Java_com_gtasixty_spatial_PsChiakiNative_nativeSessionSetLoginPin",
        "Java_com_gtasixty_spatial_PsChiakiNative_nativeSessionRequestIdr",
        "Java_com_gtasixty_spatial_PsChiakiNative_nativeSessionStop"
    )
    $missing = @()
    foreach ($fn in $required) {
        if (-not ($syms | Select-String -SimpleMatch $fn -Quiet)) { $missing += $fn }
    }
    if ($missing.Count -gt 0) {
        throw "Missing exported JNI symbols: $($missing -join ', ')"
    }
    Write-Host "[PS Chiaki] Exported JNI symbols OK"
}

if (Test-Path -LiteralPath $ReadElf) {
    $needed = & $ReadElf -d $OutSo 2>$null | Select-String -Pattern "NEEDED"
    Write-Host "[PS Chiaki] NEEDED libs:"
    $needed | ForEach-Object { Write-Host "  $($_.Line.Trim())" }
    if ($needed | Select-String -SimpleMatch "libc++_shared.so" -Quiet) {
        throw "libgtaps.so must not dynamically link libc++_shared.so"
    }
}
Write-Host "[PS Chiaki] Success"
