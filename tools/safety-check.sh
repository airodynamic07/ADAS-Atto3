#!/usr/bin/env bash
# HARD RULE 1 — warnings only. Fails the build if the source code mentions
# a command that could change the car's driving systems.
set -euo pipefail
cd "$(dirname "$0")/.."
PATTERN='set(AEB|AiEmergencyBrake|EmergencyBrake|LKS|LaneKeep|SteerAssis|ESP|ABS|TCS|Drift|SLA|BSD|RCA|FCW|DriveConfig|DriveMode|EnergyMode|OperationMode|RoadSurface|Gear|EPB|Cruise|Torque)|BYDAUTO_(ADAS|DIPILOT|DI_PILOT|ESP|GEARBOX|SPEED|ENGINE)[A-Z_]*_SET'
if grep -rnE "$PATTERN" --include='*.java' --include='*.kt' --include='*.xml' --include='*.kts' . \
     | grep -v '^./tools/'; then
  echo "SAFETY CHECK FAILED: driving-system command found above. This app must only warn." >&2
  exit 1
fi
echo "Safety check passed: no driving-system commands in the source."
