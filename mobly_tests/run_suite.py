"""Run the device test suites, one phone after the other, with one headset.

Keep testbed_session.py running in another window: it keeps every phone's
Bluetooth off and the screens on. For each phone on adb that testbed.yaml
lists, this installs the build under test, turns the phone's Bluetooth on,
runs the selected suites and turns Bluetooth off again.

Suites, in the order they run:
  ui          the shared UI tests (app/src/sharedTest) with am instrument
  journeys    L5, tests/journey_test.py
  bt_logic    L3, tests/bt_logic_test.py
  cold_start  L4, tests/cold_start_test.py
The journeys need the screen on, so they run before the tests that turn it
off or reboot.

Usage, from this folder:
  .venv/Scripts/python run_suite.py --build
  .venv/Scripts/python run_suite.py --suite ui --phone <serial>
Before a phone's tests, sanity checks make sure it's plugged in, awake,
unlocked, alone with Bluetooth on, and that the headset connects when
Bluetooth turns on; a phone that fails them is skipped. `--suite smoke`
checks that the baseline is stable.

Results go to results/<time>/, one Mobly log directory per phone and suite.
"""

import argparse
import datetime
import importlib
import inspect
import logging
import os
import pathlib
import subprocess
import sys

import yaml
from mobly import config_parser
from mobly import test_runner
from mobly.controllers import android_device
from mobly.controllers.android_device_lib.adb import AdbProxy

from lib import app
from lib import device_state
from lib import headset as headset_controller
from lib import sanity
from lib.device_test import DeviceTest

HERE = pathlib.Path(__file__).resolve().parent
SUITES = ["ui", "journeys", "bt_logic", "cold_start"]
# Checks that the baseline is stable, run on its own with --suite smoke
SMOKE = "smoke"


def load_testbed(path):
    testbed = yaml.safe_load(path.read_text(encoding="utf-8"))
    return {p["serial"]: p for p in testbed["phones"]}, testbed["headsets"]


def build():
    """Builds the app, its instrumented tests and the snippet with Gradle."""
    gradlew = app.ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    subprocess.run([str(gradlew), ":app:assembleDebug", ":app:assembleDebugAndroidTest", ":snippet:assembleDebug"],
                   cwd=app.ROOT, check=True)


def install(adb):
    for apk in (app.APK, app.TEST_APK, app.SNIPPET_APK):
        if not apk.exists():
            sys.exit(f"{apk} is missing, build first (--build)")
        app.install(adb, apk)
    expected = app.expected_version()
    installed = app.installed_version(adb)
    if installed != expected:
        sys.exit(f"installed build {installed} isn't the working tree's {expected}, build first (--build)")


def mobly_config(directory, serial, phone, headset_name, headset):
    """Writes a Mobly config with the phone under test and the headset, returns its path."""
    device = {"serial": serial, **{k: v for k, v in phone.items() if k != "serial"}}
    config = {
        "TestBeds": [{
            "Name": f"{headset_name}-{serial}",
            "Controllers": {
                "AndroidDevice": [device],
                "Headset": [headset],
            },
        }],
        "MoblyParams": {"LogPath": str(directory)},
    }
    path = directory / f"config-{serial}.yaml"
    path.write_text(yaml.safe_dump(config), encoding="utf-8")
    return path


def test_classes(suite):
    module = importlib.import_module(f"tests.{suite}_test")
    # Only the classes with tests, not the base classes a module defines for them
    return [cls for _, cls in inspect.getmembers(module, inspect.isclass)
            if issubclass(cls, DeviceTest) and cls.__module__ == module.__name__
            and any(name.startswith("test_") for name in dir(cls))]


def run_mobly(config_path, suite, tests=None):
    config = config_parser.load_test_config_file(str(config_path))[0]
    runner = test_runner.TestRunner(log_dir=config.log_path, testbed_name=config.testbed_name)
    with runner.mobly_logger():
        for cls in test_classes(suite):
            runner.add_test_class(config, cls, tests=tests)
        runner.run()
    # Mobly leaves the root logger at DEBUG, which would print every adb command from here on
    logging.getLogger().setLevel(logging.WARNING)
    return runner.results.is_all_pass


def run_ui(adb, directory):
    output = app.run_instrumentation(adb)
    (directory / "instrumentation.txt").write_text(output, encoding="utf-8")
    ran, failures, failed = app.parse_instrumentation(output)
    print(f"  ui: {ran} tests, {failures} failed" + "".join(f"\n    {name}" for name in failed))
    return failures == 0


def failed_sanity(problems):
    for problem in problems:
        print(f"  sanity check failed: {problem}")
    return {"sanity": False}


def run_phone(serial, phone, headset_name, headset, suites, directory, tests, other_serials):
    adb = AdbProxy(serial)
    print(f"{device_state.model(adb)} ({serial}), API {app.sdk(adb)}")
    directory.mkdir(parents=True, exist_ok=True)
    problems = sanity.phone_problems(adb, other_serials)
    if problems:
        return failed_sanity(problems)
    results = {}
    if "ui" in suites:
        results["ui"] = run_ui(adb, directory)
    device_suites = [s for s in suites if s != "ui"]
    if not device_suites:
        return results
    config = mobly_config(directory, serial, phone, headset_name, headset)
    try:
        problems = sanity.connect_problems(adb, headset_controller.create([headset])[0])
        if problems:
            results.update(failed_sanity(problems))
            return results
        for suite in device_suites:
            results[suite] = run_mobly(config, suite, tests)
    finally:
        device_state.set_bluetooth(adb, False)
    return results


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--headset", default="qudelix", help="a headset of testbed.yaml (default qudelix)")
    ap.add_argument("--phone", action="append", help="serial of a phone to test, all on adb by default")
    ap.add_argument("--suite", action="append", choices=SUITES + [SMOKE], help="suites to run, all but smoke by default")
    ap.add_argument("--test", action="append", help="run only these tests of the device suites")
    ap.add_argument("--build", action="store_true", help="build with Gradle first")
    ap.add_argument("--no-install", action="store_true", help="test the build that is installed")
    ap.add_argument("--testbed", type=pathlib.Path, default=HERE / "testbed.yaml")
    args = ap.parse_args()

    phones, headsets = load_testbed(args.testbed)
    if args.headset not in headsets:
        sys.exit(f"no headset {args.headset!r} in {args.testbed}")
    on_adb = android_device.list_adb_devices()
    serials = args.phone or [s for s in phones if s in on_adb]
    missing = [s for s in serials if s not in on_adb or s not in phones]
    if missing:
        sys.exit(f"not on adb or not in {args.testbed.name}: {', '.join(missing)}")
    suites = [s for s in SUITES + [SMOKE] if s in (args.suite or SUITES)]

    if args.build:
        build()
    directory = HERE / "results" / datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    summary = {}
    for serial in serials:
        if not args.no_install:
            install(AdbProxy(serial))
        others = [s for s in on_adb if s != serial]
        summary[serial] = run_phone(serial, phones[serial], args.headset, headsets[args.headset], suites,
                                    directory / serial, args.test, others)
    print(f"\nResults in {directory}")
    for serial, results in summary.items():
        print(f"  {serial}: " + ", ".join(f"{suite} {'passed' if ok else 'FAILED'}" for suite, ok in results.items()))
    sys.exit(0 if all(all(r.values()) for r in summary.values()) else 1)


if __name__ == "__main__":
    main()
