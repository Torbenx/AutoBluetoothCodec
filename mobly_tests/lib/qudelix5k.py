"""Control a Qudelix-5K over USB HID, mirroring what the official Qudelix
Chrome extension (v4.0.0.0) does. Used to force Bluetooth disconnects and
reconnects on the test phone.

Protocol (reverse-engineered from the extension's App-*.js):
  * Vendor HID interface: VID 0x0A12, usage page 0xFF00.
  * Host->device: output report 8 (63 bytes) = [len(cmd+data)+1, 0x80, cmd_hi, cmd_lo, data...]
  * Device->host: input report 9 = [len, cmd_hi, cmd_lo, data...]
  * The firmware ignores every command until a host sends ReqInitData (0x0100)
    with the protocol version (2.0.0 -> 0x2000) and 4 ("req"); it answers
    RspInitData (0x0101) with status 3 ("success") at data offset 2. The
    session is device-wide and survives closing the handle; it only ends on
    Disconnect (0x0107) or a reboot. We never send Disconnect, since that
    would also end the session of an extension that is open at the same time.
  * SetUsbDacMode (0x0217) + 1 byte: 0 Normal, 1 Disconnect A2DP,
    2 Disconnect A2DP & HFP, 3 Bluetooth Off (until next power on).
    Leaving mode 3 needs a SysReset (0x1000), exactly like the extension does.

Requires: pip install hidapi
"""

import argparse
import sys
import time

import hid

VID = 0x0A12
PIDS = {16387, 16388, 16389, 16390, 16391, 16677, 16678, 16658}  # x5kProductIds (partial list from extension)
VENDOR_USAGE_PAGE = 0xFF00
OUT_REPORT_ID = 8
OUT_REPORT_SIZE = 63
IN_REPORT_ID = 9

CMD_REQ_INIT_DATA = 0x0100
CMD_RSP_INIT_DATA = 0x0101
INIT_REQ_DATA = [0x20, 0x00, 0x04]  # protocol 2.0.0, "req"
INIT_SUCCESS = 3
CMD_REQ_DEV_CONFIG = 0x0120
CMD_RSP_DEV_CONFIG = 0x0121
CMD_SET_USB_DAC_MODE = 0x0217
CMD_SYS_RESET = 0x1000
CFG_SYS2 = 64

MODES = {
    "normal": 0,
    "no-a2dp": 1,
    "no-a2dp-hfp": 2,
    "bt-off": 3,
}
MODE_NAMES = {v: k for k, v in MODES.items()}


def find_path():
    for d in hid.enumerate(VID, 0):
        if d["product_id"] in PIDS and d["usage_page"] == VENDOR_USAGE_PAGE:
            return d["path"]
    return None


class Qudelix:
    def __init__(self, path):
        self.dev = hid.device()
        self.dev.open_path(path)
        self.dev.set_nonblocking(True)
        try:
            self.init()
        except BaseException:
            self.dev.close()
            raise

    def init(self):
        # Like the extension, drain reports still queued before asking for a session.
        end = time.time() + 0.2
        while time.time() < end:
            if not self.dev.read(128):
                time.sleep(0.01)
        self.send(CMD_REQ_INIT_DATA, INIT_REQ_DATA)
        rsp = self.wait_for(CMD_RSP_INIT_DATA)
        if rsp is None or len(rsp) < 3 or rsp[2] != INIT_SUCCESS:
            raise RuntimeError("no init response")

    def close(self):
        self.dev.close()

    def send(self, cmd, data=b""):
        payload = bytes([cmd >> 8, cmd & 0xFF]) + bytes(data)
        pkt = bytes([len(payload) + 1, 0x80]) + payload
        self.dev.write(bytes([OUT_REPORT_ID]) + pkt.ljust(OUT_REPORT_SIZE, b"\0"))

    def wait_for(self, cmd, timeout=2.0):
        end = time.time() + timeout
        while time.time() < end:
            r = self.dev.read(128)
            if not r:
                time.sleep(0.01)
                continue
            if r[0] != IN_REPORT_ID or len(r) < 4:
                continue
            length = r[1]
            if (r[2] << 8 | r[3]) == cmd:
                return bytes(r[4:length + 2])
        return None

    def get_usb_dac_mode(self):
        self.send(CMD_REQ_DEV_CONFIG, [CFG_SYS2])
        rsp = self.wait_for(CMD_RSP_DEV_CONFIG)
        if rsp is None or not rsp[0] & CFG_SYS2:
            raise RuntimeError("no sys2 config response")
        # sys2 block starts after the mask byte; BtMode4UsbDac is 2 bits at
        # bit offset 109 (LSB-first), i.e. byte 13 bits 5..6.
        return (rsp[1 + 13] >> 5) & 0x3

    def set_usb_dac_mode(self, mode):
        self.send(CMD_SET_USB_DAC_MODE, [mode])

    def reboot(self):
        self.send(CMD_SYS_RESET)


def open_device(wait=0.0):
    end = time.time() + wait
    while True:
        path = find_path()
        if path:
            return Qudelix(path)
        if time.time() >= end:
            sys.exit("Qudelix-5K not found on USB")
        time.sleep(0.5)


def set_mode(name, wait):
    q = open_device()
    try:
        current = q.get_usb_dac_mode()
        target = MODES[name]
        if current == target:
            print(f"already {name}")
            return
        q.set_usb_dac_mode(target)
        # Same as the extension: leaving "Bluetooth off" only takes effect after a reboot.
        if current == MODES["bt-off"]:
            time.sleep(0.2)
            q.reboot()
            print(f"{MODE_NAMES[current]} -> {name} (rebooting)")
        else:
            print(f"{MODE_NAMES[current]} -> {name}")
    finally:
        q.close()
    if current == MODES["bt-off"] and wait:
        wait_until_back(wait)


def reboot(wait):
    """Restarts the 5K in its current mode. The phone sees it go away and come
    back, like switching it off and on."""
    q = open_device()
    try:
        q.reboot()
        print("rebooting")
    finally:
        q.close()
    if wait:
        wait_until_back(wait)


def wait_until_back(wait):
    """Waits for a rebooting 5K to leave USB and answer again, up to [wait] s."""
    end = time.time() + 5
    while find_path() and time.time() < end:
        time.sleep(0.1)
    print("device left USB" if not find_path() else "warning: device never left USB")
    end = time.time() + wait
    while True:
        # Firmware re-enumerates on USB slightly before it answers commands.
        try:
            q = open_device(max(0.0, end - time.time()))
        except (RuntimeError, OSError):
            q = None
        if q:
            try:
                q.get_usb_dac_mode()
                break
            except (RuntimeError, OSError):
                pass
            finally:
                q.close()
        if time.time() >= end:
            sys.exit("Qudelix-5K back on USB but not responding")
        time.sleep(0.5)
    print("device back on USB")


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("status", help="print the current Bluetooth mode for USB DAC")
    for name in MODES:
        sub.add_parser(name, help=f"set Bluetooth mode for USB DAC to {name}")
    cyc = sub.add_parser("cycle", help="bt-off, wait, then normal (reconnects the phone)")
    cyc.add_argument("--off-seconds", type=float, default=5.0)
    sub.add_parser("reboot", help="restart the 5K")
    ap.add_argument("--wait", type=float, default=15.0,
                    help="seconds to wait for the 5K to re-enumerate after a reboot (0 = don't)")
    args = ap.parse_args()

    if args.cmd == "status":
        q = open_device()
        try:
            print(MODE_NAMES.get(q.get_usb_dac_mode(), "unknown"))
        finally:
            q.close()
    elif args.cmd == "reboot":
        reboot(args.wait)
    elif args.cmd == "cycle":
        set_mode("bt-off", args.wait)
        time.sleep(args.off_seconds)
        set_mode("normal", args.wait)
    else:
        set_mode(args.cmd, args.wait)


if __name__ == "__main__":
    main()
