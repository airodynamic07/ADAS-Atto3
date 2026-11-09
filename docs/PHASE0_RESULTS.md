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

## Still to collect
- `app_basic.txt`, `app_car_methods.txt`, `app_camera.txt` (camera FPS), `app_watch.txt` (turn signal / door / brake readings), `app_voice.txt`
- Control tests in **APP** mode (the helper versions had no car context), plus what the user saw
- Apps in windows (Maps, Engine Sound + Ghost Race, YouTube, Netflix)

## Decision
Build on the **normal app** for both cameras and car data. The ADB helper is not needed for the
launcher — simpler and nothing to approve on the car.
