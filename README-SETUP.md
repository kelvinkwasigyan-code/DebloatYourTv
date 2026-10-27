# Onn FHD Streaming Device - CleanYourTV Setup Guide

## What was done
1. **Debloated telemetry, trackers, and ad services:**
   - Disabled Google feedback & consent tracking
   - Disabled Privacy Sandbox & federated telemetry
   - Disabled unused preloaded apps (Netflix, Prime Video, Print Spooler, TalkBack)
   - Disabled setupwraith (preventing Home hijack)
   - Disabled stock `launcherx`
2. **Replaced with Apple TV-style native launcher:**
   - Pitch-black background with subtle ambient glow
   - Minimalist date/time in Outfit typography
   - Dock pinned to 4 favorite apps: **Apple TV**, **YouTube**, **Spotify**, and **Plex**
   - Clean app drawer grid sliding up on `DPAD_DOWN`
   - Single glass floating highlight
3. **Ad-blocking Private DNS:**
   - Configured `dns.adguard-dns.com` system-wide.

---

## How to Change Dock Apps & Order
1. Open `launcher\app\src\main\res\values\config.xml` in an editor.
2. Edit `<string-array name="dock_apps">`:
   ```xml
   <string-array name="dock_apps">
       <item>com.apple.atve.androidtv.appletv</item>
       <item>com.google.android.youtube.tv</item>
       <item>com.spotify.tv.android</item>
       <item>com.plexapp.android</item>
   </string-array>
   ```
3. Run:
   ```powershell
   $env:JAVA_HOME = "C:\Users\isaac\.gemini\antigravity-ide\scratch\jdk17\jdk-17.0.20.1+1"
   .\launcher\build-and-install.ps1 -TvHost 192.168.0.51
   ```

---

## How to Apply Wallpapers and Themes
You can change the theme or add wallpapers without recompiling:
```powershell
tools\theme.ps1 -TvHost 192.168.0.51 themes\aurora
```
Themes available in `themes\`:
- `themes\aurora`
- `themes\sweden`
- `themes\scenes`
- `themes\six-columns`

To restore back to default pitch-black:
```powershell
tools\theme.ps1 -TvHost 192.168.0.51 -Reset
```

---

## How to Re-enable Any Disabled App
If you ever want an app back (e.g. Netflix or Prime Video):
```powershell
tools\venv\Scripts\python.exe tools\tv.py "pm enable --user 0 com.netflix.ninja"
```

---

## How to Fully Undo (One-Line Restore)
Run the included restore script:
```powershell
.\restore-my-tv.ps1 -TvHost 192.168.0.51
```
This re-enables all apps, sets stock Google TV launcher back as Home, and restores standard DNS.

---

## Security Wrap-Up (Closing ADB)
Now that your TV is clean and configured:
1. On your TV, go to: **Settings → System → Developer options**.
2. Turn **USB debugging** OFF.
3. Select **Revoke USB debugging authorizations**.
4. (Optional) Run `tools\venv\Scripts\python.exe tools\adb-closed.py 192.168.0.51` to verify port 5555 is closed to the local network.
