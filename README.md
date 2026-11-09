# ADAS-Atto3

Launcher + ADAS warnings for the **BYD Atto 3 (DiLink 3.x)**, using the car's own
360° cameras and on-device AI. The full plan is in [BRIEF.md](BRIEF.md).

**Hard rules:** warnings only, never sends commands to brakes, steering, lane keeping
or any driving system (checked on every build by `tools/safety-check.sh`).
Full-screen video only in P. Updates never reset your settings.

## Status
| Phase | What | State |
|---|---|---|
| 0 | Probe app: cameras, car data, unknowns report | **ready to test** |
| 1 | Front camera + YOLO boxes + FPS | waiting for Phase 0 |
| 2 | 4 cameras, Map view, turn-signal camera, Top view | |
| 3 | Warnings + my-speed-limit voice | |
| 4 | Launcher shell, windows, themes | |
| 5 | Info window + Settings | |

## Getting the APK
GitHub Actions builds it on every push to `main`.
- **Releases** (right side of the repo page): `adas-probe.apk`
- or **Actions** tab → newest run → *Artifacts*

How to test Phase 0 in the car: **[docs/PHASE0_STEPS.md](docs/PHASE0_STEPS.md)**

## Project layout
- `probe/` — Phase 0 probe app (Java, no libraries except `dadb` for ADB)
- `tools/safety-check.sh` — fails the build if driving-system commands appear
- `keystore/` — fixed signing key, so every new APK installs as an update
  (personal sideload key; it is not a store key)

## Credits
Camera and BYD car-data techniques are from
[OverDrive](https://github.com/yash-srivastava/Overdrive-release) by Yash Srivastava (MIT) — see [NOTICE](NOTICE).
