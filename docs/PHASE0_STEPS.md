# Phase 0: testing the probe app in the car

Takes about 20 minutes. Park somewhere safe, **car in P**, car ON (ready).

## A. Install (phone, Termux)

1. On the phone, download the newest `adas-probe.apk` from the repo's **Releases** page
   (or Actions → newest run → Artifacts, then unzip). Save it to Downloads.
2. In Termux, go to Downloads (run `termux-setup-storage` once if you never did):
   ```
   cd ~/storage/downloads
   ```
3. Connect to the car (same as always):
   ```
   adb connect <car_ip>:5555
   ```
4. Install (the `-r` keeps data when updating):
   ```
   adb install -r adas-probe.apk
   ```
5. On the car screen, open the app **ADAS Probe** from the app list.

## B. Read-only tests (safe)

Press the buttons in order and wait for **"Done"** at the top left after each one.

| Button | What to do |
|---|---|
| **1 Basic check (app)** | Just wait (about 1 minute). |
| **2 Car data via helper** | The first time, the car may show **"Allow USB debugging?"** → tick **Always allow** → **OK**, then press button 2 again. |
| **3 Camera picture via helper** | **Close OverDrive's live view and the car's 360 view first.** Wait up to 1 minute. |
| **4 Camera picture from app** | Same as 3, but without the helper. It's fine if this one fails. |
| **5 Watch signals 30 s** | After you press it, wait 5 s, then: flick **LEFT** indicator for 3 s, **RIGHT** for 3 s, open and close a door, press the brake. |
| **6 Voice test** | Listen: do you hear English, then Thai, from the car speakers? |
| **7 / 8 / 8b / 9 Open in a window** | Look: does the app open in a **window** or **full screen**? For **8 (Engine Sound)**, also tap the purple ghost button: does Ghost Race open inside the window? Then go back to ADAS Probe. |

## C. Control tests (car in P, watch the car)

Each one does a small change and undoes it. Note what you **see** happen.
Leave the top button at **"HELPER (shell)"**.

- Fan · Temperature · Recirculation · Air direction · Defrost · Purify · Driver window
- **Sunroof nudge:** moves it a little one way, then the other. If it isn't fully
  closed afterwards, close it with the car's own button.
- **Other cameras** (amber): only if you have time. It may briefly restart the 360 view.

If button 2 never connects, try control tests with the top button switched to **APP**.

## D. Send me the results (phone, Termux)

1. Copy the reports and pictures to your phone:
   ```
   adb pull /sdcard/Download/atto3_probe ~/storage/downloads/atto3_probe
   ```
2. Zip them:
   ```
   cd ~/storage/downloads && zip -r atto3_probe.zip atto3_probe
   ```
3. Upload `atto3_probe.zip` in our chat, and add a few words on what you saw
   (fan changed? window moved? which way the sunroof went? apps in windows?).

## If button 2 fails: run the helper from Termux

```
adb shell 'CLASSPATH=$(pm path com.airodynamic.adasprobe | cut -d: -f2) app_process /system/bin com.airodynamic.adasprobe.ShellMain read'
```
Replace `read` with `camera`, `watch`, `fan`, `temp`, `recirc`, `airdir`, `defrost`,
`purify`, `window` or `sunroof` for the other tests.
