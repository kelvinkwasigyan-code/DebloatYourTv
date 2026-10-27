# Restore script for onn. full HD Streaming Device
# Re-enables all disabled packages, resets Home back to the stock Google TV launcher,
# and resets Private DNS back to default.
param(
    [string]$TvHost = "192.168.0.51"
)

$ErrorActionPreference = 'Stop'
$py = "tools\venv\Scripts\python.exe"

Write-Host "Reconnecting to TV at $TvHost..."
$env:TV_HOST = $TvHost

$packages = @(
    "com.google.android.apps.tv.launcherx",
    "com.google.android.tungsten.setupwraith",
    "com.google.android.feedback",
    "com.android.tv.feedbackconsent",
    "com.android.adservices.api",
    "com.android.ondevicepersonalization.services",
    "com.android.federatedcompute.services",
    "com.netflix.ninja",
    "com.amazon.amazonvideo.livingroom",
    "com.android.printspooler",
    "com.google.android.marvin.talkback"
)

Write-Host "Re-enabling packages..."
foreach ($pkg in $packages) {
    & $py tools\tv.py "pm enable --user 0 $pkg"
    Write-Host "  Enabled: $pkg"
}

Write-Host "Restoring stock Google TV launcher as Home..."
& $py tools\tv.py "cmd package set-home-activity --user 0 com.google.android.apps.tv.launcherx/.home.HomeActivity"

Write-Host "Restoring default DNS..."
& $py tools\tv.py "settings put global private_dns_mode opportunistic; settings delete global private_dns_specifier"

Write-Host "Done! Stock Google TV home screen has been fully restored."
