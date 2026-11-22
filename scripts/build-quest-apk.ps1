$ErrorActionPreference = "Stop"

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$AppDir = Join-Path $Root "quest-app"
$SdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }

$BuildTools = Get-ChildItem (Join-Path $SdkRoot "build-tools") | Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
$PlatformDir = Get-ChildItem (Join-Path $SdkRoot "platforms") | Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
$AndroidJar = Join-Path $PlatformDir "android.jar"

# JDK: prefer JAVA_HOME, then whatever javac.exe is on PATH.
$JdkBin = $null
if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME "bin\javac.exe"))) {
    $JdkBin = Join-Path $env:JAVA_HOME "bin"
} else {
    $javacFound = Get-Command javac -ErrorAction SilentlyContinue
    if (-not $javacFound) { throw "JDK not found: set JAVA_HOME or put javac.exe on PATH (JDK 17 or newer)" }
    $JdkBin = Split-Path $javacFound.Source -Parent
}

$Aapt2 = Join-Path $BuildTools "aapt2.exe"
$D8 = Join-Path $BuildTools "d8.bat"
$Zipalign = Join-Path $BuildTools "zipalign.exe"
$Apksigner = Join-Path $BuildTools "apksigner.bat"
$Javac = Join-Path $JdkBin "javac.exe"
$Jar = Join-Path $JdkBin "jar.exe"
$Keytool = Join-Path $JdkBin "keytool.exe"

foreach ($tool in @($Aapt2, $D8, $Zipalign, $Apksigner, $AndroidJar, $Javac, $Jar, $Keytool)) {
    if (-not (Test-Path -LiteralPath $tool)) { throw "Required Android build tool missing: $tool" }
}

$OutBuild = Join-Path $Root "build"
$Work = Join-Path $OutBuild "quest-apk-work"
if (Test-Path -LiteralPath $Work) { Remove-Item -LiteralPath $Work -Recurse -Force }
$ResFlat = Join-Path $Work "res-flat"
$GenJava = Join-Path $Work "gen"
$ObjDir = Join-Path $Work "obj"
$DexDir = Join-Path $Work "dex"
New-Item -ItemType Directory -Force -Path $OutBuild, $ResFlat, $GenJava, $ObjDir, $DexDir | Out-Null

$Manifest = Join-Path $AppDir "src\main\AndroidManifest.xml"
$ResDir = Join-Path $AppDir "src\main\res"
$AssetsDir = Join-Path $AppDir "src\main\assets"
$JavaSrcDir = Join-Path $AppDir "src\main\java"

Write-Host "[Quest APK] Compiling resources with aapt2..."
& $Aapt2 compile --dir $ResDir -o (Join-Path $ResFlat "res.zip")
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

$UnalignedApk = Join-Path $Work "unaligned.apk"
Write-Host "[Quest APK] Linking manifest & resources..."
$linkArgs = @(
    "link",
    "-o", $UnalignedApk,
    "-I", $AndroidJar,
    "--manifest", $Manifest,
    "--java", $GenJava,
    "--min-sdk-version", "29",
    "--target-sdk-version", "34"
)
$linkArgs += (Join-Path $ResFlat "res.zip")
& $Aapt2 @linkArgs
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

$JavaFiles = @(Get-ChildItem -Path $JavaSrcDir -Filter "*.java" -Recurse | Select-Object -ExpandProperty FullName) +
             @(Get-ChildItem -Path $GenJava -Filter "*.java" -Recurse | Select-Object -ExpandProperty FullName)
Write-Host "[Quest APK] Compiling $($JavaFiles.Count) Java sources..."
& $Javac --release 11 -classpath $AndroidJar -d $ObjDir @JavaFiles
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

$ClassesJar = Join-Path $Work "classes.jar"
& $Jar cf $ClassesJar -C $ObjDir .
if ($LASTEXITCODE -ne 0) { throw "jar cf failed" }

Write-Host "[Quest APK] Converting classes.jar -> classes.dex with d8..."
& cmd.exe /c ('"{0}" --release --min-api 29 --lib "{1}" --output "{2}" "{3}"' -f $D8, $AndroidJar, $DexDir, $ClassesJar)
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "[Quest APK] Adding classes.dex to APK..."
& $Jar uf $UnalignedApk -C $DexDir "classes.dex"
if ($LASTEXITCODE -ne 0) { throw "jar uf classes.dex failed" }

# aapt2 on Windows stores -A entries with backslashes ("assets/direct\file.js"), which the
# AssetManager cannot open. jar always writes '/', so package the assets here instead.
if (Test-Path -LiteralPath $AssetsDir) {
    Write-Host "[Quest APK] Adding assets..."
    & $Jar uf $UnalignedApk -C (Join-Path $AppDir "src\main") "assets"
    if ($LASTEXITCODE -ne 0) { throw "jar uf assets failed" }
}

$AlignedApk = Join-Path $Work "aligned.apk"
Write-Host "[Quest APK] Aligning APK..."
& $Zipalign -f 4 $UnalignedApk $AlignedApk
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

$Keystore = Join-Path $AppDir "debug.keystore"
if (-not (Test-Path -LiteralPath $Keystore)) {
    Write-Host "[Quest APK] Generating debug keystore..."
    & $Keytool -genkeypair -v -keystore $Keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=GTA-Sixty,O=Android,C=US"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}

$FinalApk = Join-Path $OutBuild "GTA-Sixty-Quest3.apk"
Write-Host "[Quest APK] Signing $FinalApk..."
& cmd.exe /c ('"{0}" sign --ks "{1}" --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --out "{2}" "{3}"' -f $Apksigner, $Keystore, $FinalApk, $AlignedApk)
if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed" }

Write-Host "[Quest APK] Verifying signature..."
& cmd.exe /c ('"{0}" verify --verbose "{1}"' -f $Apksigner, $FinalApk)
if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed" }

Write-Host "[Quest APK] Success: $FinalApk"
