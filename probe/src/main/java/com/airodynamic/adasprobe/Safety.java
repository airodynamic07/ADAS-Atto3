package com.airodynamic.adasprobe;

import java.util.regex.Pattern;

/**
 * HARD RULE 1: warnings only. This app never sends anything to the car's
 * driving systems (brakes, steering, lane keeping, stability, gear, drive modes).
 *
 * Every call that changes something in the car goes through {@link #checkCommand}.
 * The GitHub build also fails if forbidden method names appear in the source.
 */
public final class Safety {
    private Safety() {}

    /** Car device classes we only ever READ from. Any write to these is refused. */
    private static final Pattern READ_ONLY_DEVICES = Pattern.compile(
            "(?i).*\\.(adas|dipilot|gearbox|speed|engine|special|safetybelt|instrument|radar|setting)\\..*");

    /** Method names that touch driving systems. Refused on every device. */
    private static final Pattern FORBIDDEN_METHODS = Pattern.compile(
            "(?i).*(aeb|brake|lks|lane|steer|esp|abs|tcs|drift|drive|gear|epb|park|cruise|acc\\b|"
            + "pilot|sla|bsd|rca|fcw|collision|speedlimit|torque|energymode|operationmode|roadsurface).*");

    /** Only these write methods are allowed at all (comfort features only). */
    private static final Pattern ALLOWED_WRITES = Pattern.compile(
            "set(AcTemperature|AcWindLevel|AcWindMode|AcCycleMode|AutoCleanAirState|AcDefrostState"
            + "|WindowOpenPercent|IALColor|IALBrightness|MoonRoofAndSunshadeStop)|voiceCtlMoonRoof|set");

    /** The Setting device is read-only except for the interior ambient light (IAL). */
    private static final Pattern SETTING_EXCEPTIONS = Pattern.compile("setIAL(Color|Brightness)");

    public static void checkCommand(Object device, String method) {
        String cls = device == null ? "" : device.getClass().getName();
        boolean ambientOnSetting = cls.endsWith("BYDAutoSettingDevice") && SETTING_EXCEPTIONS.matcher(method).matches();
        if (READ_ONLY_DEVICES.matcher(cls).matches() && !ambientOnSetting) {
            throw new SecurityException("Refused: " + cls + " is read-only in this app");
        }
        if (FORBIDDEN_METHODS.matcher(method).matches()) {
            throw new SecurityException("Refused: " + method + " touches a driving system");
        }
        if (!ALLOWED_WRITES.matcher(method).matches()) {
            throw new SecurityException("Refused: " + method + " is not on the allowed list");
        }
    }
}
