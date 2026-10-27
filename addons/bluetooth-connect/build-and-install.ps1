# Windows version of install.sh: build Bluetooth Connect, install it on the TV and grant permissions.
#   addons\bluetooth-connect\build-and-install.ps1 -TvHost 192.168.1.50
param(
    [string]$TvHost = $env:TV_HOST
)
$ErrorActionPreference = 'Stop'
if (-not $TvHost) { throw 'pass -TvHost <tv ip> or set $env:TV_HOST' }
$env:TV_HOST = $TvHost
$here = $PSScriptRoot
$tools = Join-Path (Split-Path (Split-Path $here -Parent) -Parent) 'tools'
$py = Join-Path $tools 'venv\Scripts\python.exe'
if (-not (Test-Path $py)) { throw 'run: python -m venv tools\venv; tools\venv\Scripts\python.exe -m pip install -r tools\requirements.txt' }
$pkg = 'com.gathin.btconnect'
function Tv([string]$cmd) { & $py -I (Join-Path $tools 'tv.py') $cmd; if ($LASTEXITCODE) { throw "tv.py failed: $cmd" } }

Push-Location $here
$ErrorActionPreference = 'Continue'
try {
    & .\gradlew.bat assembleRelease -q
} finally {
    Pop-Location
}
if ($LASTEXITCODE) { throw 'build failed (JDK 17 and Android SDK needed)' }
$ErrorActionPreference = 'Stop'

$apk = Join-Path $here 'app\build\outputs\apk\release\app-release.apk'
& $py -I (Join-Path $tools 'b64push.py') $apk /data/local/tmp/bluetooth-connect.apk | Out-Null
Tv 'pm install -r /data/local/tmp/bluetooth-connect.apk; rm -f /data/local/tmp/bluetooth-connect.apk'
Tv "pm grant $pkg android.permission.BLUETOOTH_CONNECT 2>/dev/null; true"
Tv "am start -n $pkg/.MainActivity 2>/dev/null; true"
Write-Host 'Installed. Bluetooth Connect is on the TV.'
