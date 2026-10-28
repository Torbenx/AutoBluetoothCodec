"""Self-tests of the harness, against outputs recorded on the phones. No phone needed:

  cd mobly_tests
  python -m unittest tests.harness_test
"""

import pathlib
import unittest

from lib import app, app_log, bt_oracle, companion

TESTDATA = pathlib.Path(__file__).resolve().parents[1] / "testdata"
SAMPLE_LOG = pathlib.Path(__file__).resolve().parents[2] / "app/src/sharedTest/resources/sample_event_log.jsonl"
QUDELIX = "0A:1B:2C:3D:4E:5F"


def read(*parts):
    return TESTDATA.joinpath(*parts).read_text(encoding="utf-8")


class BtOracleTest(unittest.TestCase):
    def check_connected(self, name, codec, capabilities):
        state = bt_oracle.parse(read("bluetooth_manager", name))
        self.assertEqual("ON", state.adapter_state)
        self.assertTrue(state.is_active(QUDELIX))
        device = state.device(QUDELIX)
        self.assertIsNotNone(device)
        self.assertTrue(device.connected)
        self.assertTrue(codec.same_config(device.codec), device.codec)
        self.assertEqual(capabilities, [c.codec_type for c in device.capabilities])
        self.assertIsNone(state.device("AA:BB:CC:DD:EE:FF"))
        return device

    def test_api28(self):
        device = self.check_connected("api28_connected.txt", bt_oracle.Codec(bt_oracle.LDAC, 0x8, 0x4),
                                      [bt_oracle.LDAC, bt_oracle.APTX_HD, bt_oracle.APTX, bt_oracle.AAC, bt_oracle.SBC])
        self.assertTrue(device.capabilities[0].supports(0x8, 0x4))

    def test_api32(self):
        device = self.check_connected("api32_connected.txt", bt_oracle.Codec(bt_oracle.LDAC, 0x8, 0x4),
                                      [bt_oracle.LDAC, bt_oracle.APTX_HD, bt_oracle.APTX, bt_oracle.AAC, bt_oracle.SBC])
        self.assertEqual(5001, device.codec.priority)
        self.assertFalse(device.capabilities[1].supports(0x8, 0x2))

    def test_api36_matchesMaskedAddresses(self):
        device = self.check_connected("api36_connected.txt", bt_oracle.Codec(bt_oracle.LDAC, 0x1, 0x2, codec_specific1=10),
                                      [bt_oracle.LDAC, bt_oracle.APTX, bt_oracle.AAC, bt_oracle.SBC])
        self.assertEqual("XX:XX:XX:XX:4E:5F", device.address)
        # Set by the app, with the highest priority
        self.assertEqual(1000000, device.codec.priority)

    def test_sameDevice(self):
        self.assertTrue(bt_oracle.same_device("XX:XX:XX:XX:4E:5F", "0a:1b:2c:3d:4e:5f"))
        self.assertFalse(bt_oracle.same_device("XX:XX:XX:XX:4E:5E", QUDELIX))
        self.assertTrue(bt_oracle.same_device(QUDELIX, QUDELIX.lower()))

    def test_disconnectedDump(self):
        dump = read("bluetooth_manager", "api32_connected.txt").replace("mConnectionState: CONNECTED", "mConnectionState: DISCONNECTED")
        self.assertFalse(bt_oracle.parse(dump).device(QUDELIX).connected)
        self.assertEqual((), bt_oracle.parse("Bluetooth Status\n  state: OFF\n").devices)

    def test_codecJsonRoundTrips(self):
        codec = bt_oracle.Codec(bt_oracle.LDAC, 0x8, 0x2, codec_specific1=13)
        self.assertEqual(codec, bt_oracle.Codec.from_app(codec.to_app()))

    def test_codecsToChangeTo(self):
        capabilities = bt_oracle.parse(read("bluetooth_manager", "api36_connected.txt")).device(QUDELIX).capabilities
        outside = bt_oracle.codec_outside(capabilities)
        self.assertEqual(bt_oracle.OPUS, outside.codec_type)
        self.assertFalse(any(c.codec_type == outside.codec_type for c in capabilities))
        only_sbc = (bt_oracle.Capability(bt_oracle.SBC, 0x3, 0x1),)
        self.assertEqual(bt_oracle.Codec(bt_oracle.OPUS, 0x2, 0x1), bt_oracle.codec_outside(only_sbc))
        ldac = bt_oracle.Codec(bt_oracle.LDAC, 0x8, 0x4)
        self.assertEqual(bt_oracle.Codec(bt_oracle.SBC, 0x1, 0x1), bt_oracle.other_codec(ldac))
        self.assertEqual(bt_oracle.LDAC, bt_oracle.other_codec(bt_oracle.Codec(bt_oracle.SBC, 0x1, 0x1), capabilities).codec_type)
        self.assertTrue(bt_oracle.same_format(ldac, bt_oracle.Codec(bt_oracle.LDAC, 0x8, 0x4, codec_specific1=1003)))
        self.assertFalse(bt_oracle.same_format(ldac, None))


class CompanionTest(unittest.TestCase):
    def test_listUpTo35(self):
        for name in ("api28_list.txt", "api32_list.txt"):
            self.assertEqual([companion.Association(app.PACKAGE, QUDELIX)],
                             companion.parse_list(read("companiondevice", name)))

    def test_listOn36HasIds(self):
        self.assertEqual([companion.Association(app.PACKAGE, QUDELIX, 7)],
                         companion.parse_list(read("companiondevice", "api36_list.txt")))

    def test_observingAndPresence(self):
        dump = read("companiondevice", "api36_dumpsys.txt")
        self.assertTrue(companion.parse_observing(dump, app.PACKAGE, QUDELIX))
        self.assertIsNone(companion.parse_observing(dump, "other.package", QUDELIX))
        self.assertTrue(companion.parse_present(dump, QUDELIX))
        # API 32 shows neither
        dump32 = read("companiondevice", "api32_dumpsys.txt")
        self.assertIsNone(companion.parse_observing(dump32, app.PACKAGE, QUDELIX))
        self.assertIsNone(companion.parse_present(dump32, QUDELIX))


class AppLogTest(unittest.TestCase):
    def setUp(self):
        self.events = app_log.parse(SAMPLE_LOG.read_text(encoding="utf-8"))

    def test_sampleLogHasOneOpenOperation(self):
        self.assertEqual([], app_log.check_structure(self.events, allow_open=1))
        self.assertEqual(1, len(app_log.check_structure(self.events)))

    def test_brokenStructure(self):
        broken = [e for e in self.events if e["type"] != "A2DP_CONNECTED"]
        self.assertTrue(any("breaks the operation structure" in p for p in app_log.check_structure(broken, allow_open=99)))
        reordered = self.events[1:2] + self.events[0:1]
        self.assertTrue(any("seq" in p for p in app_log.check_structure(reordered, allow_open=99)))

    def test_operations(self):
        ops = app_log.operations(self.events)
        first = ops[0]
        self.assertEqual("A2DP_CONNECTED", first.trigger["type"])
        self.assertEqual("CODEC_VERIFIED", first.resolution)
        self.assertEqual(2, len(first.of_type("SET_CODEC_CONFIG")))
        self.assertIsNone(ops[-1].resolution)

    def test_partialLastLine(self):
        self.assertEqual(1, len(app_log.parse('{"v":1,"seq":0,"time":0,"type":"TEST_LABEL"}\n{"v":1,"se')))


class InstrumentationTest(unittest.TestCase):
    def test_results(self):
        self.assertEqual((38, 0, []), app.parse_instrumentation("INSTRUMENTATION_RESULT: stream=\n\nOK (38 tests)\n"))
        output = ("INSTRUMENTATION_STATUS: class=com.tt.autobluetoothcodec.MainScreenTest\n"
                  "INSTRUMENTATION_STATUS: test=noCodecInformation\n"
                  "INSTRUMENTATION_STATUS_CODE: -2\n"
                  "Tests run: 38,  Failures: 1\n")
        self.assertEqual((38, 1, ["com.tt.autobluetoothcodec.MainScreenTest#noCodecInformation"]),
                         app.parse_instrumentation(output))


if __name__ == "__main__":
    unittest.main()
