#!/usr/bin/env python3
"""Check a built APK against the hard constraints of the specification.

A compiling build says nothing about whether the package still obeys the
product rules. These are the ones that can be verified without a device, and
each is something that would quietly break the product if it regressed:

  * No IP networking permission at all. Section 2 and 12 require a
    Bluetooth-only transport; the absence of INTERNET is what makes that
    structural rather than a promise, and a stray dependency pulling the
    permission in through manifest merging is exactly the kind of thing nobody
    notices by hand.
  * The Bluetooth, microphone and notification permissions the app cannot work
    without.
  * minSdk 29, which is what AudioPlaybackCapture needs.
  * Both services declare the foreground service types they actually claim at
    runtime. Android refuses a claim that the manifest does not cover, so a
    missing bit here means a service that cannot start — on a device, in the
    field, not at build time.
  * Neither service is exported.

Usage: check_apk.py <path-to-apk>
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
from pathlib import Path

# android:foregroundServiceType bits, from R.attr.
FGS_DATA_SYNC = 1 << 0
FGS_MEDIA_PLAYBACK = 1 << 1
FGS_PHONE_CALL = 1 << 2
FGS_LOCATION = 1 << 3
FGS_CONNECTED_DEVICE = 1 << 4
FGS_MEDIA_PROJECTION = 1 << 5
FGS_CAMERA = 1 << 6
FGS_MICROPHONE = 1 << 7

FGS_NAMES = {
    FGS_DATA_SYNC: "dataSync",
    FGS_MEDIA_PLAYBACK: "mediaPlayback",
    FGS_PHONE_CALL: "phoneCall",
    FGS_LOCATION: "location",
    FGS_CONNECTED_DEVICE: "connectedDevice",
    FGS_MEDIA_PROJECTION: "mediaProjection",
    FGS_CAMERA: "camera",
    FGS_MICROPHONE: "microphone",
}

FORBIDDEN_PERMISSIONS = [
    # The whole product is "no IP path, by construction".
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.ACCESS_WIFI_STATE",
    "android.permission.CHANGE_WIFI_STATE",
    "android.permission.CHANGE_NETWORK_STATE",
]

REQUIRED_PERMISSIONS = [
    "android.permission.BLUETOOTH_CONNECT",
    "android.permission.BLUETOOTH_SCAN",
    "android.permission.RECORD_AUDIO",
    "android.permission.MODIFY_AUDIO_SETTINGS",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
    "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
    "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE",
    "android.permission.FOREGROUND_SERVICE_MICROPHONE",
    "android.permission.POST_NOTIFICATIONS",
]

EXPECTED_MIN_SDK = 29

# service name -> bits it must declare
REQUIRED_SERVICE_TYPES = {
    "com.dualstream.uniter.service.ReceiverService":
        FGS_MEDIA_PLAYBACK | FGS_CONNECTED_DEVICE | FGS_MICROPHONE,
    "com.dualstream.uniter.service.TransmitterService":
        FGS_MEDIA_PROJECTION | FGS_CONNECTED_DEVICE | FGS_MICROPHONE,
}


# aapt2 prints attributes as
#   A: http://schemas.android.com/apk/res/android:name(0x01010003)="x" (Raw: "x")
# while older aapt prints
#   A: android:name="x" (Raw: "x")
# so the resource id is optional, and integers may be hex or decimal.
_INT = r"(0x[0-9a-fA-F]+|-?\d+)"
ATTR_NAME_RE = re.compile(r'android:name(?:\([^)]*\))?="([^"]+)"')
ATTR_FGS_RE = re.compile(
    r"android:foregroundServiceType(?:\([^)]*\))?=(?:\(type [^)]*\))?" + _INT
)
ATTR_EXPORTED_RE = re.compile(
    r"android:exported(?:\([^)]*\))?=(?:\(type [^)]*\))?" + _INT
)


def parse_int(text: str) -> int:
    return int(text, 16) if text.lower().startswith("0x") else int(text)


def describe_bits(mask: int) -> str:
    names = [name for bit, name in sorted(FGS_NAMES.items()) if mask & bit]
    leftover = mask & ~sum(FGS_NAMES)
    if leftover:
        names.append(hex(leftover))
    return "|".join(names) if names else "none"


def find_aapt2() -> str:
    """Locates aapt2 in the SDK, preferring the newest build-tools."""
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        root = os.environ.get(var)
        if not root:
            continue
        build_tools = Path(root) / "build-tools"
        if not build_tools.is_dir():
            continue
        candidates = sorted(
            (p for p in build_tools.glob("*/aapt2") if p.is_file()),
            key=lambda p: [int(x) if x.isdigit() else 0
                           for x in p.parent.name.split(".")],
        )
        if candidates:
            return str(candidates[-1])
    # Fall back to anything on PATH.
    for name in ("aapt2", "aapt"):
        found = subprocess.run(["which", name], capture_output=True, text=True)
        if found.returncode == 0:
            return found.stdout.strip()
    sys.exit("could not find aapt2; is the Android SDK installed?")


def run(cmd: list[str]) -> str:
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        sys.exit(f"{' '.join(cmd)} failed:\n{result.stderr}")
    return result.stdout


def parse_services(xmltree: str) -> dict[str, dict]:
    """Pulls each <service> element's name, type mask and exported flag."""
    services: dict[str, dict] = {}
    current: dict | None = None
    # aapt2 indents by element depth; a new "E: service" starts a block and any
    # other "E:" at the same or lower indent ends it.
    for line in xmltree.splitlines():
        stripped = line.strip()
        m_elem = re.match(r"E: (\S+)", stripped)
        if m_elem:
            # Any new element ends the block being collected, including the next
            # <service>: two services in a row must both be kept.
            if current is not None and current["name"]:
                services[current["name"]] = current
            current = (
                {"name": None, "types": 0, "exported": None}
                if m_elem.group(1) == "service"
                else None
            )
            continue
        if current is None:
            continue
        m_name = re.search(ATTR_NAME_RE, stripped)
        if m_name:
            current["name"] = m_name.group(1)
            continue
        m_types = re.search(ATTR_FGS_RE, stripped)
        if m_types:
            current["types"] = parse_int(m_types.group(1))
            continue
        m_exported = re.search(ATTR_EXPORTED_RE, stripped)
        if m_exported:
            current["exported"] = parse_int(m_exported.group(1)) != 0
    if current is not None and current["name"]:
        services[current["name"]] = current
    return services


SAMPLE_AAPT2 = """N: android=http://schemas.android.com/apk/res/android
  E: manifest (line=2)
    A: android:versionCode(0x0101021b)=(type 0x10)0x1
    E: uses-permission (line=20)
      A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.BLUETOOTH_CONNECT" (Raw: "android.permission.BLUETOOTH_CONNECT")
    E: application (line=40)
      A: http://schemas.android.com/apk/res/android:label(0x01010001)=@0x7f0e0000
      E: activity (line=43)
        A: http://schemas.android.com/apk/res/android:name(0x01010003)="com.dualstream.uniter.ui.MainActivity" (Raw: "com.dualstream.uniter.ui.MainActivity")
        A: http://schemas.android.com/apk/res/android:exported(0x01010010)=(type 0x12)0xffffffff
        E: intent-filter (line=48)
          E: action (line=49)
            A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.intent.action.MAIN" (Raw: "android.intent.action.MAIN")
      E: service (line=58)
        A: http://schemas.android.com/apk/res/android:name(0x01010003)="com.dualstream.uniter.service.ReceiverService" (Raw: "com.dualstream.uniter.service.ReceiverService")
        A: http://schemas.android.com/apk/res/android:exported(0x01010010)=(type 0x12)0x0
        A: http://schemas.android.com/apk/res/android:foregroundServiceType(0x0101063f)=(type 0x11)0x92
      E: service (line=63)
        A: http://schemas.android.com/apk/res/android:name(0x01010003)="com.dualstream.uniter.service.TransmitterService" (Raw: "com.dualstream.uniter.service.TransmitterService")
        A: http://schemas.android.com/apk/res/android:exported(0x01010010)=(type 0x12)0x0
        A: http://schemas.android.com/apk/res/android:foregroundServiceType(0x0101063f)=(type 0x11)0xb0
      E: receiver (line=69)
        A: http://schemas.android.com/apk/res/android:name(0x01010003)="com.dualstream.uniter.service.BtStateReceiver" (Raw: "com.dualstream.uniter.service.BtStateReceiver")
        A: http://schemas.android.com/apk/res/android:exported(0x01010010)=(type 0x12)0x0
        E: intent-filter (line=72)
          E: action (line=73)
            A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.bluetooth.adapter.action.STATE_CHANGED" (Raw: "android.bluetooth.adapter.action.STATE_CHANGED")
"""

# The shape older aapt used, in case a runner's build-tools only ships aapt.
SAMPLE_LEGACY_AAPT = """N: android=http://schemas.android.com/apk/res/android
  E: manifest (line=2)
    E: application (line=40)
      E: service (line=58)
        A: android:name="com.dualstream.uniter.service.ReceiverService" (Raw: "...")
        A: android:exported=(type 0x12)0x0
        A: android:foregroundServiceType=(type 0x10)146
"""


def self_test() -> int:
    """Checks the manifest parser against recorded aapt output shapes.

    The parser reads a text format that differs between aapt and aapt2
    versions, which is exactly the sort of thing that silently starts matching
    nothing and turns every assertion below into a pass. So it gets its own
    test, and CI runs it before trusting the real check.
    """
    problems = []

    services = parse_services(SAMPLE_AAPT2)
    recv = services.get("com.dualstream.uniter.service.ReceiverService")
    tx = services.get("com.dualstream.uniter.service.TransmitterService")

    if not recv:
        problems.append("aapt2 sample: receiver service not found")
    else:
        if recv["types"] != (FGS_MEDIA_PLAYBACK | FGS_CONNECTED_DEVICE | FGS_MICROPHONE):
            problems.append(f"aapt2 sample: receiver types = {describe_bits(recv['types'])}")
        if recv["exported"] is not False:
            problems.append("aapt2 sample: receiver exported flag misread")
    if not tx:
        problems.append("aapt2 sample: transmitter service not found")
    elif tx["types"] != (FGS_MEDIA_PROJECTION | FGS_CONNECTED_DEVICE | FGS_MICROPHONE):
        problems.append(f"aapt2 sample: transmitter types = {describe_bits(tx['types'])}")

    # The activity and the receiver must not be mistaken for services, and an
    # intent-filter nested in an element must not leak its action name upwards.
    for unwanted in (
        "com.dualstream.uniter.ui.MainActivity",
        "com.dualstream.uniter.service.BtStateReceiver",
        "android.intent.action.MAIN",
    ):
        if unwanted in services:
            problems.append(f"aapt2 sample: {unwanted} was parsed as a service")

    legacy = parse_services(SAMPLE_LEGACY_AAPT)
    lrecv = legacy.get("com.dualstream.uniter.service.ReceiverService")
    if not lrecv:
        problems.append("legacy sample: receiver service not found")
    elif lrecv["types"] != (FGS_MEDIA_PLAYBACK | FGS_CONNECTED_DEVICE | FGS_MICROPHONE):
        problems.append(f"legacy sample: types = {describe_bits(lrecv['types'])}")

    if describe_bits(FGS_MICROPHONE | FGS_CONNECTED_DEVICE) != "connectedDevice|microphone":
        problems.append("describe_bits produced an unexpected label")
    if parse_int("0x92") != 146 or parse_int("146") != 146:
        problems.append("parse_int cannot read both hex and decimal")

    if problems:
        print("parser self-test FAILED:", file=sys.stderr)
        for p in problems:
            print(f"  - {p}", file=sys.stderr)
        return 1
    print("parser self-test passed")
    return 0


def main() -> int:
    if len(sys.argv) == 2 and sys.argv[1] == "--self-test":
        return self_test()
    if len(sys.argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    apk = Path(sys.argv[1])
    if not apk.is_file():
        sys.exit(f"no such APK: {apk}")

    # The manifest parser reads a text format that varies between aapt versions.
    # If it silently stops matching, every assertion below turns into a pass, so
    # it is verified before it is trusted.
    if self_test() != 0:
        return 1

    aapt2 = find_aapt2()
    print(f"using {aapt2}")
    badging = run([aapt2, "dump", "badging", str(apk)])
    xmltree = run([aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", str(apk)])

    permissions = set(re.findall(r"uses-permission: name='([^']+)'", badging))
    failures: list[str] = []

    # --- no IP path, by construction
    for perm in FORBIDDEN_PERMISSIONS:
        if perm in permissions:
            failures.append(
                f"{perm} is in the package. The specification requires a "
                f"Bluetooth-only transport with no IP path; if a dependency "
                f"merged this in, remove it with tools:node=\"remove\"."
            )

    # --- permissions the app cannot work without
    for perm in REQUIRED_PERMISSIONS:
        if perm not in permissions:
            failures.append(f"{perm} is missing from the package")

    # --- minSdk
    m = re.search(r"sdkVersion:'(\d+)'", badging)
    if not m:
        failures.append("could not read minSdkVersion from the package")
    elif int(m.group(1)) != EXPECTED_MIN_SDK:
        failures.append(
            f"minSdkVersion is {m.group(1)}, expected {EXPECTED_MIN_SDK} "
            f"(AudioPlaybackCapture needs API 29)"
        )

    # --- foreground service types, and that the services stay internal
    services = parse_services(xmltree)
    for name, required in REQUIRED_SERVICE_TYPES.items():
        found = services.get(name)
        if not found:
            failures.append(f"{name} is not declared in the manifest")
            continue
        missing = required & ~found["types"]
        if missing:
            failures.append(
                f"{name} does not declare {describe_bits(missing)}; it declares "
                f"{describe_bits(found['types'])}. Android refuses a foreground "
                f"claim the manifest does not cover, so this service would fail "
                f"to start on a device."
            )
        if found["exported"]:
            failures.append(f"{name} is exported; it must stay internal")

    print(f"permissions: {len(permissions)}")
    for name, info in sorted(services.items()):
        print(f"service {name}: types={describe_bits(info['types'])} "
              f"exported={info['exported']}")

    if failures:
        print("\nFAILED:", file=sys.stderr)
        for f in failures:
            print(f"  - {f}", file=sys.stderr)
        return 1

    print("\nall package checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
