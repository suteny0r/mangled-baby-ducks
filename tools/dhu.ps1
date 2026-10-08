# Launch the Android Auto Desktop Head Unit against the phone, in its own console window.
#
# Prerequisites (one-time, on the phone): Android Auto developer mode with "Unknown sources"
# on, then "Start head unit server" from Android Auto's overflow menu before each session.
# The DHU is installed at <sdk>\extras\google\auto (downloaded 2026-09-19, r2.1).
#
# In the DHU window the mouse is the touchscreen. The console accepts commands, e.g.
#   tap X Y            screenshot C:\path\shot.png     night / day
#   dpad up|down|left|right|click        mic begin      help
#
# Resolution comes from a config ini in <sdk>\extras\google\auto\config (default.ini is
# 800x480; default_720p.ini, default_1080p.ini; copy one and edit resolution/dpi for others).
# On this 4K desktop at 200 % scaling a 1920x1080 window only fits with the exe marked
# high-DPI aware (AppCompatFlags "~ HIGHDPIAWARE"); without it Windows maximizes the window
# and pushes the frame offscreen, which looks like fullscreen. This script sets that flag.
param(
    [string]$Serial = "R5CN70YWT5Z",
    [string]$Sdk = "C:\Users\User\AppData\Local\Android\Sdk",
    [string]$Config = "config\default_1080p.ini"
)

$dhu = Join-Path $Sdk "extras\google\auto\desktop-head-unit.exe"
if (-not (Test-Path $dhu)) { Write-Error "DHU not found at $dhu"; exit 1 }

$devices = & adb devices | Select-String $Serial
if (-not $devices) { Write-Error "Phone $Serial not on adb"; exit 1 }

$server = & adb -s $Serial shell dumpsys notification --noredact 2>$null | Select-String "Head unit server running"
if (-not $server) {
    Write-Warning "Head unit server is not running on the phone: Android Auto settings > overflow > Start head unit server"
}

$layers = 'HKCU:\Software\Microsoft\Windows NT\CurrentVersion\AppCompatFlags\Layers'
if (-not (Test-Path $layers)) { New-Item -Path $layers -Force | Out-Null }
if ((Get-ItemProperty $layers -ErrorAction SilentlyContinue).$dhu -ne '~ HIGHDPIAWARE') {
    Set-ItemProperty -Path $layers -Name $dhu -Value '~ HIGHDPIAWARE'
}

& adb -s $Serial forward tcp:5277 tcp:5277 | Out-Null
Start-Process -FilePath $dhu -ArgumentList "--adb=5277", "--config=$Config" -WorkingDirectory (Split-Path $dhu)
Write-Host "DHU started. Phone-side: accept the 'Welcome to Android Auto' prompt if it appears."
