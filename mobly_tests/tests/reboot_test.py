"""L4 tests that reboot the phone: C7 without a lock screen, C14 and C15 with a secure lock screen that is
still locked when A2DP connects.

Before the first unlock after a boot, the app's storage is encrypted and the
system doesn't start it: neither its manifest receiver nor its companion
service, which don't run before the unlock. What happens to the codec and to
the companion device manager then was analysed from the AOSP sources per API
level ("Codec After Locked Reboot"): nothing applies the preferred codec after
the unlock, and on API 31 to 33 the system doesn't find the companion service
for the rest of that boot. C14 and C15 fail until the app handles it.

Which phones are rebooted depends on their lock screen (D24): a pure test
device gets the PIN 0000 for the locked reboot and loses it again, a
personal phone's password is typed, and a phone whose password isn't known
isn't rebooted. A locked reboot ends with a clean reboot (D26).
"""

import time

from mobly import asserts

from lib import app
from lib import baseline
from lib import bt_oracle
from lib import device_state
from lib import lock_screen
from lib.bt_oracle import same_format
from tests.cold_start_test import APPEARANCE_TIMEOUT
from tests.cold_start_test import ColdStartBase

# How long after the unlock the preferred codec may take, measured from the unlock
UNLOCK_APPLY_TIMEOUT = 60.0
# Since API 35 the system reports the device right at the unlock, from the presence events it held back
UNLOCK_APPEARANCE_TIMEOUT = 60.0


class RebootTest(ColdStartBase):
    def setup_class(self):
        super().setup_class()
        # Samsung only, adb can't change it: settings put refuses the key
        if device_state.get_setting(self.adb, "secure", "block_usb_lock") == "1":
            asserts.abort_class("block_usb_lock is 1, adb would stay blocked after a reboot until a manual the unlock")

        if self.lock_class == lock_screen.PURE_TEST_DEVICE and lock_screen.has_credential(self.adb):
            # Left by a run that ended before its teardown. The suite only ever sets 0000, so clearing it is tried once
            self.ad.log.warning("the pure test device has a credential, clearing the PIN 0000")
            try:
                lock_screen.clear_pin(self.adb, self.lock_class)
            except RuntimeError as e:
                asserts.abort_class(f"the pure test device has a credential other than the PIN 0000: {e}")

    def setup_test(self):
        super().setup_test()
        self._pin_set = False
        self._locked_reboot = False
        self._unlock_failed = False

    def require_lock_screen(self):
        self.require(self.lock_class != lock_screen.UNKNOWN_PASSWORD,
                     "the lock screen's password isn't known, the phone can't be unlocked after a reboot")

    def unlock(self):
        """Unlocks the phone like a user would and returns the time, or aborts the run if it stays locked.

        Tried once: a wrong password counts as a failed attempt, which brings
        lockout delays and, on some phones, a factory reset.
        """
        try:
            lock_screen.unlock(self.adb, self.ad.serial, lock_screen.password(self.testbed, self.ad.serial))
        except lock_screen.UnlockFailed as e:
            self._unlock_failed = True
            asserts.abort_all(f"{e}. Unlock {self.ad.serial} by hand, the suite doesn't try again")
        return time.time()

    def reboot_locked(self, codec):
        """Saves [codec] as preferred and reboots with a secure lock screen. Returns the log's last seq
        before the reboot once A2DP is connected while the user is still locked."""
        if self.lock_class == lock_screen.KNOWN_PASSWORD:
            # A testbed.yaml it can't be read from fails now, not at the lock screen
            lock_screen.verify(self.ad.serial, lock_screen.password(self.testbed, self.ad.serial))
        self.save_preferred(codec)
        # The fixture granted the permission just before, and a reboot loses it until the system writes it
        app.wait_permission_persisted(self.adb)
        if self.lock_class == lock_screen.PURE_TEST_DEVICE:
            lock_screen.set_pin(self.adb, self.lock_class)
            self._pin_set = True
        mark = self.log.last_seq()
        self._locked_reboot = True
        self.ad.reboot()
        asserts.assert_equal(lock_screen.RUNNING_LOCKED, lock_screen.user_state(self.adb),
                             "the user was unlocked without the lock screen, does the phone have a secure one?")
        try:
            bt_oracle.wait_connected(self.adb, self.address, timeout=baseline.CONNECT_TIMEOUT)
        except TimeoutError:
            # The connect fixture's last resort, the phone's Bluetooth settings, is behind the lock screen
            self.headset.reconnect(self.adb)
            try:
                bt_oracle.wait_connected(self.adb, self.address, timeout=baseline.CONNECT_TIMEOUT)
            except TimeoutError:
                asserts.fail("A2DP didn't connect before the unlock, not even after the headset reconnected")
        asserts.assert_equal(lock_screen.RUNNING_LOCKED, lock_screen.user_state(self.adb),
                             "the user was unlocked before A2DP connected")
        dumped = bt_oracle.active_codec(self.adb, self.address)
        self.ad.log.info("A2DP connected before the unlock with %s, the preferred codec is %s", dumped, codec)
        return mark

    def appearance(self, after, timeout):
        """The first DEVICE_APPEARED after seq [after] within [timeout] s, or None."""
        try:
            return self.log.wait_for_event("DEVICE_APPEARED", after=after, device=self.address, timeout=timeout)
        except TimeoutError:
            return None

    def reset_phone(self):
        """Clears the PIN and, after a locked reboot, reboots again with Bluetooth off (D26).

        On API 31 to 33, and on 34 after a BLE sighting, the system keeps the
        companion service it didn't find before the unlock until the next
        reboot. With Bluetooth off nothing appears before the unlock.
        """
        if self._unlock_failed:
            asserts.abort_all(f"{self.ad.serial} is still locked")
        if not (self._pin_set or self._locked_reboot):
            return
        if not device_state.is_unlocked(self.adb) or lock_screen.user_state(self.adb) != lock_screen.RUNNING_UNLOCKED:
            self.unlock()
        if self._locked_reboot:
            device_state.set_bluetooth(self.adb, False)
        if self._pin_set:
            lock_screen.clear_pin(self.adb, self.lock_class)
            self._pin_set = False
        if self._locked_reboot:
            self.ad.reboot()
            self.unlock()
            self._locked_reboot = False
            # The baseline's repair connects the headset
            device_state.set_bluetooth(self.adb, True)

    def test_c7_after_a_reboot(self):
        """After a reboot, without opening the app: applied."""
        self.require(self.lock_class == lock_screen.PURE_TEST_DEVICE, "only a pure test device has no lock screen")
        codec = self.preferred_codec()
        self.save_preferred(codec)
        # The fixture granted the permission just before, and a reboot loses it until the system writes it
        app.wait_permission_persisted(self.adb)
        mark = self.log.last_seq()
        self.ad.reboot()
        # A pure test device has no lock screen, the keyguard only needs dismissing
        device_state.wake(self.adb)
        device_state.shell(self.adb, "wm dismiss-keyguard")
        baseline.connect(self.adb, self.headset)
        ops = self.wait_for_applied(mark, codec, timeout=120)
        self.ad.log.info("C7: %s", self.describe(ops))

    def test_c14_locked_reboot_applies_after_the_unlock(self):
        """After a reboot with a secure lock screen, A2DP connects before the first unlock: applied after
        the unlock, without another connect.

        Fails today on every API level: the A2DP broadcast reaches no receiver
        of the app before the unlock, and nothing applies the codec after it.
        """
        self.require_lock_screen()
        codec = self.preferred_codec()
        mark = self.reboot_locked(codec)
        unlocked = self.unlock()
        ops = self.wait_for_applied(mark, codec, timeout=UNLOCK_APPLY_TIMEOUT)
        self.ad.log.info("C14: applied %.0f s after the unlock: %s", time.time() - unlocked, self.describe(ops))

    def test_c15_companion_service_after_a_locked_reboot(self):
        """After the same locked reboot, the system reports the device to the companion service: after
        the unlock, since it's connected, and again when it next connects.

        Fails today on API 31 to 33: the system looked the service up while
        the user was locked and found nothing. On 31 and 32 it keeps that
        until the next reboot, on 33 until a package changes. On 34 the same
        happens after a BLE sighting before the unlock. Since API 35 it holds
        the presence events back and reports them at the unlock.
        """
        self.require_lock_screen()
        self.require(self.sdk >= 31, "presence is only observed since API 31")
        codec = self.preferred_codec()
        mark = self.reboot_locked(codec)
        self.unlock()
        after_unlock = self.appearance(mark, UNLOCK_APPEARANCE_TIMEOUT)
        self.headset_off()
        # Gone for the system too: on API 32 a device counts as gone 7 to 8 s after its BLE advertisements stop
        time.sleep(15)
        mark = self.log.last_seq()
        self.headset_on()
        bt_oracle.wait_connected(self.adb, self.address, timeout=baseline.CONNECT_TIMEOUT)
        on_connect = self.appearance(mark, APPEARANCE_TIMEOUT)
        events = self.log.events_after(mark)
        ops = self.resolved_after(mark)
        applied = same_format(codec, bt_oracle.active_codec(self.adb, self.address))
        self.ad.log.info("C15 on API %d: appeared after the unlock %s, on the next connect %s; applied %s: %s",
                         self.sdk, after_unlock is not None, on_connect is not None, applied, self.describe(ops))
        missing = [when for when, event in (("after the unlock", after_unlock), ("on the next connect", on_connect))
                   if event is None]
        asserts.assert_equal([], missing, f"the system didn't report the device to the companion service; "
                                          f"events since the connect: {[e['type'] for e in events]}")
