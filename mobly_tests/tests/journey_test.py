"""L5, the journeys: through the UI with snippet-uiautomator, which runs in its own process.

They check that the parts work together, including the system's dialog that
links a device, not every detail on the way; the UI tests cover those.
"""

import time

from mobly import asserts

from lib import app
from lib import app_log
from lib import bt_oracle
from lib import companion
from lib import screens
from lib.bt_oracle import Codec
from lib.device_test import BaselineTest

SBC_44_16 = Codec(bt_oracle.SBC, 0x1, 0x1)
APPLY_TIMEOUT = 60.0


def same_format(a, b):
    return a is not None and (a.codec_type, a.sample_rate, a.bits_per_sample) == (b.codec_type, b.sample_rate, b.bits_per_sample)


class JourneyTest(BaselineTest):
    def setup_class(self):
        super().setup_class()
        screens.register(self.ad)

    @property
    def address(self):
        return self.headset.address

    def main_screen(self):
        return screens.MainScreen(self.ad, self.headset).open()

    def choose(self, screen, chip, codec):
        """Chooses a codec with the chips of the expanded card, and checks it's applied."""
        mark = self.log.last_seq()
        screen.toggle()
        screen.tap_chip(chip)
        op = self.log.wait_for_operation(after=mark, trigger="USER_SET_CODEC", device=self.address, timeout=30)
        asserts.assert_equal("CODEC_VERIFIED", op.resolution, str(op))
        dumped = bt_oracle.active_codec(self.adb, self.address)
        asserts.assert_true(same_format(dumped, codec), f"dumpsys shows {dumped}")
        screen.toggle()

    def wait_for_applied(self, after, codec, timeout=APPLY_TIMEOUT):
        """Waits until A2DP is connected with [codec] and an operation after seq [after] verified it.

        A disconnected device keeps its last codec in dumpsys, so only a connected one counts.
        """
        end = time.time() + timeout
        while True:
            dumped = bt_oracle.active_codec(self.adb, self.address)
            ops = app_log.operations(self.log.events_after(after))
            if same_format(dumped, codec) and any(op.resolution == "CODEC_VERIFIED" for op in ops):
                return ops
            if time.time() >= end:
                asserts.fail(f"{codec} wasn't applied within {timeout:.0f} s, dumpsys shows {dumped}: "
                             + "; ".join(map(str, ops)))
            time.sleep(1)

    def test_e2_choose_a_codec_with_the_chips(self):
        """Choosing SBC on the card changes the codec, and the card and the log show it (manual 1.1)."""
        screen = self.main_screen()
        self.choose(screen, "SBC", SBC_44_16)
        asserts.assert_true(screen.shows("SBC", "44100 Hz", "16 bits/sample"), "the card doesn't show SBC")

    def test_e3_save_as_preferred_and_reconnect(self):
        """Saving the codec as preferred, linking where needed; after a reconnect it's applied and shown."""
        # Since API 31 saving links the device. On API 36 an unlinked device's codec is unreadable,
        # so there is nothing to save; E6 covers that.
        linking = 31 <= self.sdk < 36
        if linking:
            companion.unlink(self.adb, app.PACKAGE, self.address)
        screen = self.main_screen()
        self.choose(screen, "SBC", SBC_44_16)
        mark = self.log.last_seq()
        asserts.assert_equal("Save As Preferred Codec", screen.status(), "the status chip")
        screen.tap_status()
        if linking:
            screen.accept_link_dialog()
        op = self.log.wait_for_operation(after=mark, trigger="USER_SAVE_PREFERRED", device=self.address, timeout=30)
        asserts.assert_equal("PREFERRED_SAVED", op.resolution, str(op))
        asserts.assert_equal("Preferred Codec", screen.status(), "the status chip after saving")
        if linking:
            asserts.assert_true(companion.is_linked(self.adb, app.PACKAGE, self.address), "the device isn't linked")
        app.go_home(self.adb)
        mark = self.log.last_seq()
        self.headset.reconnect(self.adb)
        self.wait_for_applied(mark, SBC_44_16)
        screen = self.main_screen()
        asserts.assert_true(screen.shows("SBC", "44100 Hz", "16 bits/sample"), "the card doesn't show SBC")
        asserts.assert_equal("Preferred Codec", screen.status(), "the status chip after the reconnect")

    def test_e6_an_unlinked_device_asks_to_be_linked(self):
        """Since API 34 an unlinked device asks for the link; on 36 its codec only shows once it's linked."""
        self.require(self.sdk >= 34, "a link is only needed since API 34")
        companion.unlink(self.adb, app.PACKAGE, self.address)
        screen = self.main_screen()
        if self.sdk >= 36:
            asserts.assert_true(self.ad.ui(text=f"Link {self.headset.name} to see and change its codec.").wait.exists(screens.TIMEOUT),
                                "the card doesn't ask for the link")
            asserts.assert_false(screen.chip("96000 Hz").exists, "the codec shows without a link")
        mark = self.log.last_seq()
        screen.tap_link()
        screen.accept_link_dialog()
        op = self.log.wait_for_operation(after=mark, trigger="USER_LINK_DEVICE", device=self.address, timeout=30)
        asserts.assert_equal("ASSOCIATION_CREATED", op.resolution, str(op))
        asserts.assert_true(screen.shows("LDAC", "96000 Hz"), "the codec doesn't show after linking")
        self.choose(screen, "SBC", SBC_44_16)
