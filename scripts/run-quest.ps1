param(
    [ValidateSet("login", "consoles", "connect", "synthetic")]
    [string]$Mode = "connect",
    [string]$Console = "auto",
    [ValidateSet(60, 90)]
    [int]$Fps = 60,
    [ValidateSet("1080p", "1440p", "4k")]
    [string]$Upscale = "1440p",
    [double]$ScreenWidth = 3.6,
    [double]$ScreenDistance = 2.5,
    [switch]$QuestServer,
    [int]$Bitrate = 50,
    [int]$Port = 7660,
    [switch]$ForwardController,
    [switch]$Headless
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Exe = Join-Path $Root "build\GTA-Sixty.exe"

if (-not (Test-Path -LiteralPath $Exe)) {
    Write-Host "[GTA-Sixty] Binary not built yet; running scripts\build.ps1..."
    & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "build.ps1")
}

$argsList = @()
switch ($Mode) {
    "login"    { $argsList += "--login" }
    "consoles" { $argsList += "--list-consoles" }
    "connect"  {
        $argsList += @("--connect", $Console, "--fps", "$Fps", "--upscale", $Upscale,
                       "--screen-width", "$ScreenWidth", "--screen-distance", "$ScreenDistance")
        if ($QuestServer) { $argsList += @("--quest-server", "--port", "$Port", "--bitrate", "$Bitrate") }
        if ($ForwardController) { $argsList += "--forward-controller" }
        if ($Headless) { $argsList += "--headless" }
    }
    "synthetic" {
        $frames = if ($QuestServer) { "0" } else { "180" }
        $argsList += @("--synthetic-demo", "--frames", $frames, "--fps", "$Fps", "--upscale", $Upscale,
                       "--screen-width", "$ScreenWidth", "--screen-distance", "$ScreenDistance")
        if ($QuestServer) { $argsList += @("--quest-server", "--port", "$Port", "--bitrate", "$Bitrate") }
        if ($Headless) { $argsList += "--headless" }
    }
}

Write-Host "[GTA-Sixty] Launching: $Exe $($argsList -join ' ')"
& $Exe @argsList
exit $LASTEXITCODE
