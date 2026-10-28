"""Sanity checks before a phone's tests run, so a broken setup fails early (D15).

testbed_session.py keeps the phones awake and their Bluetooth off, the suite
doesn't change those settings, it only checks them.
"""

import re

from mobly.controllers.android_device_lib.adb import AdbProxy

from lib import baseline
from lib import bt_oracle
from lib import device_state

# Bit of stay_on_while_plugged_in for USB
STAY_ON_USB = 2


def phone_problems(adb, other_serials=()):
    """What keeps the phone from being tested, without touching its Bluetooth."""
    problems = []
    if not device_state.is_powered(adb):
        problems.append("not plugged in")
    stay_on = device_state.get_setting(adb, "global", "stay_on_while_plugged_in")
    if not stay_on or not int(stay_on) & STAY_ON_USB:
        problems.append(f"stay_on_while_plugged_in is {stay_on}, it doesn't keep the screen on over USB "
                        "(is testbed_session.py running?)")
    if not device_state.is_awake(adb):
        problems.append("screen off")
    if not device_state.is_unlocked(adb):
        problems.append("locked")
    for serial in other_serials:
        if device_state.is_bluetooth_on(AdbProxy(serial)):
            problems.append(f"Bluetooth is on on {serial}, the headset could connect to it")
    return problems


def is_bonded(adb, address):
    """Whether the phone is paired with the device. Only listed while Bluetooth is on."""
    out = device_state.shell(adb, "dumpsys bluetooth_manager | sed -n '/Bonded devices:/,/^$/p'", timeout=30)
    return any(bt_oracle.same_device(a, address) for a in re.findall(r"[0-9A-FX]{2}(?::[0-9A-FX]{2}){5}", out.upper()))


def connect_problems(adb, headset):
    """Turns the phone's Bluetooth on and checks that the headset connects over A2DP.

    Uses the connect fixture: the Pixels connect on their own when Bluetooth
    turns on, the S24 when the headset reconnects to it, which works only if
    it was the last phone the headset was connected to, or else from its
    Bluetooth settings.
    """
    device_state.set_bluetooth(adb, True)
    if not is_bonded(adb, headset.address):
        return [f"not paired with {headset.name}"]
    try:
        baseline.connect(adb, headset)
    except TimeoutError:
        return [f"{headset.name} didn't connect over A2DP, neither when Bluetooth turned on, nor after it "
                "reconnected, nor from the phone's Bluetooth settings"]
    return []
