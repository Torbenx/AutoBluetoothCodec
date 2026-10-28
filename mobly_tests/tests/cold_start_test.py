"""L4, the background and cold start tests: the preferred codec is applied without the UI or the snippet.

Each test saves a preferred codec through the snippet, unloads it, which ends
the app's process, puts the process in the state the test needs, and only
then causes the stimulus. No label is sent inside that window, since a label
broadcast would start the process; the log is read with run-as instead.
"""

import time

from mobly import asserts

from lib import app
from lib import app_log
from lib import baseline
from lib import bt_oracle
from lib import device_state
from lib import headset
from lib.bt_oracle import Codec
from lib.bt_oracle import same_format
from lib.device_test import BaselineTest

SNIPPET = "snippet"
SBC_44_16 = Codec(bt_oracle.SBC, 0x1, 0x1)
AAC_44_16 = Codec(bt_oracle.AAC, 0x1, 0x1)
APPLY_TIMEOUT = 60.0

# Characterization tests (D8), their results recorded per API level on the testbed (6.7).
# C3: whether the preferred codec is applied when A2DP connects after the process was killed while
# the device was present. On API 36 too since the manifest receiver is enabled there (D17).
C3_APPLIED = {32: True, 36: True}
# C9: whether the preferred codec is applied when the device connects after a force stop.
C9_FORCE_STOPPED_APPLIED = {28: False, 32: False, 36: True}
# C13: whether the system keeps reporting the device as present for 5 minutes while it's on
# without A2DP. On API 32 it sometimes reports it gone and back within seconds, from BLE scans. On
# API 36 it does so every minute or two, and reports the headset coming on twice, a second apart.
C13_STAYS_PRESENT = {32: False, 36: False}


class ColdStartTest(BaselineTest):
    @property
    def address(self):
        return self.headset.address

    def preferred_codec(self):
        """A codec other than the phone's own choice (D11), so applying it shows."""
        own = self.baseline.own_codec
        return AAC_44_16 if own is not None and own.codec_type == bt_oracle.SBC else SBC_44_16

    def save_preferred(self, codec):
        """Saves [codec] as preferred through the snippet, which also observes the device since API 31."""
        self.ad.load_snippet(SNIPPET, app.SNIPPET_PACKAGE)
        try:
            getattr(self.ad, SNIPPET).setPreferredCodec(self.address, codec.to_app())
            # On API 32 the device appears right after observing starts, and the service applies the
            # codec; unloading the snippet ends the process, which mustn't interrupt that
            time.sleep(2)
            self.wait_until_resolved(timeout=15)
        finally:
            self.ad.unload_snippet(SNIPPET)
        app.kill(self.adb)

    def wait_for_applied(self, after, codec, timeout=APPLY_TIMEOUT):
        """Waits until A2DP uses [codec] and an operation after seq [after] verified it, returns the operations.

        dumpsys decides: an operation may also have verified the codec before the
        stimulus, when the app started observing again.
        """
        end = time.time() + timeout
        while True:
            dumped = bt_oracle.active_codec(self.adb, self.address)
            ops = app_log.operations(self.log.events_after(after))
            if same_format(dumped, codec) and any(op.resolution == "CODEC_VERIFIED" for op in ops):
                return ops
            if time.time() >= end:
                asserts.fail(f"{codec} wasn't applied within {timeout:.0f} s, dumpsys shows {dumped}: {self.describe(ops)}")
            time.sleep(1)

    def resolved_after(self, seq):
        return [op for op in app_log.operations(self.log.events_after(seq)) if op.resolution]

    def describe(self, ops):
        return "; ".join(f"{op.trigger['type']} ({op.trigger.get('message', '')}) -> {op.resolution}" for op in ops)

    def headset_off(self):
        """The device goes away: the headset off, or the phone's Bluetooth for headsets without power switch."""
        if headset.POWER_SWITCH in self.headset.capabilities:
            self.headset.power_off()
        else:
            device_state.set_bluetooth(self.adb, False)
        bt_oracle.wait_connected(self.adb, self.address, timeout=20, connected=False)

    def headset_on(self):
        if headset.POWER_SWITCH in self.headset.capabilities:
            self.headset.power_on()
        else:
            device_state.set_bluetooth(self.adb, True)

    def wait_until_applying(self, after, timeout=60.0):
        """Waits for an operation after seq [after] that has set a codec and is still open, returns it."""
        end = time.time() + timeout
        while True:
            ops = app_log.operations(self.log.events_after(after))
            for op in ops:
                if op.of_type("SET_CODEC_CONFIG") and not op.resolution:
                    return op
            if time.time() >= end:
                asserts.fail(f"no operation applied a codec within {timeout:.0f} s: {self.describe(ops)}")
            time.sleep(0.2)

    def wait_until_resolved(self, timeout=30.0):
        """Waits until no operation is open, returns the log."""
        end = time.time() + timeout
        while True:
            events = self.log.read()
            if not app_log.check_structure(events) or time.time() >= end:
                return events
            time.sleep(1)

    def summary(self, after):
        """The operations after seq [after], as "TRIGGER RESOLUTION"."""
        return [f"{op.trigger['type']} {op.resolution}" for op in app_log.operations(self.log.events_after(after))]

    def test_c1_killed_app_applies_on_reconnect(self):
        """App process killed, the headset reconnects: applied. The manifest receiver handles A2DP connecting
        on every API level (D17), since API 31 the service may apply it first."""
        codec = self.preferred_codec()
        self.save_preferred(codec)
        self.headset_off()
        # Since API 31 the system unbinds the service once the device is gone, 7 to 8 s on API 32
        time.sleep(15)
        app.kill(self.adb)
        time.sleep(3)
        asserts.assert_false(app.is_running(self.adb), "the system started the app's process again")
        mark = self.log.last_seq()
        self.headset_on()
        self.wait_for_applied(mark, codec)
        end = time.time() + 10
        while True:
            ops = self.resolved_after(mark)
            if any(op.trigger.get("message") == "manifest receiver" for op in ops):
                break
            if time.time() >= end:
                asserts.fail(f"the manifest receiver didn't handle A2DP connecting: {self.describe(ops)}")
            time.sleep(1)
        self.ad.log.info("C1: %s", self.describe(ops))

    def test_c2_app_in_front_applies_on_reconnect(self):
        """The same with the main screen open (manual 2.1)."""
        codec = self.preferred_codec()
        self.save_preferred(codec)
        app.launch(self.adb)
        mark = self.log.last_seq()
        self.headset.reconnect(self.adb)
        ops = self.wait_for_applied(mark, codec)
        self.ad.log.info("C2 in front: %s", self.describe(ops))

    def test_c2_app_in_background_applies_on_reconnect(self):
        """The same with the process alive in the background."""
        codec = self.preferred_codec()
        self.save_preferred(codec)
        app.launch(self.adb)
        app.go_home(self.adb)
        asserts.assert_true(app.is_running(self.adb), "the app's process isn't running")
        mark = self.log.last_seq()
        self.headset.reconnect(self.adb)
        ops = self.wait_for_applied(mark, codec)
        self.ad.log.info("C2 in background: %s", self.describe(ops))

    def test_c3_killed_while_present_then_a2dp_connects(self):
        """Characterization: the process is killed while the device is present with A2DP down, then A2DP connects."""
        self.require(self.sdk >= 31, "presence is only observed since API 31")
        self.require_capability(headset.A2DP_SWITCH)
        self.require_capability(headset.POWER_SWITCH)
        codec = self.preferred_codec()
        self.save_preferred(codec)
        self.headset_off()
        time.sleep(15)
        # Back on without A2DP: the device appears, and the service keeps it as present
        mark = self.log.last_seq()
        self.headset.power_on(a2dp=False)
        # Seen through BLE scans or the link, which took from seconds to over a minute
        appeared = self.log.wait_for_operation(after=mark, trigger="DEVICE_APPEARED", device=self.address, timeout=90)
        self.ad.log.info("C3: %s", appeared)
        app.kill(self.adb)
        time.sleep(5)
        restarted = app.is_running(self.adb)
        mark = self.log.last_seq()
        self.headset.connect_a2dp()
        bt_oracle.wait_connected(self.adb, self.address, timeout=30)
        time.sleep(10)
        ops = app_log.operations(self.log.events_after(mark))
        dumped = bt_oracle.active_codec(self.adb, self.address)
        applied = dumped is not None and dumped.codec_type == codec.codec_type
        self.ad.log.info("C3 on API %d: process restarted by the system %s, applied %s, codec %s: %s",
                         self.sdk, restarted, applied, dumped, self.describe(ops))
        expected = C3_APPLIED.get(self.sdk)
        if expected is None:
            asserts.explicit_pass(f"recorded: applied {applied}, process restarted {restarted}; "
                                  f"add it to C3_APPLIED for API {self.sdk}")
        asserts.assert_equal(expected, applied, f"applied after a kill while present: {self.describe(ops)}")

    def test_c4_killed_during_an_operation(self):
        """The process is killed while it applies the preferred codec: the next start resolves the
        operation OPERATION_INTERRUPTED, and nothing stays open."""
        self.allowed_interrupted = 1
        # A codec the headset doesn't offer keeps the operation open for 5 attempts
        self.save_preferred(bt_oracle.codec_outside(bt_oracle.device(self.adb, self.address).capabilities))
        mark = self.log.last_seq()
        self.headset.reconnect(self.adb)
        op = self.wait_until_applying(mark)
        app.kill(self.adb)
        # The label starts the process, which repairs the log when it loads it
        self.log.label("C4 next start")
        events = self.wait_until_resolved()
        killed = next(o for o in app_log.operations(events) if o.uid == op.uid)
        self.ad.log.info("C4: %s", killed)
        asserts.assert_equal("OPERATION_INTERRUPTED", killed.resolution, str(killed))
        asserts.assert_equal([], app_log.check_structure(events), "the log after the next start")

    def test_c5_permission_revoked(self):
        """Without the permission, the device connects: NO_PERMISSION is logged, nothing applied, no crash."""
        self.require(self.sdk >= 31, "the permission exists since API 31")
        codec = self.preferred_codec()
        self.save_preferred(codec)
        app.revoke_permission(self.adb)
        mark = self.log.last_seq()
        self.headset.reconnect(self.adb)
        # The service gets the device when it appears, seen through BLE scans or the link
        op = self.log.wait_for_operation(after=mark, device=self.address, timeout=90)
        time.sleep(10)
        ops = app_log.operations(self.log.events_after(mark))
        self.ad.log.info("C5: %s", self.describe(ops))
        asserts.assert_equal("NO_PERMISSION", op.resolution, self.describe(ops))
        asserts.assert_false(any(o.resolution == "CODEC_VERIFIED" for o in ops), self.describe(ops))
        asserts.assert_false(same_format(codec, bt_oracle.active_codec(self.adb, self.address)), "the codec was applied")

    def test_c6_no_preferred_codec(self):
        """Without a preferred codec the codec isn't touched and nothing is logged. The manifest receiver
        starts the process and returns."""
        app.kill(self.adb)
        mark = self.log.last_seq()
        self.headset.reconnect(self.adb)
        bt_oracle.wait_connected(self.adb, self.address, timeout=baseline.CONNECT_TIMEOUT)
        time.sleep(10)
        events = self.log.events_after(mark)
        running = app.is_running(self.adb)
        self.ad.log.info("C6: process running %s, events %s", running, [e["type"] for e in events])
        asserts.assert_equal([], events, "the app logged something")
        dumped = bt_oracle.active_codec(self.adb, self.address)
        asserts.assert_true(self.baseline.own_codec.same_config(dumped), f"dumpsys shows {dumped}")

    def test_c7_after_a_reboot(self):
        """After a reboot, without opening the app: applied."""
        self.require(not self.secure_lock_screen, "after a reboot the app can't run until the phone is unlocked")
        codec = self.preferred_codec()
        self.save_preferred(codec)
        mark = self.log.last_seq()
        self.ad.reboot()
        # The phones have no secure lock screen, the keyguard only needs dismissing
        device_state.wake(self.adb)
        device_state.shell(self.adb, "wm dismiss-keyguard")
        baseline.connect(self.adb, self.headset)
        ops = self.wait_for_applied(mark, codec, timeout=120)
        self.ad.log.info("C7: %s", self.describe(ops))

    def test_c8_after_an_update(self):
        """After an update, without opening the app: applied."""
        codec = self.preferred_codec()
        self.save_preferred(codec)
        app.install(self.adb)
        mark = self.log.last_seq()
        self.headset.reconnect(self.adb)
        ops = self.wait_for_applied(mark, codec)
        self.ad.log.info("C8: %s", self.describe(ops))

    def test_c9_fresh_install(self):
        """Characterization: installed and never launched, the device connects. The app has nothing to
        apply and isn't started. Whether the install is in the stopped state is recorded."""
        device_state.shell(self.adb, f"pm uninstall {app.PACKAGE}", timeout=60)
        app.install(self.adb)
        stopped = app.is_stopped(self.adb)
        self.headset.reconnect(self.adb)
        bt_oracle.wait_connected(self.adb, self.address, timeout=baseline.CONNECT_TIMEOUT)
        time.sleep(10)
        running, logged = app.is_running(self.adb), self.log.exists()
        self.ad.log.info("C9 fresh install on API %d: stopped %s, process running %s, log written %s",
                         self.sdk, stopped, running, logged)
        asserts.assert_false(running, "the app's process was started")
        asserts.assert_false(logged, "the app logged something")

    def test_c9_force_stopped(self):
        """Characterization: the app is force-stopped, then the device connects: records whether the
        preferred codec is applied."""
        codec = self.preferred_codec()
        self.save_preferred(codec)
        app.force_stop(self.adb)
        asserts.assert_true(app.is_stopped(self.adb), "the package isn't stopped")
        mark = self.log.last_seq()
        self.headset.reconnect(self.adb)
        bt_oracle.wait_connected(self.adb, self.address, timeout=baseline.CONNECT_TIMEOUT)
        end = time.time() + 30
        while not (applied := same_format(codec, bt_oracle.active_codec(self.adb, self.address))) and time.time() < end:
            time.sleep(1)
        ops = app_log.operations(self.log.events_after(mark))
        self.ad.log.info("C9 force-stopped on API %d: applied %s, still stopped %s: %s",
                         self.sdk, applied, app.is_stopped(self.adb), self.describe(ops))
        expected = C9_FORCE_STOPPED_APPLIED.get(self.sdk)
        if expected is None:
            asserts.explicit_pass(f"recorded: applied {applied}; add it to C9_FORCE_STOPPED_APPLIED for API {self.sdk}")
        asserts.assert_equal(expected, applied, f"applied after a force stop: {self.describe(ops)}")

    def test_c13_presence_without_a2dp(self):
        """Characterization: with a preferred codec and A2DP down, what the app does each time the
        device appears, and while it stays present for 5 minutes."""
        self.require(self.sdk >= 31, "presence is only observed since API 31")
        self.require_capability(headset.A2DP_SWITCH)
        self.require_capability(headset.POWER_SWITCH)
        self.save_preferred(self.preferred_codec())
        self.headset.disconnect_a2dp()
        bt_oracle.wait_connected(self.adb, self.address, timeout=30, connected=False)
        start = self.log.last_seq()
        recorded = {}
        # The headset off and back on without A2DP
        self.headset.power_off()
        time.sleep(15)
        mark = self.log.last_seq()
        self.headset.power_on(a2dp=False)
        self.log.wait_for_operation(after=mark, trigger="DEVICE_APPEARED", device=self.address, timeout=90)
        time.sleep(10)
        recorded["headset on"] = self.summary(mark)
        # The phone's Bluetooth off and back on
        mark = self.log.last_seq()
        device_state.set_bluetooth(self.adb, False)
        time.sleep(15)
        device_state.set_bluetooth(self.adb, True)
        try:
            self.log.wait_for_operation(after=mark, trigger="DEVICE_APPEARED", device=self.address, timeout=90)
        except TimeoutError:
            # The S24 sees the device over BLE with its Bluetooth off, so it may never be gone
            pass
        time.sleep(10)
        recorded["Bluetooth on"] = self.summary(mark)
        # Present for 5 minutes
        mark = self.log.last_seq()
        time.sleep(300)
        recorded["present"] = self.summary(mark)
        self.ad.log.info("C13 on API %d: %s", self.sdk, recorded)
        asserts.assert_is_none(bt_oracle.active_codec(self.adb, self.address), "A2DP connected")
        # The S24 sees the device over BLE before its Bluetooth is back on
        allowed = ("DEVICE_APPEARED NOT_CONNECTED", "DEVICE_APPEARED PROFILE_UNAVAILABLE")
        others = [op for ops in recorded.values() for op in ops if op not in allowed]
        asserts.assert_equal([], others, f"operations other than an appearance without A2DP: {recorded}")
        # One operation per appearance the system reports
        appeared = [e for e in self.log.events_after(start) if e["type"] == "DEVICE_APPEARED"]
        asserts.assert_equal(len(appeared), sum(len(ops) for ops in recorded.values()), f"operations: {recorded}")
        stays = C13_STAYS_PRESENT.get(self.sdk)
        if stays is None:
            asserts.explicit_pass(f"recorded: {recorded}; add whether the device stayed present to "
                                  f"C13_STAYS_PRESENT for API {self.sdk}")
        if stays:
            asserts.assert_equal([], recorded["present"], "operations while the device stayed present")
