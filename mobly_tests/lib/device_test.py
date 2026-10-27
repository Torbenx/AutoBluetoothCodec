"""The base class of the device tests: one phone, one headset, artifacts on failure.

run_suite.py writes a Mobly config per phone with exactly one AndroidDevice
and one Headset, see testbed.yaml.
"""

import pathlib

from mobly import base_test
from mobly import signals
from mobly.controllers import android_device

from lib import app
from lib import app_log
from lib import bt_oracle
from lib import device_state
from lib import headset as headset_controller


class DeviceTest(base_test.BaseTestClass):
    def setup_class(self):
        self.ad = android_device.get_device(self.register_controller(android_device))
        self.headset = self.register_controller(headset_controller)[0]
        self.adb = self.ad.adb
        self.log = app_log.AppLog(self.adb)
        self.sdk = app.sdk(self.adb)
        # Declared per phone in testbed.yaml, see TestConcept.md section 2
        self.secure_lock_screen = bool(getattr(self.ad, "secure_lock_screen", False))
        self.ad.log.info("API %d, headset %s (%s)", self.sdk, self.headset.name, self.headset.backend)

    def setup_test(self):
        self._logcat_since = device_state.shell(self.adb, "date +'%m-%d %H:%M:%S.000'")

    def require(self, condition, reason):
        """Skips the test unless the phone or headset can do what it needs."""
        if not condition:
            raise signals.TestSkip(reason)

    def require_capability(self, capability):
        self.require(capability in self.headset.capabilities,
                     f"the {self.headset.backend} headset has no {capability}")

    def on_fail(self, record):
        self.collect_artifacts(pathlib.Path(self.current_test_info.output_path))

    def collect_artifacts(self, directory):
        """Saves what helps to understand a failure: log, Logcat, dumps, screen, headset."""
        directory.mkdir(parents=True, exist_ok=True)
        steps = {
            "event_log.jsonl": lambda: device_state.shell(
                self.adb, f"run-as {app.PACKAGE} cat {app_log.LOG_FILE} 2>/dev/null || true", timeout=30),
            "logcat.txt": lambda: device_state.shell(
                self.adb, f"logcat -d -v threadtime -T '{self._logcat_since}'", timeout=60),
            "bluetooth_manager.txt": lambda: device_state.shell(self.adb, bt_oracle.DUMP_COMMAND, timeout=30),
            "companiondevice.txt": lambda: device_state.shell(self.adb, "dumpsys companiondevice", timeout=30),
            "ui.xml": lambda: device_state.shell(
                self.adb, "uiautomator dump /data/local/tmp/ui.xml >/dev/null && cat /data/local/tmp/ui.xml", timeout=30),
            "headset.txt": lambda: f"{self.headset.info()}: {self.headset.status()}",
        }
        for name, step in steps.items():
            try:
                (directory / name).write_text(step(), encoding="utf-8")
            except Exception as e:  # An artifact that can't be collected mustn't hide the failure
                (directory / name).write_text(f"couldn't collect: {e!r}", encoding="utf-8")
        try:
            self.ad.take_screenshot(str(directory))
        except Exception as e:
            (directory / "screenshot.txt").write_text(f"couldn't collect: {e!r}", encoding="utf-8")
