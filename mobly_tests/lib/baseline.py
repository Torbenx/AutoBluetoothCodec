"""The state every device test starts from and leaves behind, TestConcept.md 6.2.

A user who has set the app up and listens with the headset, but hasn't saved
a preferred codec: log empty, settings at their defaults, package not
stopped, permission granted without flags, headset linked and not observed,
phone Bluetooth on, A2DP connected, headset normal, the codec the phone picks
on its own (D11), screen on and unlocked with the launcher in front.

check() reads every part, restore() puts back what differs and checks again.
Whether the app process runs isn't part of the baseline; restoring ends it,
so no state survives in memory.
"""

import re

from lib import app
from lib import app_log
from lib import bt_oracle
from lib import companion
from lib import device_state

# Flags a user's choice leaves on the permission, the reset clears them
USER_FLAGS = {"USER_SET", "USER_FIXED"}
CONNECT_TIMEOUT = 40.0


def foreground_activity(adb):
    out = device_state.shell(adb, "dumpsys activity activities | grep -m1 -E 'mResumedActivity|topResumedActivity' || true")
    match = re.search(r"\{\S+ u\d+ ([^ }]+)", out)
    return match.group(1) if match else None


def preferred_codecs(adb):
    """The addresses the app has preferred codecs for, from its preferences file."""
    out = device_state.shell(adb, f"run-as {app.PACKAGE} cat shared_prefs/preferred_codecs.xml 2>/dev/null || true")
    return re.findall(r'<string name="([^"]+)"', out)


def connect(adb, headset, timeout=CONNECT_TIMEOUT):
    """The connect fixture, 6.6: done once A2DP connects, else one reconnect of the headset.

    It waits a little first: after the phone's Bluetooth or the headset's A2DP
    comes back, A2DP connects on its own within seconds.
    """
    if not device_state.is_bluetooth_on(adb):
        device_state.set_bluetooth(adb, True)
    try:
        bt_oracle.wait_connected(adb, headset.address, timeout=15)
        return
    except TimeoutError:
        pass
    headset.reconnect(adb)
    bt_oracle.wait_connected(adb, headset.address, timeout=timeout)


class Baseline:
    def __init__(self, adb, headset, sdk):
        self.adb = adb
        self.headset = headset
        self.sdk = sdk
        self.log = app_log.AppLog(adb)
        # The phone's own choice, recorded by record_own_codec()
        self.own_codec = None

    def check(self):
        """The parts that differ from the baseline, as readable problems."""
        adb, problems = self.adb, []
        events = self.log.read()
        if events:
            problems.append(f"log has {len(events)} events, the last {events[-1]['type']}")
        preferred = preferred_codecs(adb)
        if preferred:
            problems.append(f"preferred codecs for {', '.join(preferred)}")
        if app.is_stopped(adb):
            problems.append("package is stopped")
        permission = app.permission_state(adb)
        if permission is not None:
            granted, flags = permission
            if not granted:
                problems.append("permission not granted")
            if flags & USER_FLAGS:
                problems.append(f"permission flags {sorted(flags & USER_FLAGS)}")
        if not companion.is_linked(adb, app.PACKAGE, self.headset.address):
            problems.append("headset not linked")
        elif companion.is_observing(adb, app.PACKAGE, self.headset.address):
            problems.append("headset observed")
        if not device_state.is_bluetooth_on(adb):
            problems.append(f"phone Bluetooth {device_state.bluetooth_state(adb)}")
        else:
            state = bt_oracle.read(adb)
            device = state.device(self.headset.address)
            if device is None or not device.connected or not state.is_active(self.headset.address):
                problems.append("A2DP not connected")
            elif self.own_codec is not None and not self.own_codec.same_config(device.codec):
                problems.append(f"codec {device.codec}, the phone's own is {self.own_codec}")
        if not self.headset.is_normal():
            problems.append(f"headset {self.headset.status()}")
        if not device_state.is_awake(adb) or not device_state.is_unlocked(adb):
            problems.append("screen off or locked")
        if (foreground_activity(adb) or "").startswith(app.PACKAGE + "/"):
            problems.append("the app is in front")
        return problems

    def restore(self):
        """Restores the baseline and returns what still differs, an empty list if nothing."""
        adb = self.adb
        # Relinking first ends observing; any event it causes is deleted with the app data next.
        # API 31 and 32 don't show observing, so they're always relinked.
        linked = companion.is_linked(adb, app.PACKAGE, self.headset.address)
        if not linked or self.sdk in (31, 32) or companion.is_observing(adb, app.PACKAGE, self.headset.address):
            if linked:
                companion.unlink(adb, app.PACKAGE, self.headset.address)
            companion.link(adb, app.PACKAGE, self.headset.address)
        # Always, so nothing survives in the process's memory either
        app.clear_data(adb)
        # On API 36 clearing the data also removes the link
        if not companion.is_linked(adb, app.PACKAGE, self.headset.address):
            companion.link(adb, app.PACKAGE, self.headset.address)
        app.grant_permission(adb)
        if app.is_stopped(adb):
            app.unstop(adb)
        if not self.headset.is_normal():
            self.headset.restore_normal()
        connect(adb, self.headset)
        if self.own_codec is not None and not self.own_codec.same_config(bt_oracle.active_codec(adb, self.headset.address)):
            # Only a full reconnect brings back the phone's own choice
            self.headset.reconnect(adb)
            bt_oracle.wait_connected(adb, self.headset.address, timeout=CONNECT_TIMEOUT)
        if (foreground_activity(adb) or "").startswith(app.PACKAGE + "/"):
            app.go_home(adb)
        return self.check()

    def record_own_codec(self):
        """Records the codec the phone picks on a full reconnect while the app has no preferred codec."""
        self.headset.reconnect(self.adb)
        bt_oracle.wait_connected(self.adb, self.headset.address, timeout=CONNECT_TIMEOUT)
        self.own_codec = bt_oracle.active_codec(self.adb, self.headset.address)
        return self.own_codec
