"""The lock screen of a test phone: its class, the test PIN, and unlocking like a user (D24).

testbed.yaml puts every phone in one of three classes:

  pure_test_device: true   No lock screen by default. A test that needs a secure
                           lock screen sets the PIN 0000 and clears it again.
  lock_password: "…"       A personal phone with that PIN or password. It's only
                           ever typed, never set or cleared.
  neither                  A lock screen with an unknown password. The phone
                           isn't rebooted, nobody could unlock it afterwards.

The suite only ever sets or clears the PIN 0000, with the two commands below.
They're hardcoded, and harness_test checks that no other command in the suite
sets or clears a credential. Both refuse to run on anything but a pure test
device.

A personal phone's password is kept out of the results (D25): run_suite.py
passes only the class to Mobly, which records its device config in every
test summary, and the password is typed with a plain adb call, since Mobly's
AdbProxy logs every command.

Checked on Android 9 and 12L (Pixel 3a) and 16 (Galaxy S24).
"""

import re
import shlex
import subprocess
import time

import yaml
from mobly.controllers.android_device_lib import adb as mobly_adb

from lib import device_state

PURE_TEST_DEVICE = "pure test device"
KNOWN_PASSWORD = "known password"
UNKNOWN_PASSWORD = "unknown password"

TEST_PIN = "0000"
_SET_PIN = "locksettings set-pin 0000"
_CLEAR_PIN = "locksettings clear --old 0000"

USER = 0
RUNNING_LOCKED = "RUNNING_LOCKED"
RUNNING_UNLOCKED = "RUNNING_UNLOCKED"
# The unlock steps need a moment in between, or the PIN pad drops the input
STEP_DELAY = 1.0
UNLOCK_TIMEOUT = 15.0


class UnlockFailed(Exception):
    """The phone stayed locked. Never retried: each wrong try counts as a failed attempt."""


def lock_class(phone):
    """The class of a phone entry of testbed.yaml."""
    if phone.get("pure_test_device"):
        return PURE_TEST_DEVICE
    if "lock_password" in phone:
        return KNOWN_PASSWORD
    return UNKNOWN_PASSWORD


def password(testbed, serial):
    """The lock_password of the phone [serial] in the testbed file [testbed], read when it's typed."""
    with open(testbed, encoding="utf-8") as f:
        phones = yaml.safe_load(f)["phones"]
    phone = next(p for p in phones if p["serial"] == serial)
    lclass = lock_class(phone)
    if lclass == PURE_TEST_DEVICE:
        return TEST_PIN
    elif lclass == KNOWN_PASSWORD:
        value = phone.get("lock_password")
        # Unquoted, YAML reads 1234 as a number and 0123 as an octal one
        if not isinstance(value, str):
            raise ValueError(f"the lock_password of {serial} in {testbed} isn't a string, put it in quotes")
        return value
    else:
        return None


def parse_has_credential(dump):
    """Whether dumpsys lock_settings shows a PIN, pattern or password for the user.

    Android 12L and 16 print its CredentialType. Android 9 doesn't, there the
    Gatekeeper SID tells: it's 0 without a credential.
    """
    credential = re.search(r"^\s*CredentialType:\s*(\S+)", dump, re.MULTILINE)
    if credential:
        return credential.group(1) != "None"
    sid = re.search(r"^\s*SID\s*[:=]\s*([0-9a-fA-F]+)\s*$", dump, re.MULTILINE)
    if sid:
        return int(sid.group(1), 16) != 0
    raise RuntimeError(f"no credential type or SID in dumpsys lock_settings: {dump[:500]!r}")


def has_credential(adb):
    return parse_has_credential(device_state.shell(adb, "dumpsys lock_settings", timeout=30))


def parse_user_state(output):
    """The state of `am get-started-user-state`, like RUNNING_LOCKED before the first unlock after a boot."""
    match = re.search(r"\b(RUNNING_\w+|BOOTING|STOPPING|SHUTDOWN)\b", output)
    if not match:
        raise RuntimeError(f"no user state in {output!r}")
    return match.group(1)


def user_state(adb):
    return parse_user_state(device_state.shell(adb, f"am get-started-user-state {USER}"))


def set_pin(adb, phone_class):
    """Sets the PIN 0000 on a pure test device without a credential."""
    if phone_class != PURE_TEST_DEVICE:
        raise RuntimeError(f"only a pure test device gets a PIN, not one with a {phone_class}")
    if has_credential(adb):
        raise RuntimeError("the phone already has a PIN, pattern or password")
    out = device_state.shell(adb, _SET_PIN)
    if not has_credential(adb):
        raise RuntimeError(f"setting the PIN 0000 failed: {out}")


def clear_pin(adb, phone_class):
    """Clears the PIN 0000 of a pure test device.

    Until the next boot the phone then has the swipe lock screen; the test
    images turn it off at every boot.
    """
    if phone_class != PURE_TEST_DEVICE:
        raise RuntimeError(f"only a pure test device's PIN is cleared, not one with a {phone_class}")
    out = device_state.shell(adb, _CLEAR_PIN)
    if has_credential(adb):
        raise RuntimeError(f"clearing the PIN 0000 failed: {out}")


def _type_secret(serial, text):
    """Types [text] with a plain adb call, which unlike Mobly's AdbProxy doesn't log the command."""
    try:
        result = subprocess.run([mobly_adb.ADB, "-s", serial, "shell", f"input text {shlex.quote(text)}"],
                                capture_output=True, timeout=15)
    except subprocess.TimeoutExpired:
        # The exception's message would contain the command
        raise UnlockFailed("typing the PIN or password timed out") from None
    if result.returncode != 0:
        raise UnlockFailed(f"typing the PIN or password failed with exit code {result.returncode}")

def verify(serial, secret=None):
    """Verify the lockscreen password with a a plain adb call"""
    cmd = "locksettings verify"
    if secret is not None:
        cmd += f" --old {shlex.quote(secret)}"
    try:
        result = subprocess.run([mobly_adb.ADB, "-s", serial, "shell", cmd],
                                capture_output=True, timeout=15)
    except subprocess.TimeoutExpired:
        # The exception's message would contain the command
        raise UnlockFailed("verifying the PIN or password timed out") from None
    if result.returncode != 0:
        raise UnlockFailed(f"verifying the PIN or password failed with exit code {result.returncode}")


def unlock(adb, serial, secret=None, timeout=UNLOCK_TIMEOUT):
    """Unlocks the phone like a user: wakes it, dismisses the keyguard, types [secret] and Enter.

    Without [secret] it only dismisses the keyguard. Done once the lock screen
    is gone and the user's storage is unlocked; raises UnlockFailed otherwise.
    Tried once: a caller mustn't try again with the same secret.
    """
    device_state.wake(adb)
    time.sleep(STEP_DELAY)
    device_state.shell(adb, "wm dismiss-keyguard")
    if secret is not None:
        time.sleep(STEP_DELAY)
        _type_secret(serial, secret)
        time.sleep(STEP_DELAY)
        device_state.shell(adb, "input keyevent KEYCODE_ENTER")
    end = time.time() + timeout
    while True:
        unlocked, state = device_state.is_unlocked(adb), user_state(adb)
        if unlocked and state == RUNNING_UNLOCKED:
            return
        if time.time() >= end:
            raise UnlockFailed(f"still locked {timeout:.0f} s after the unlock: "
                               f"lock screen {'dismissed' if unlocked else 'shown'}, user {state}")
        time.sleep(0.5)
