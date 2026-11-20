# Registers the GTA-Sixty agent to start automatically when you sign in, so the headset's
# "Wake PC" button can launch a stream on a machine that is powered but not already streaming.
#
# Uses the user's Startup folder (no administrator rights required). A scheduled task would also
# work, but creating one needs elevation on many machines.
#
# Usage:
#   scripts\install-autostart.ps1            # install / update
#   scripts\install-autostart.ps1 -Remove    # uninstall
#   scripts\install-autostart.ps1 -StartNow  # install and start it immediately

param([switch]$Remove, [switch]$StartNow)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Exe = Join-Path $Root "build\GTA-Sixty.exe"
$Startup = [Environment]::GetFolderPath('Startup')
$Lnk = Join-Path $Startup 'GTA-Sixty Agent.lnk'

if ($Remove) {
    if (Test-Path -LiteralPath $Lnk) { Remove-Item -LiteralPath $Lnk -Force }
    Get-Process GTA-Sixty -ErrorAction SilentlyContinue |
        Where-Object { $_.Id -ne 0 } | ForEach-Object {
            # Only stop a bare agent (no stream server bound on 7660 belongs to it).
            try { $_.Kill() } catch {}
        }
    Write-Host "[GTA60] Autostart shortcut removed."
    exit 0
}

if (-not (Test-Path -LiteralPath $Exe)) {
    throw "Binary not found: $Exe  (run scripts\build.ps1 first)"
}

$shell = New-Object -ComObject WScript.Shell
$sc = $shell.CreateShortcut($Lnk)
$sc.TargetPath = $Exe
$sc.Arguments = '--agent'
$sc.WorkingDirectory = Split-Path $Exe -Parent
$sc.WindowStyle = 7          # minimized
$sc.Description = 'GTA-Sixty remote-start agent (UDP 7662)'
$sc.Save()
Write-Host "[GTA60] Agent will start at sign-in: $Lnk"

if ($StartNow) {
    Start-Process -FilePath $Exe -ArgumentList '--agent' -WindowStyle Hidden
    Write-Host "[GTA60] Agent started."
}

Write-Host ""
Write-Host "How the chain works:"
Write-Host "  headset 'Wake PC' -> magic packet wakes the machine ->"
Write-Host "  agent (started at sign-in) receives GTA60_START on UDP 7662 ->"
Write-Host "  launches: GTA-Sixty.exe --connect auto --quest-server --bitrate 120"
Write-Host ""
Write-Host "Wake-on-LAN only gets the PC to the sign-in screen. For a fully hands-off"
Write-Host "wake-and-stream, enable automatic sign-in for this account."
