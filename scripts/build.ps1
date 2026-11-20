param(
    [switch]$Clean,
    [ValidateSet("Release", "RelWithDebInfo", "Debug")]
    [string]$Config = "Release"
)

$ErrorActionPreference = "Stop"

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$ThirdParty = Join-Path $Root "third_party"
$BuildDir = Join-Path $Root "build"

New-Item -ItemType Directory -Force -Path $ThirdParty | Out-Null

# 1. Locate VS 2022 BuildTools / IDE
$vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
if (-not (Test-Path -LiteralPath $vswhere)) {
    throw "vswhere.exe not found at $vswhere"
}
$vsPath = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if ([string]::IsNullOrWhiteSpace($vsPath)) {
    throw "Visual Studio 2022 VC++ tools not found."
}
$vcvars = Join-Path $vsPath "VC\Auxiliary\Build\vcvars64.bat"
$cmakeExe = (Get-ChildItem -Path "$vsPath\Common7\IDE\CommonExtensions\Microsoft\CMake" -Filter "cmake.exe" -Recurse | Select-Object -First 1).FullName
$ninjaExe = (Get-ChildItem -Path "$vsPath\Common7\IDE\CommonExtensions\Microsoft\CMake\Ninja" -Filter "ninja.exe" -Recurse | Select-Object -First 1).FullName
if (-not (Test-Path -LiteralPath $vcvars) -or -not $cmakeExe -or -not $ninjaExe) {
    throw "Missing vcvars64.bat, cmake.exe, or ninja.exe under $vsPath"
}

$dxcExe = Get-ChildItem -Path "${env:ProgramFiles(x86)}\Windows Kits\10\bin" -Filter "dxc.exe" -Recurse -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -match '\\x64\\' } |
    Sort-Object FullName -Descending |
    Select-Object -First 1 -ExpandProperty FullName
if (-not $dxcExe) {
    throw "Windows SDK dxc.exe (x64) not found."
}

Write-Host "[GTA-Sixty] VS: $vsPath"
Write-Host "[GTA-Sixty] CMake: $cmakeExe"
Write-Host "[GTA-Sixty] Ninja: $ninjaExe"
Write-Host "[GTA-Sixty] DXC: $dxcExe"

# 2. Download OpenXR Loader 1.1.65 nupkg
$openXrDir = Join-Path $ThirdParty "openxr"
$openXrMarker = Join-Path $openXrDir "include\openxr\openxr.h"
if (-not (Test-Path -LiteralPath $openXrMarker)) {
    New-Item -ItemType Directory -Force -Path $openXrDir | Out-Null
    $nupkgZip = Join-Path $openXrDir "openxr.zip"
    Write-Host "[GTA-Sixty] Downloading OpenXR.Loader.1.1.65.nupkg..."
    Invoke-WebRequest -Uri "https://github.com/KhronosGroup/OpenXR-SDK/releases/download/release-1.1.65/OpenXR.Loader.1.1.65.nupkg" -OutFile $nupkgZip -UseBasicParsing
    Expand-Archive -Path $nupkgZip -DestinationPath $openXrDir -Force
    Remove-Item -LiteralPath $nupkgZip -Force -ErrorAction SilentlyContinue
}

# 3. Download nlohmann/json single header v3.11.3
$jsonHeader = Join-Path $ThirdParty "nlohmann\json.hpp"
if (-not (Test-Path -LiteralPath $jsonHeader)) {
    New-Item -ItemType Directory -Force -Path (Split-Path $jsonHeader -Parent) | Out-Null
    Write-Host "[GTA-Sixty] Downloading nlohmann/json.hpp v3.11.3..."
    Invoke-WebRequest -Uri "https://github.com/nlohmann/json/releases/download/v3.11.3/json.hpp" -OutFile $jsonHeader -UseBasicParsing
}

# 3b. Download nv-codec-headers nvEncodeAPI.h (n13.0.19.0)
$nvencHeader = Join-Path $ThirdParty "ffnvcodec\nvEncodeAPI.h"
if (-not (Test-Path -LiteralPath $nvencHeader)) {
    New-Item -ItemType Directory -Force -Path (Split-Path $nvencHeader -Parent) | Out-Null
    Write-Host "[GTA-Sixty] Downloading nvEncodeAPI.h (n13.0.19.0)..."
    Invoke-WebRequest -Uri "https://raw.githubusercontent.com/FFmpeg/nv-codec-headers/n13.0.19.0/include/ffnvcodec/nvEncodeAPI.h" -OutFile $nvencHeader -UseBasicParsing
}

# 4. Build & install MbedTLS 3.6.2 with DTLS-SRTP enabled (cached in third_party/mbedtls-install)
$mbedInstall = Join-Path $ThirdParty "mbedtls-install"
$mbedLib = Join-Path $mbedInstall "lib\mbedtls.lib"
$mbedInstalledCfg = Join-Path $mbedInstall "include\mbedtls\mbedtls_config.h"
$mbedNeedsBuild = -not (Test-Path -LiteralPath $mbedLib) -or -not (Test-Path -LiteralPath $mbedInstalledCfg) -or -not ((Get-Content -LiteralPath $mbedInstalledCfg -Raw) -match '(?m)^#define\s+MBEDTLS_SSL_DTLS_SRTP\b')
if ($mbedNeedsBuild) {
    $mbedSrcZip = Join-Path $ThirdParty "mbedtls-3.6.2.tar.bz2"
    $mbedSrcRoot = Join-Path $ThirdParty "mbedtls-3.6.2"
    $mbedBuild = Join-Path $ThirdParty "mbedtls-build"
    if (-not (Test-Path -LiteralPath (Join-Path $mbedSrcRoot "CMakeLists.txt"))) {
        Write-Host "[GTA-Sixty] Downloading MbedTLS 3.6.2..."
        Invoke-WebRequest -Uri "https://github.com/Mbed-TLS/mbedtls/releases/download/mbedtls-3.6.2/mbedtls-3.6.2.tar.bz2" -OutFile $mbedSrcZip -UseBasicParsing
        Write-Host "[GTA-Sixty] Extracting MbedTLS 3.6.2..."
        & tar -xf $mbedSrcZip -C $ThirdParty
        if ($LASTEXITCODE -ne 0) { throw "Failed to extract MbedTLS 3.6.2" }
        Remove-Item -LiteralPath $mbedSrcZip -Force -ErrorAction SilentlyContinue
    }
    $mbedCfgFile = Join-Path $mbedSrcRoot "include\mbedtls\mbedtls_config.h"
    $cfgRaw = Get-Content -LiteralPath $mbedCfgFile -Raw
    if ($cfgRaw -match '(?m)^//#define\s+MBEDTLS_SSL_DTLS_SRTP\b') {
        $cfgRaw = $cfgRaw -replace '(?m)^//#define\s+MBEDTLS_SSL_DTLS_SRTP\b', '#define MBEDTLS_SSL_DTLS_SRTP'
        Set-Content -LiteralPath $mbedCfgFile -Value $cfgRaw -NoNewline -Encoding ASCII
    }

    $mbedBatch = Join-Path $ThirdParty "build-mbedtls.cmd"
    @(
        '@echo off',
        ('call "{0}" >nul 2>&1' -f $vcvars),
        'if errorlevel 1 exit /b 1',
        ('"{0}" -G Ninja -S "{1}" -B "{2}" -DCMAKE_MAKE_PROGRAM="{3}" -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="{4}" -DENABLE_TESTING=OFF -DENABLE_PROGRAMS=OFF -DUSE_SHARED_MBEDTLS_LIBRARY=OFF -DUSE_STATIC_MBEDTLS_LIBRARY=ON -DCMAKE_POLICY_VERSION_MINIMUM=3.5' -f $cmakeExe, $mbedSrcRoot, $mbedBuild, $ninjaExe, $mbedInstall),
        'if errorlevel 1 exit /b 2',
        ('"{0}" --build "{1}" --parallel 4' -f $cmakeExe, $mbedBuild),
        'if errorlevel 1 exit /b 3',
        ('"{0}" --install "{1}"' -f $cmakeExe, $mbedBuild),
        'if errorlevel 1 exit /b 4',
        'exit /b 0'
    ) | Set-Content -LiteralPath $mbedBatch -Encoding ASCII

    Write-Host "[GTA-Sixty] Building & installing MbedTLS 3.6.2..."
    & cmd.exe /c ('"{0}"' -f $mbedBatch)
    if ($LASTEXITCODE -ne 0) { throw "MbedTLS build failed with code $LASTEXITCODE" }
}

if ($Clean -and (Test-Path -LiteralPath $BuildDir)) {
    Remove-Item -LiteralPath $BuildDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $BuildDir | Out-Null

# 5. Configure & build GTA-Sixty inside vcvars64 environment
$buildBatch = Join-Path $BuildDir "build-GTA-Sixty.cmd"
@(
    '@echo off',
    ('call "{0}" >nul 2>&1' -f $vcvars),
    'if errorlevel 1 exit /b 1',
    'chcp 65001 >nul',
    ('cd /d "{0}"' -f $Root),
    ('"{0}" -G Ninja -S "{8}" -B "{1}" -DCMAKE_MAKE_PROGRAM="{2}" -DCMAKE_BUILD_TYPE={3} -DCMAKE_PREFIX_PATH="{4}" -DGTA60_DXC_EXECUTABLE="{5}" -DGTA60_OPENXR_ROOT="{6}" -DGTA60_NLOHMANN_JSON_DIR="{7}" -DCMAKE_POLICY_VERSION_MINIMUM=3.5' -f $cmakeExe, $BuildDir, $ninjaExe, $Config, $mbedInstall, $dxcExe, $openXrDir, $ThirdParty, $Root),
    'if errorlevel 1 exit /b 2',
    ('"{0}" --build "{1}" --parallel 4' -f $cmakeExe, $BuildDir),
    'if errorlevel 1 exit /b 3',
    'exit /b 0'
) | Set-Content -LiteralPath $buildBatch -Encoding ASCII

Write-Host "[GTA-Sixty] Configuring and building ($Config)..."
& cmd.exe /c ('"{0}"' -f $buildBatch)
if ($LASTEXITCODE -ne 0) {
    throw "GTA-Sixty build failed with exit code $LASTEXITCODE"
}

Write-Host "[GTA-Sixty] Build succeeded! Binaries in: $BuildDir"
