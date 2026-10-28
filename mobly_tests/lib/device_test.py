"""The base class of the device tests: one phone, one headset, artifacts on failure.

run_suite.py writes a Mobly config per phone with exactly one AndroidDevice
and one Headset, see testbed.yaml.
"""

import pathlib
import shutil

from mobly import asserts
from mobly import base_test
from mobly import signals
from mobly.controllers import android_device

from lib import app
from lib import app_log
from lib import baseline
from lib import bt_oracle
from lib import device_state
from lib import headset as headset_controller


class DeviceTest(base_test.BaseTestClass):
    def setup_class(self):
        self.ad = self.register_controller(android_device)[0]
        self.headset = self.register_controller(headset_controller)[0]
        self.adb = self.ad.adb
        self.log = app_log.AppLog(self.adb)
        self.sdk = app.sdk(self.adb)
        # Declared per phone in testbed.yaml, see TestConcept.md section 2
        self.secure_lock_screen = bool(getattr(self.ad, "secure_lock_screen", False))
        self.ad.log.info("API %d, headset %s (%s)", self.sdk, self.headset.name, self.headset.backend)

    def setup_test(self):
        self._logcat_since = device_state.shell(self.adb, "date +'%m-%d %H:%M:%S.000'")
        self._artifacts = None

    def require(self, condition, reason):
        """Skips the test unless the phone or headset can do what it needs."""
        if not condition:
            raise signals.TestSkip(reason)

    def require_capability(self, capability):
        self.require(capability in self.headset.capabilities,
                     f"the {self.headset.backend} headset has no {capability}")

    def on_fail(self, record):
        if self._artifacts is None:
            self.collect_artifacts()

    def on_pass(self, record):
        if self._artifacts is not None:
            shutil.rmtree(self._artifacts, ignore_errors=True)

    def collect_artifacts(self):
        """Saves what helps to understand a failure: log, Logcat, dumps, screen, headset."""
        directory = pathlib.Path(self.current_test_info.output_path) / "artifacts"
        self._artifacts = directory
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


class BaselineTest(DeviceTest):
    """A device test class whose tests start from the baseline and restore it, TestConcept.md 6.2.

    A difference found before a test means the previous test's restore missed
    something. It's repaired and reported as a leak of that test; if the
    repair fails, the rest of the class is aborted instead of failing every
    test for the same reason (D12).
    """

    def setup_class(self):
        self.leaks = []
        self._previous_test = None
        super().setup_class()
        installed, expected = app.installed_version(self.adb), app.expected_version()
        if installed != expected:
            asserts.abort_class(f"installed build {installed} isn't the working tree's {expected}")
        self.baseline = baseline.Baseline(self.adb, self.headset, self.sdk)
        problems = self.baseline.restore()
        if problems:
            asserts.abort_class(f"the baseline can't be established: {problems}")
        codec = self.baseline.record_own_codec()
        self.ad.log.info("the phone's own codec is %s", codec)
        # The reconnect mustn't make the app log anything, there is no preferred codec
        problems = self.baseline.check()
        if problems:
            asserts.abort_class(f"the baseline isn't stable after recording the codec: {problems}")

    def setup_test(self):
        super().setup_test()
        problems = self.baseline.check()
        if problems:
            self.leaks.append((self._previous_test, problems))
            self.ad.log.warning("the baseline was left broken by %s: %s", self._previous_test, problems)
            remaining = self.baseline.restore()
            if remaining:
                asserts.abort_class(f"the baseline can't be restored: {remaining}")

    def teardown_test(self):
        self._previous_test = self.current_test_info.name
        # Mobly only calls on_fail after the teardown, when the restore has reset the app
        self.collect_artifacts()
        try:
            remaining = self.baseline.restore()
            if remaining:
                self.ad.log.warning("restoring after %s left %s", self._previous_test, remaining)
        except Exception:
            # The next test's check reports it as a leak
            self.ad.log.exception("restoring the baseline after %s failed", self._previous_test)

    def teardown_class(self):
        if self.leaks:
            path = pathlib.Path(self.log_path) / f"{self.TAG}-leaks.txt"
            path.write_text("".join(f"{test}: {problems}\n" for test, problems in self.leaks), encoding="utf-8")
            self.ad.log.warning("%d baseline leaks, see %s", len(self.leaks), path)
