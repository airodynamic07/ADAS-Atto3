# Phase 0: testing the probe app in the car

## Easiest way (v0.0.6 and newer)
1. Car ON, in P, connected to Wi-Fi (e.g. your phone's hotspot), USB stick plugged in.
2. Open **ADAS Probe** → tap **⟳ Update app from internet** (gets the newest version by itself).
3. Tap **▶ RUN EVERYTHING** and follow the questions on screen. It copies the results to the USB stick at the end.
4. At home: zip the `atto3_probe` folder from the USB stick and send it in the chat.

The sections below are the manual way.

Takes about 20 minutes. Park somewhere safe, **car in P**, car ON (ready).

## A. Install with a USB stick

1. On your phone or PC, download the newest **`adas-probe.apk`** from the repo's
   **Releases** page: https://github.com/airodynamic07/ADAS-Atto3/releases
2. Copy `adas-probe.apk` onto your USB stick (same way you did for OverDrive / Engine Sound).
3. Plug the USB stick into the car. Open the car's file manager, go to the USB stick,
   tap **`adas-probe.apk`** and tap **Install**. (Updating later: same steps; your data is kept.)
4. Open the app **ADAS Probe** from the car's app list.

## B. Read-only tests (safe)

Press the buttons in order and wait for **"Done"** at the top left after each one.

| Button | What to do |
|---|---|
| **1 Basic check (app)** | Just wait (about 1 minute). |
| **2 Car data via helper** | **Watch the car screen**: it may show **"Allow USB debugging?"** → tick **Always allow** → **OK**. It gives up by itself after about 1 minute; you can also press **■ Stop**. |
| **3 Camera picture via helper** | **Close OverDrive's live view and the car's 360 view first.** Wait up to 1 minute. |
| **4 Camera picture from app** | Same as 3, but without the helper. It's fine if this one fails. |
| **5 Watch signals 30 s** | After you press it, wait 5 s, then: flick **LEFT** indicator for 3 s, **RIGHT** for 3 s, open and close a door, press the brake. |
| **6 Voice test** | Listen: do you hear English, then Thai, from the car speakers? |
| **7 / 8 / 8b / 9 Open in a window** | Look: does the app open in a **window** or **full screen**? For **8 (Engine Sound)**, also tap the purple ghost button: does Ghost Race open inside the window? Then go back to ADAS Probe. |

## C. Control tests (car in P, watch the car)

Each one does a small change and undoes it. Note what you **see** happen.
Leave the top button at **"APP"** (the default).

- Fan · Temperature · Recirculation · Air direction · Defrost · Purify · Driver window
- **Sunroof nudge:** moves it a little one way, then the other. If it isn't fully
  closed afterwards, close it with the car's own button.
- **Other cameras** (amber): only if you have time. It may briefly restart the 360 view.

If button 2 connects, you can also try the control tests with the top button switched to **HELPER**.

## D. Send me the results

1. With the USB stick plugged into the car, press **"Copy all results to USB stick"** (bottom of the list).
2. Take the USB stick to your phone or PC. Open the folder
   **Android → data → com.airodynamic.adasprobe → files → atto3_probe**.
3. Zip that `atto3_probe` folder and upload it in our chat, with a few words on what you saw
   (fan changed? window moved? which way the sunroof went? apps in windows?).

## Only if button 2 fails AND you have Termux on your phone

```
adb shell 'CLASSPATH=$(pm path com.airodynamic.adasprobe | cut -d: -f2) app_process /system/bin com.airodynamic.adasprobe.ShellMain read'
```
Replace `read` with `camera`, `watch`, `fan`, `temp`, `recirc`, `airdir`, `defrost`,
`purify`, `window` or `sunroof` for the other tests.
