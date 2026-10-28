"""Headsets as a Mobly controller, so tests don't depend on how a headset is switched.

The headset is part of the testbed: testbed.yaml names it and its backend,
and the same tests run with each. A backend offers what it can, tests that
need more check `capabilities` and skip:

  * qudelix: the Qudelix-5K over USB, see tools/qudelix5k.py. It can switch
    A2DP alone (no-a2dp) and its Bluetooth (bt-off), so it has both
    capabilities.
  * phone: any paired headset. Only the phone's Bluetooth adapter can be
    switched over adb, so it has neither capability (D14).

Testbed config:
  Headset:
    - backend: qudelix
      address: "0A:1B:2C:3D:4E:5F"
      name: Qudelix-5K
"""

import pathlib
import sys
import time

from lib import device_state

MOBLY_CONTROLLER_CONFIG_NAME = "Headset"

# A2DP goes down and up while the device stays present
A2DP_SWITCH = "a2dp_switch"
# The device disappears and appears while the phone's Bluetooth stays on
POWER_SWITCH = "power_switch"

_TOOLS = pathlib.Path(__file__).resolve().parents[2] / "tools"


class Headset:
    backend = None
    capabilities = frozenset()

    def __init__(self, config):
        self.address = config["address"].upper()
        self.name = config.get("name", self.address)

    def reconnect(self, adb):
        """The device goes away and comes back, like switching it off and on.

        A full reconnect brings the codec back to the phone's own choice; an
        A2DP-only reconnect keeps the codec that was set.
        """
        raise NotImplementedError

    def disconnect_a2dp(self):
        raise NotImplementedError(f"{self.backend} headsets can't switch A2DP alone")

    def connect_a2dp(self):
        raise NotImplementedError(f"{self.backend} headsets can't switch A2DP alone")

    def power_off(self):
        raise NotImplementedError(f"{self.backend} headsets can't be switched off")

    def power_on(self, a2dp=True):
        """Switches the headset on, with A2DP or with only its link and BLE."""
        raise NotImplementedError(f"{self.backend} headsets can't be switched on")

    def is_normal(self):
        """Whether the headset is in its normal state, its part of the baseline."""
        return True

    def restore_normal(self):
        pass

    def status(self):
        return "normal"

    def info(self):
        return {"backend": self.backend, "address": self.address, "name": self.name}


class QudelixHeadset(Headset):
    backend = "qudelix"
    capabilities = frozenset({A2DP_SWITCH, POWER_SWITCH})
    # How long the 5K stays off in reconnect(), like `qudelix5k.py cycle`
    OFF_SECONDS = 5.0

    def __init__(self, config):
        super().__init__(config)
        if str(_TOOLS) not in sys.path:
            sys.path.insert(0, str(_TOOLS))
        import qudelix5k
        self._q = qudelix5k

    def _set(self, mode):
        try:
            self._q.set_mode(mode, wait=15.0)
        except SystemExit as e:
            # The tool exits on errors, which must not end the test run
            raise RuntimeError(f"Qudelix: {e}") from None

    def status(self):
        try:
            device = self._q.open_device(wait=5.0)
        except SystemExit as e:
            raise RuntimeError(f"Qudelix: {e}") from None
        try:
            return self._q.MODE_NAMES.get(device.get_usb_dac_mode(), "unknown")
        finally:
            device.close()

    def reconnect(self, adb):
        self._set("bt-off")
        time.sleep(self.OFF_SECONDS)
        self._set("normal")

    def disconnect_a2dp(self):
        self._set("no-a2dp")

    def connect_a2dp(self):
        self._set("normal")

    def power_off(self):
        self._set("bt-off")

    def power_on(self, a2dp=True):
        # Leaving bt-off reboots the 5K into the new mode
        self._set("normal" if a2dp else "no-a2dp")

    def is_normal(self):
        return self.status() == "normal"

    def restore_normal(self):
        self._set("normal")


class PhoneHeadset(Headset):
    """Any headset, switched through the phone's Bluetooth adapter."""
    backend = "phone"
    OFF_SECONDS = 3.0

    def reconnect(self, adb):
        device_state.set_bluetooth(adb, False)
        time.sleep(self.OFF_SECONDS)
        device_state.set_bluetooth(adb, True)


BACKENDS = {cls.backend: cls for cls in (QudelixHeadset, PhoneHeadset)}


def create(configs):
    headsets = []
    for config in configs:
        backend = config.get("backend")
        if backend not in BACKENDS:
            raise ValueError(f"unknown headset backend {backend!r}, expected one of {sorted(BACKENDS)}")
        headsets.append(BACKENDS[backend](config))
    return headsets


def destroy(objects):
    pass


def get_info(objects):
    return [headset.info() for headset in objects]
