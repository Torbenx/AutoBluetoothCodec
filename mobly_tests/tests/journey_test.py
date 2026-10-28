"""L5, the journeys: through the UI with snippet-uiautomator, which runs in its own process.

They check that the parts work together, including the system's dialog that
links a device, not every detail on the way; the UI tests cover those.
"""

import re
import time

from mobly import asserts

from lib import app
from lib import app_log
from lib import bt_oracle
from lib import companion
from lib import device_state
from lib import screens
from lib.bt_oracle import Codec
from lib.bt_oracle import same_format
from lib.device_test import BaselineTest

SNIPPET = "snippet"
SBC_44_16 = Codec(bt_oracle.SBC, 0x1, 0x1)
SBC_TEXTS = ("SBC", "44100 Hz", "16 bits/sample")
APPLY_TIMEOUT = 60.0
EXPORTS = "cache/exports"
MAC = re.compile(r"([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")


def chips(codec):
    """The chips the collapsed card shows for [codec]."""
    return (codec.name, f"{bt_oracle.SAMPLE_RATES[codec.sample_rate]} Hz",
            f"{bt_oracle.BITS_PER_SAMPLE[codec.bits_per_sample]} bits/sample")


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

    def save_preferred(self, screen):
        """Saves the card's codec as preferred with the status chip; the baseline's link means no dialog."""
        mark = self.log.last_seq()
        screen.tap_status()
        op = self.log.wait_for_operation(after=mark, trigger="USER_SAVE_PREFERRED", device=self.address, timeout=30)
        asserts.assert_equal("PREFERRED_SAVED", op.resolution, str(op))
        asserts.assert_equal("Preferred Codec", screen.status(), "the status chip after saving")

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

    def test_e1_first_start(self):
        """First start: the permission is granted in the system's dialog, and the card shows the codec
        dumpsys reports. Denying it twice shows the settings hint."""
        self.require(self.sdk >= 31, "the permission exists since API 31")

        def first_start():
            # A fresh start: the permission is revoked and its flags cleared
            app.clear_data(self.adb)
            if not companion.is_linked(self.adb, app.PACKAGE, self.address):
                companion.link(self.adb, app.PACKAGE, self.address)
            app.launch(self.adb)
            return screens.MainScreen(self.ad, self.headset)

        screen = first_start()
        screen.answer_permission(allow=True)
        screen.wait_for_card()
        dumped = bt_oracle.active_codec(self.adb, self.address)
        asserts.assert_true(screen.shows(*chips(dumped)), f"the card doesn't show {dumped}")
        screen = first_start()
        screen.answer_permission(allow=False)
        screen.tap_text("Grant permission")
        screen.answer_permission(allow=False)
        asserts.assert_true(screen.shows_text("Allow it in the app settings under Permissions."), "no settings hint")
        asserts.assert_true(screen.shows_text("Open settings"), "no Open settings button")

    def test_e4_an_external_change_shows_and_the_preferred_codec_is_restored(self):
        """A codec set by another app shows on the card within 2 s; Restore Preferred Codec brings the
        preferred codec back (manual 1.2, 2.2). The snippet sets it the way the developer options do."""
        own = bt_oracle.active_codec(self.adb, self.address)
        # Loading the snippet restarts the app's process, so it's loaded first
        self.ad.load_snippet(SNIPPET, app.SNIPPET_PACKAGE)
        try:
            screen = self.main_screen()
            self.save_preferred(screen)
            getattr(self.ad, SNIPPET).setCodecDirectly(self.address, SBC_44_16.to_app())
            changed = time.time()
            bt_oracle.wait_for(self.adb, lambda s: same_format(SBC_44_16, s.device(self.address).codec), 10, what="SBC")
            asserts.assert_true(screen.shows(*SBC_TEXTS), "the card doesn't show SBC")
            self.ad.log.info("E4: the card showed SBC %.1f s after the change", time.time() - changed)
            asserts.assert_true(time.time() - changed <= 2.0 + 1.5, "the card took longer than 2 s, plus the reads")
            asserts.assert_equal("Save As Preferred Codec", screen.status(), "the status chip after the change")
            mark = self.log.last_seq()
            screen.tap_menu_item("restore_preferred")
            op = self.log.wait_for_operation(after=mark, trigger="USER_RESTORE_PREFERRED", device=self.address, timeout=30)
            asserts.assert_equal("CODEC_VERIFIED", op.resolution, str(op))
            asserts.assert_true(same_format(own, bt_oracle.active_codec(self.adb, self.address)), "not restored")
            asserts.assert_true(screen.shows(*chips(own)), f"the card doesn't show {own}")
            asserts.assert_equal("Preferred Codec", screen.status(), "the status chip after restoring")
        finally:
            self.ad.unload_snippet(SNIPPET)

    def test_e5_delete_the_preferred_codec(self):
        """After deleting the preferred codec, a reconnect leaves the codec alone and the menu has no
        preferred codec items."""
        screen = self.main_screen()
        self.choose(screen, "SBC", SBC_44_16)
        self.save_preferred(screen)
        mark = self.log.last_seq()
        screen.tap_menu_item("delete_preferred")
        self.log.wait_for_event("PREFERRED_DELETED", after=mark, timeout=10)
        # Debug builds keep the menu for unlinking where the device is linked
        asserts.assert_equal([], [i for i in screen.menu_items() if i != "unlink_device"], "the menu after deleting")
        asserts.assert_equal("Save As Preferred Codec", screen.status(), "the status chip after deleting")
        app.go_home(self.adb)
        mark = self.log.last_seq()
        self.headset.reconnect(self.adb)
        bt_oracle.wait_connected(self.adb, self.address, timeout=60)
        time.sleep(10)
        dumped = bt_oracle.active_codec(self.adb, self.address)
        asserts.assert_true(self.baseline.own_codec.same_config(dumped), f"dumpsys shows {dumped}")
        set_codec = [e for e in self.log.events_after(mark) if e["type"] == "SET_CODEC_CONFIG"]
        asserts.assert_equal([], set_codec, "a codec was set after the reconnect")

    def test_e7_rotate_while_applying(self):
        """Rotating while a codec is applied: no crash, the codec is verified (manual 1.6)."""
        saved = {key: device_state.get_setting(self.adb, "system", key) for key in ("accelerometer_rotation", "user_rotation")}
        device_state.put_setting(self.adb, "system", "accelerometer_rotation", "0")
        device_state.put_setting(self.adb, "system", "user_rotation", "0")
        try:
            screen = self.main_screen()
            pid = app.pid(self.adb)
            mark = self.log.last_seq()
            screen.toggle()
            screen.tap_chip("SBC")
            device_state.put_setting(self.adb, "system", "user_rotation", "1")
            op = self.log.wait_for_operation(after=mark, trigger="USER_SET_CODEC", device=self.address, timeout=30)
            self.ad.log.info("E7: %s", op)
            asserts.assert_equal("CODEC_VERIFIED", op.resolution, str(op))
            asserts.assert_true(same_format(SBC_44_16, bt_oracle.active_codec(self.adb, self.address)), "SBC isn't active")
            screen.wait_for_card()
            asserts.assert_equal(pid, app.pid(self.adb), "the app's process changed")
        finally:
            for key, value in saved.items():
                device_state.put_setting(self.adb, "system", key, value)

    def test_e8_the_log_shows_a_reconnect_and_exports_it_anonymised(self):
        """The log screen shows a real reconnect with its attempts; the anonymised export contains no
        addresses or names (log 2.5)."""
        screen = self.main_screen()
        self.choose(screen, "SBC", SBC_44_16)
        self.save_preferred(screen)
        app.go_home(self.adb)
        mark = self.log.last_seq()
        self.headset.reconnect(self.adb)
        ops = self.wait_for_applied(mark, SBC_44_16)
        verified = next(op for op in ops if op.resolution == "CODEC_VERIFIED")
        log_screen = self.main_screen().open_log()
        seq = verified.trigger["seq"]
        asserts.assert_true(log_screen.entry_shows(seq, r"(?s).*Codec applied: SBC.*"), f"entry {seq} isn't shown")
        log_screen.toggle(seq)
        for event in verified.of_type("SET_CODEC_CONFIG"):
            asserts.assert_true(log_screen.entry_shows(seq, rf"Setting codec, attempt {event['attempt']}: SBC.*"),
                                f"attempt {event['attempt']} isn't shown")
        device_state.shell(self.adb, f"run-as {app.PACKAGE} rm -rf {EXPORTS}")
        log_screen.share()
        files = device_state.shell(self.adb, f"run-as {app.PACKAGE} ls {EXPORTS}").split()
        asserts.assert_equal(1, len(files), f"exports: {files}")
        export = device_state.shell(self.adb, f"run-as {app.PACKAGE} cat {EXPORTS}/{files[0]}", timeout=30)
        asserts.assert_true(any(e["seq"] == seq for e in app_log.parse(export)), "the reconnect isn't exported")
        asserts.assert_is_none(MAC.search(export), "the export contains an address")
        asserts.assert_true(self.headset.name not in export, "the export contains the headset's name")
        asserts.assert_true('"address":"device-' in export, "the export has no device ids")
