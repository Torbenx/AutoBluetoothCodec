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
from lib import bt_oracle
from lib import device_state
from lib import headset
from lib.bt_oracle import Codec
from lib.device_test import BaselineTest

SNIPPET = "snippet"
SBC_44_16 = Codec(bt_oracle.SBC, 0x1, 0x1)
AAC_44_16 = Codec(bt_oracle.AAC, 0x1, 0x1)
APPLY_TIMEOUT = 60.0


def same_format(a, b):
    return a is not None and (a.codec_type, a.sample_rate, a.bits_per_sample) == (b.codec_type, b.sample_rate, b.bits_per_sample)

# C3 is a characterization test (D8): whether the preferred codec is applied when A2DP connects
# after the process was killed while the device was present. Recorded per API level on the testbed.
C3_APPLIED = {32: True, 36: False}


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

    def test_c1_killed_app_applies_on_reconnect(self):
        """App process killed, the headset reconnects: applied, by the receiver up to API 32, the service since."""
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
        ops = self.wait_for_applied(mark, codec)
        self.ad.log.info("C1: %s", self.describe(ops))
        # Up to API 32 the manifest receiver applies it when A2DP connects, since API 33 the
        # companion service; either may when the device appears with A2DP connected already
        if self.sdk >= 33:
            asserts.assert_false(any(op.trigger.get("message") == "legacy receiver" for op in ops),
                                 f"the receiver is enabled: {self.describe(ops)}")

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
