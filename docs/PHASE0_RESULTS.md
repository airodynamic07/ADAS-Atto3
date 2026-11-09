# Phase 0 results (car run 1–2, 2026-11-09)

| Item | Result |
|---|---|
| Car data (speed, gear, all BYD devices) | **Works from the normal app** (permission-bypass context). Gear code 1 = P, speed 0.0. |
| Car data from the shell helper | Fails: `ActivityThread.systemMain()` throws as shell → no context → every device NPEs. Not needed. |
| Cameras | **Works from the normal app** by loading `/system/framework/bmmcamera.jar` with a class loader. 360 strip `pano_h` = **camera id 1** (`cam_sort = rear:0;pano_h:1;`), 5120×960. |
| Slice order | Confirmed from pictures: slice1 = **rear** (plate), slice2 = **left**, slice3 = **right** (mirror cams, wiper visible toward front), slice4 = **front**. Same as OverDrive's mapping. |
| Windshield camera | Not in the camera list. |
| Wireless ADB (127.0.0.1:5555) | Open; helper starts. Kept only as a fallback. |
| Reports to Download | Refused → reports now go to the app's own folder. |
| USB stick | Not at the standard Android location → v0.0.5 searches more places / copies via helper. |
| SDK feature ids seen | PM2.5 inside **and outside** (`PM2P5_VALUE_IN/OUT`), turn signals, tyres, A/C recirc/defrost/purify, ambient (`SET_ATMOSPHERE_LAMP_*`), sunroof position/state (read). No sunroof open/close feature id; `voiceCtlMoonRoof` still to test. These are SDK constants, not yet proof the car supports them. |

## Run 1 app files (read)
| Item | Result |
|---|---|
| Camera speed | open 584 ms, first frame 639 ms, **~11.6 fps** for the 5120×960 strip (`setCameraFps(15)` refused) |
| Readings | speed 0.0, gear 1 (P), battery **55 %**, range **245 km**, outside **35 °C**, A/C set **24 °C**, fan 2, recirc state 1, purify 2 (off) |
| Tyres | `getTyrePressureValue(1..4)` = 275/275/275/277 (kPa → 2.75 bar) |
| PM2.5 | `getPM2p5Value()` = [inside, outside] e.g. [9, 19] — **both available from the car** |
| Radar | `getAllRadarDistance()` = 9 values, 150 = nothing near |
| Turn signal | `getTurnLightFlashState()` (OverDrive: 2/3 left, 4/5 right, 6/7 hazard). Stayed 1 in the watch — signals probably not flicked; re-test |
| Doors / brake | no change seen in the watch — re-test |
| Cabin temperature | **no reading found** (`getTemprature(3)` = 65535). Release heat needs another source |
| Sunroof | reads position/state; commands **`setMoonRoofState(int)`, `setMoonRoofAndSunshadeStop()`, `voiceCtlMoonRoof(int)`** exist |
| Ambient light | **`setIALColor`, `setIALBrightness`, `setIALArea`** exist on the Setting device (colour 25, brightness 5 now) |
| Voice | **no text-to-speech engine** on the car → ship pre-recorded Thai/English clips |
| Google apps | not installed natively; the user runs them in **GBox** |
| Engine Sound | installed v1.0.56 (= old GitHub build; the user's newer build is lost for now) |
| Widevine | L3 |

## Run 2 ("Run everything", v0.0.10)
| Item | Result |
|---|---|
| **Turn signals** | `Light.getTurnLightFlashState()` 1 = off, **2 = left**, **4 = right** (getTurnLightState 3/4/5 too) ✅ |
| **Doors** | `Bodywork.getDoorState(1)` 0 → 1 → 0 (driver door) ✅ |
| **Brake** | `Gearbox.getBrakePedalState()` 0/1 and `Speed.getBrakeDeepness()` 0–62 ✅ |
| Beep through speakers | heard ✅ (no TTS engine → use recorded voice clips) |
| Temperature +1 | driver saw it change ✅ |
| Fan, air direction, purify, ambient colour/brightness, defrost, sunroof | car **accepted** every command (result 0) and read-backs changed (fan 3→4, wind mode 1→6, purify 2→1, IAL colour 20→10, brightness 5→1); sunroof position unchanged — need the driver's eyes (round 2) |
| Driver window | `setWindowOpenPercent` **does not exist** on this firmware → use `setAllWindowState(LF,RF,LR,RR)` 1=open 2=close 3=stop (round 2) |
| Windows for other apps | `FEATURE_FREEFORM_WINDOW_MANAGEMENT = false`, freeform setting off → round 2 switches it on via ADB and re-tests after a restart |
| GBox | each Google app is its own package: `com.gbox.com.google.android.apps.maps`, `…youtube`, `…googlequicksearchbox`, `com.gbox.com.spotify.music`, plus `com.gbox.android`. Native Maps/YouTube/Gemini are also installed (without Play services). |
| Home screen now | Kinex (`com.lexwah.kinex`); Dudu Launcher Pro also installed |
| USB stick | found at `/storage/0CC0-71F1` (KINGSTON); copied via the helper ✅ |
| RAM | 7.6 GB total |

## Round 2 (v0.0.11) — driver's report
| Item | Result |
|---|---|
| Self-update | works: the car reached GitHub and compared builds ✅ |
| Driver window | `setAllWindowState(1,0,0,0)` moved the **front-LEFT** window. Thailand is right-hand drive → **driver = RF = second value** |
| Recirculation | `getAcCycleMode`: **1 = inside air (recirc), 0 = outside air**. Writing 0 switched to outside air ✅; the test then didn't switch back (it assumed 1 = on). |
| Ambient light, defrost, sunroof | commands accepted; sunroof position read-back unchanged (driver's answers file was damaged in the archive) |
| GBox in a window | **full screen** — other apps can't be shown in our windows on this car |
| Engine Sound | fixed (`??` not supported by the car's old WebView) — Start and engine list work ✅ |
| Ghost Race inside Engine Sound | does **not** open — likely the same old-WebView problem on the Ghost Race website |

## Design consequences
- App windows: other apps open **full screen** (with a way back to the launcher). Only our own panels live in the 3 windows.
- Middle window: host **Engine Sound's own page** inside the launcher and feed it the car's real speed and accelerator pedal from the BYD data (better than the OBD dongle). Needs a newer WebView or ES2018-safe pages.

## Still to collect
- `app_basic.txt`, `app_car_methods.txt`, `app_camera.txt` (camera FPS), `app_watch.txt` (turn signal / door / brake readings), `app_voice.txt`
- Control tests in **APP** mode (the helper versions had no car context), plus what the user saw
- Apps in windows (Maps, Engine Sound + Ghost Race, YouTube, Netflix)

## Decision
Build on the **normal app** for both cameras and car data. The ADB helper is not needed for the
launcher — simpler and nothing to approve on the car.
