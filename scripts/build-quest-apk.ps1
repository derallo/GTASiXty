param(
    [ValidateSet("Debug", "Release")]
    [string]$Configuration = "Debug"
)

$ErrorActionPreference = "Stop"

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$AppDir = Join-Path $Root "quest-spatial-app"
$SdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$BuildTools = Get-ChildItem (Join-Path $SdkRoot "build-tools") | Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
$Apksigner = Join-Path $BuildTools "apksigner.bat"
if (-not (Test-Path -LiteralPath $Apksigner)) {
    throw "Required Android build tool missing: $Apksigner"
}

$GradleBat = Get-ChildItem (Join-Path $env:USERPROFILE ".gradle\wrapper\dists\gradle-9.6.0-bin") -Recurse -Filter "gradle.bat" -ErrorAction SilentlyContinue |
    Select-Object -First 1 -ExpandProperty FullName
if (-not $GradleBat) {
    $cmd = Get-Command gradle -ErrorAction SilentlyContinue
    if ($cmd) { $GradleBat = $cmd.Source }
}
if (-not $GradleBat) {
    throw "Gradle 9.6.0 not found in ~/.gradle/wrapper/dists or PATH."
}

$LocalProps = Join-Path $AppDir "local.properties"
if (-not (Test-Path -LiteralPath $LocalProps)) {
    $escapedSdk = $SdkRoot.Replace('\', '\\').Replace(':', '\:')
    Set-Content -Path $LocalProps -Value "sdk.dir=$escapedSdk" -Encoding ASCII
}

$Keystore = Join-Path $AppDir "debug.keystore"
if (-not (Test-Path -LiteralPath $Keystore)) {
    $keytool = Get-Command keytool -ErrorAction SilentlyContinue
    if (-not $keytool -and $env:JAVA_HOME) {
        $kt = Join-Path $env:JAVA_HOME "bin\keytool.exe"
        if (Test-Path -LiteralPath $kt) { $keytool = @{ Source = $kt } }
    }
    if (-not $keytool) { throw "keytool not found to generate debug.keystore" }
    Write-Host "[Quest APK] Generating debug keystore..."
    & $keytool.Source -genkeypair -v -keystore $Keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=GTA-Sixty,O=Android,C=US"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}

# Build the native OpenXR hand-tracking shim first (it lands in src/main/jniLibs). The app degrades
# gracefully when the .so is absent, so a missing NDK is only a warning.
$HandTrackerScript = Join-Path $PSScriptRoot "build-hand-tracker.ps1"
if (Test-Path -LiteralPath $HandTrackerScript) {
    Write-Host "[Quest APK] Building native hand tracker shim..."
    try {
        & $HandTrackerScript
        if ($LASTEXITCODE -ne 0) { throw "build-hand-tracker.ps1 exited $LASTEXITCODE" }
    } catch {
        Write-Warning "[Quest APK] Native hand tracker unavailable ($($_.Exception.Message)); GTA hands will fall back to the SDK body-tracking path."
    }
}

$PsChiakiScript = Join-Path $PSScriptRoot "build-ps-chiaki.ps1"
if (Test-Path -LiteralPath $PsChiakiScript) {
    Write-Host "[Quest APK] Building native PlayStation Remote Play (libgtaps.so)..."
    try {
        & $PsChiakiScript
        if ($LASTEXITCODE -ne 0) { throw "build-ps-chiaki.ps1 exited $LASTEXITCODE" }
    } catch {
        Write-Warning "[Quest APK] Native PlayStation Remote Play unavailable ($($_.Exception.Message)); PS Remote Play mode will be disabled."
    }
}

$Task = if ($Configuration -eq "Release") { "assembleRelease" } else { "assembleDebug" }
$OutBuild = Join-Path $Root "build"
New-Item -ItemType Directory -Force -Path $OutBuild | Out-Null
$FinalApk = Join-Path $OutBuild "GTA-Sixty-Quest3.apk"

Write-Host "[Quest APK] Building GTA-Sixty with Gradle ($GradleBat $Task)..."
Push-Location $AppDir
try {
    & $GradleBat $Task --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle $Task failed" }
} finally {
    Pop-Location
}

$VariantDir = if ($Configuration -eq "Release") { "release" } else { "debug" }
$BuiltApk = Get-ChildItem (Join-Path $AppDir "build\outputs\apk\$VariantDir") -Filter "*.apk" |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
if (-not $BuiltApk) {
    throw "No built APK found under quest-spatial-app/build/outputs/apk/$VariantDir"
}

Copy-Item -Force $BuiltApk $FinalApk

Write-Host "[Quest APK] Verifying signature..."
& cmd.exe /c ('"{0}" verify --verbose "{1}"' -f $Apksigner, $FinalApk)
if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed" }

Write-Host "[Quest APK] Success: $FinalApk"
