# Project brief: Atto 3 ADAS launcher

Paste this into Claude Code as the first message (or save it in the repo as `BRIEF.md`).

## What I want to build
An Android app that runs **on my BYD Atto 3's DiLink head unit** as the **home screen (launcher)**. It uses the car's own 360° cameras plus on-device AI for driving warnings and a live "around you" view. It also shows car info and controls, hosts other apps in windows, and has a full Settings area.

The full design is on my canvas: https://claude.ai/artifact/FkP5MFda4gtoDrAQVPC16J. The **blue (dark) theme screens** are the reference. Match their layout, colours and wording.

## Hard rules
1. **Warnings only.** Never brake, steer, change lanes or send any command to the car's driving systems. The car's own AEB and lane keeping do that.
2. **Full-screen video only in P.** If the car leaves P, the video drops back to its window (or pauses).
3. **New versions must keep my saved settings.** Never reset them to defaults on update.
4. I'm not a professional Android developer. Give me short, step-by-step instructions for anything I do in the car or on my phone.

## My car and setup
- BYD Atto 3, Thai market, DiLink 3.x (Android 10-based; confirm in Phase 0).
- **ADB is already unlocked.** **OverDrive is installed and working** on the car.
- I sideload with Termux on my Android phone: `adb connect <car_ip>:5555`, then `adb install`.
- GitHub username: airodynamic07. Set this up like my `atto3-engine-sound` repo: **GitHub Actions builds the APK**.

## Reuse OverDrive's code (MIT licence, keep its notice)
Source: https://github.com/yash-srivastava/Overdrive-release

- **Cameras (DiLink 3/4):** hidden class `android.hardware.AVMCamera`, called via reflection (no root). All 4 cameras arrive as one **5120×960 strip**, cut into slices (`PanoramicSlice.java`).
  - Key files: `app/src/main/java/com/overdrive/app/camera/` (`PanoramicCameraGpu.java`, `CameraConfigResolver.java`, `CameraProfiles.java`, `BydCameraCoordinator.java`).
  - Some models also have a **WINDSHIELD** camera (id 0). Check whether mine does.
  - We must coexist with OverDrive and the OEM 360 view (camera arbitration).
- **AI:** `ai/YoloDetector.kt` with `yolo11n.tflite` / `yolo26n.tflite` (`assets/models/`).
  - On this chip: CPU ≈ 200–300 ms per frame, GPU delegate ≈ 50–80 ms. We don't record video, so **use the GPU delegate**.
- **Car data and commands:** BYD SDK stubs in `stubs-bydauto/android/hardware/bydauto/`, and command routing in `byd/routing/VehicleCommandRouter.java` and `byd/BydDataCollector.java`. Confirmed in OverDrive:
  - speed, accelerator and brake pedal
  - parking radar
  - **turn signals** (`LIGHT_LEFT_TURN_SIGNAL` / `RIGHT`)
  - **gear position**
  - **door state**
  - outside temperature
  - battery
  - **A/C power, set temperature, fan speed**
  - **windows**
  - **speak / play audio** through the car speakers
  - headlight mode
- **Avoid** `libod.so` (closed source, checks OverDrive's signing key). Write our own fisheye projection and the Top (bird's-eye) stitch.

## Launcher layout (1280×720 landscape, scale to the real screen)
- **Top strip:** time and date on the left. On the right: **theme dots** (blue / white / red) and a **Settings** button.
- **Three equal windows** (each ~400 px wide).
  - **Each window has a bar** with:
    - its **3 quick-app buttons**
    - **choose apps**: opens a picker with 3 slots plus a grid of installed apps (names only, no icons)
    - **⤢ full screen**
    - **×**: closes the open app and returns the window to its default
  - **Defaults:** left = **ADAS**, middle = **Ghost Race**, right = **Info**.
  - Other apps (Google Maps, YouTube, Netflix…) open inside a window using Android multi-window / freeform. Check this works on DiLink in Phase 0.
- **Left (ADAS):**
  - speed (large) and battery % / range with a bar
  - status line ("Around you · 4 cameras live", "◀ Left signal · auto view")
  - camera buttons **Map · Top · Front · Rear · Left · Right**
  - the view itself:
    - **Map** = top-down icons for cars, motorbikes and people, with distances
    - **Front / Rear / Left / Right** = live camera with AI boxes
    - **Top** = stitched bird's-eye view
  - alert bar
- **Middle:** Ghost Race (my web app, ghostrace.airodynamic.workers.dev) by default.
- **Right (Info), top to bottom:**
  - the window bar
  - a **Gemini card**: last reply, **Ask** (voice) and **⤢ Open** (full chat in the middle window)
  - a **Climate** panel:
    - On, A/C, Auto, Recirc, Purify
    - temperature − / +
    - fan − / +
    - air direction: Face, Both, Feet, Feet+Wind, Defrost, Rear
  - a bottom row of equal width:
    - **tyre pressures** (car outline, low tyre in amber)
    - **outside and inside temperature cards**, each with its own PM2.5 reading
- **Themes:** Dark blue (default), White minimal, Black red sport.
  - **Auto Sport:** switches to black-red above a set speed (default 120 km/h) and back 10 km/h below.
  - Colours: teal `#3DD9C1` safe, amber `#FFB020` caution, red `#FF4D3D` danger, grey other traffic, purple `#B8A4FF` person. Blue theme accent `#4DA3FF` on `#05080F`.
- **Turn signal:** the ADAS window auto-switches to the left or right rear camera, with 3 / 6 / 10 m guide lines, then returns to Map a set time after the signal turns off.
- **Google Maps:** the real Maps app, following the system dark/light mode. Its own colours can't be themed.

## Settings (left menu, one page per section)
1. **Driving & alerts:**
   - turn-signal camera on/off and return delay
   - Auto Sport on/off and switch speed
   - **My speed limit**: voice alert on/off, limit (default 100 km/h), Thai or English voice. This is **my own set limit, not the road limit.**
2. **Ambient light:**
   - over my speed limit: on/off, colour, style (Solid / Breathe / Flash)
   - gear change: a colour for each of P / R / N / D, and a style
   - door open: on/off, colour, style
3. **Sunroof & fresh air:**
   - sunroof schedule: time, days, Tilt / Half / Full
   - auto fresh air: every N min, for M min
4. **Release heat** (while parked):
   - opens the sunroof a little (Tilt / 10% / 20%) and all windows (e.g. 10%)
   - **when the cabin goes above a set temperature** (e.g. 45°C), and closes again when it drops to a lower one (e.g. 38°C)
   - active hours
   - closes on rain, and when I unlock or start the car
5. **Launcher & windows:**
   - Set as default launcher
   - swap the 3 windows (⇄)
   - edit each window's 3 apps
6. **Google account:**
   - signed-in account, Switch account, Sign out
   - use the account for Gemini / Maps / YouTube
   - sign in by scanning a QR code with my phone
7. **Bills & payments:**
   - reminders (name, due date, remind N days before)
   - **Scan to pay** with a generated PromptPay QR (account name, amount), and Mark as paid
8. **About:** version and build, DiLink version, OverDrive credit, AI model, repo, Check for updates

## Gemini
DiLink 3 probably has no Google Play services, so call the **Gemini API** directly with the user's Google sign-in (or an AI Studio key as a fallback). Include live car data in the context, so questions like "is my tyre pressure OK?" work.

## Known unknowns (test in Phase 0 before building on them)
- Running while the car is **parked and off** (Release heat, sunroof schedule): a background service (12V drain) or BYD cloud commands.
- **Sunroof** control.
- **Ambient light** control (OverDrive only does this on DiLink 5).
- **Recirc, air direction, defrost, purify.**
- **Rain sensor** (fallback: online weather).
- **Outside PM2.5** (fallback: online air-quality data for the GPS location).
- **Freeform windows** for other apps.
- **Google Maps / Netflix** running on DiLink (Netflix may refuse without certified DRM).

## Build in phases (one at a time; I test in the car after each)
- **Phase 0, probe app:**
  - list cameras and ids, the DiLink and Android version, and whether a windshield camera exists
  - save one frame from each camera
  - test each item in Known unknowns, and print a report
- **Phase 1:** front camera + YOLO boxes + FPS overlay. Go / no-go on performance.
- **Phase 2:** all 4 cameras, Map view (icons and distances), turn-signal pop-up, Top view.
- **Phase 3:** warnings and my-speed-limit voice alert.
- **Phase 4:** launcher shell: 3 windows, window bars, app picker, full screen (P only), themes, Auto Sport.
- **Phase 5:** Info window (climate, tyres, temperatures and PM2.5, Gemini) and Settings, hiding anything Phase 0 showed is impossible.

Start with Phase 0. First, read OverDrive's camera and BYD SDK code and tell me in plain language how you'll open the cameras and read car data on my car.
