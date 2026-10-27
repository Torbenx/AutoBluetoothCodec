"""Checks that the baseline is stable before tests build on it, step 7 of TestConcept.md.

Each test breaks one part of the baseline, and the next test's check finds
it restored. The last test fails if any restore missed something.
"""

from mobly import asserts

from lib import app
from lib import companion
from lib import device_state
from lib import headset
from lib.device_test import BaselineTest

QUDELIX_SBC = {"codecType": 0, "sampleRate": 0x1, "bitsPerSample": 0x1}


class BaselineSmokeTest(BaselineTest):
    def test_01_nothing(self):
        pass

    def test_02_log_written(self):
        self.log.label("smoke test")

    def test_03_permission_revoked(self):
        self.require(self.sdk >= 31, "no runtime permission before API 31")
        app.revoke_permission(self.adb)

    def test_04_unlinked(self):
        companion.unlink(self.adb, app.PACKAGE, self.headset.address)

    def test_05_force_stopped(self):
        app.force_stop(self.adb)
        asserts.assert_true(app.is_stopped(self.adb), "force-stop didn't stop the package")

    def test_06_app_in_front(self):
        app.launch(self.adb)

    def test_07_codec_changed(self):
        self.ad.load_snippet("app", app.SNIPPET_PACKAGE)
        try:
            self.ad.app.setCodecDirectly(self.headset.address, QUDELIX_SBC)
        finally:
            self.ad.unload_snippet("app")

    def test_08_a2dp_down(self):
        self.require_capability(headset.A2DP_SWITCH)
        self.headset.disconnect_a2dp()

    def test_09_bluetooth_off(self):
        device_state.set_bluetooth(self.adb, False)

    def test_10_nothing(self):
        pass

    def test_99_no_leaks(self):
        asserts.assert_equal([], self.leaks, "restores that missed something")
