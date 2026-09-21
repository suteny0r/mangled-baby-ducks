# Launch the Android Auto Desktop Head Unit against the phone, in its own console window.
#
# Prerequisites (one-time, on the phone): Android Auto developer mode with "Unknown sources"
# on, then "Start head unit server" from Android Auto's overflow menu before each session.
# The DHU is installed at <sdk>\extras\google\auto (downloaded 2026-09-19, r2.1).
#
# In the DHU window the mouse is the touchscreen. The console accepts commands, e.g.
#   tap X Y            screenshot C:\path\shot.png     night / day
#   dpad up|down|left|right|click        mic begin      help
param(
    [string]$Serial = "R5CN70YWT5Z",
    [string]$Sdk = "C:\Users\User\AppData\Local\Android\Sdk"
)

$dhu = Join-Path $Sdk "extras\google\auto\desktop-head-unit.exe"
if (-not (Test-Path $dhu)) { Write-Error "DHU not found at $dhu"; exit 1 }

$devices = & adb devices | Select-String $Serial
if (-not $devices) { Write-Error "Phone $Serial not on adb"; exit 1 }

$server = & adb -s $Serial shell dumpsys notification --noredact 2>$null | Select-String "Head unit server running"
if (-not $server) {
    Write-Warning "Head unit server is not running on the phone: Android Auto settings > overflow > Start head unit server"
}

& adb -s $Serial forward tcp:5277 tcp:5277 | Out-Null
Start-Process -FilePath $dhu -ArgumentList "--adb=5277" -WorkingDirectory (Split-Path $dhu)
Write-Host "DHU started. Phone-side: accept the 'Welcome to Android Auto' prompt if it appears."
