"""L3, the Bluetooth logic tests: the app's logic against the real stack, with the snippet loaded.

The snippet is loaded for each test after the baseline check and unloaded
before the restore, which resets the app's data and so ends its process.
"""

import time

from mobly import asserts
from mobly.snippet import errors as snippet_errors

from lib import app
from lib import app_log
from lib import bt_oracle
from lib import companion
from lib.bt_oracle import Codec
from lib.device_test import BaselineTest

SNIPPET = "snippet"
SBC_44_16 = Codec(bt_oracle.SBC, 0x1, 0x1)
LDAC_96_32_ADAPTIVE = Codec(bt_oracle.LDAC, 0x8, 0x4, codec_specific1=13)
LDAC_44_24_909 = Codec(bt_oracle.LDAC, 0x1, 0x2, codec_specific1=10)
RATE_ORDER = (0x1, 0x2, 0x4, 0x8)
BITS_ORDER = (0x1, 0x2, 0x4)


def same_format(a, b):
    """The same codec, rate and size, the options aside."""
    return (a.codec_type, a.sample_rate, a.bits_per_sample) == (b.codec_type, b.sample_rate, b.bits_per_sample)


class SnippetTest(BaselineTest):
    def setup_test(self):
        super().setup_test()
        self.ad.load_snippet(SNIPPET, app.SNIPPET_PACKAGE)
        self.snippet = getattr(self.ad, SNIPPET)

    def teardown_test(self):
        try:
            self.ad.unload_snippet(SNIPPET)
        except Exception:
            self.ad.log.exception("unloading the snippet failed")
        super().teardown_test()

    @property
    def address(self):
        return self.headset.address

    def apply(self, codec):
        """Applies [codec] through the app's CodecApplier, returns the resolved operation."""
        result = self.snippet.applyCodec(self.address, codec.to_app())
        asserts.assert_is_not_none(result["resolution"], f"applying {codec} didn't resolve")
        return self.operation(result["operation"])

    def operation(self, uid, timeout=5.0):
        """The resolved operation [uid]; the app writes its log on a background thread."""
        end = time.time() + timeout
        while True:
            op = next((op for op in app_log.operations(self.log.read()) if op.uid == uid), None)
            if op is not None and op.resolution:
                return op
            if time.time() >= end:
                raise AssertionError(f"operation {uid} isn't resolved in the log: {op}")
            time.sleep(0.2)

    def dumped_codec(self):
        codec = bt_oracle.active_codec(self.adb, self.address)
        asserts.assert_is_not_none(codec, "A2DP isn't connected")
        return codec

    def capabilities(self):
        return bt_oracle.device(self.adb, self.address).capabilities


class BtLogicTest(SnippetTest):
    def test_b1_read_contract(self):
        """The codec and capabilities the app reads are what dumpsys shows."""
        read = Codec.from_app(self.snippet.getCurrentCodec(self.address))
        dumped = self.dumped_codec()
        asserts.assert_true(read.same_config(dumped), f"the app reads {read}, dumpsys shows {dumped}")
        app_caps = {(c["codecType"], c["sampleRates"], c["bitsPerSample"]) for c in self.snippet.getCapabilities(self.address)}
        dumped_caps = {(c.codec_type, c.sample_rates, c.bits_per_sample) for c in self.capabilities()}
        asserts.assert_equal(dumped_caps, app_caps, "capabilities")
        asserts.assert_true(any(c.codec_type == dumped.codec_type and c.supports(dumped.sample_rate, dumped.bits_per_sample)
                                for c in self.capabilities()), f"the active codec {dumped} isn't offered")

    def test_b2_apply_every_codec_type(self):
        """Every codec type the phone and headset offer is applied and verified, and dumpsys agrees."""
        current = self.dumped_codec()
        failures = []
        for capability in self.capabilities():
            rate = current.sample_rate if capability.sample_rates & current.sample_rate else \
                next(r for r in RATE_ORDER if capability.sample_rates & r)
            bits = current.bits_per_sample if capability.bits_per_sample & current.bits_per_sample else \
                next(b for b in BITS_ORDER if capability.bits_per_sample & b)
            target = Codec(capability.codec_type, rate, bits)
            op = self.apply(target)
            dumped = self.dumped_codec()
            self.ad.log.info("%s: %s after %s attempts, dumpsys %s", target, op.resolution,
                             (op.resolution_event or {}).get("attempt"), dumped)
            if op.resolution != "CODEC_VERIFIED" or not same_format(target, dumped):
                failures.append(f"{target}: {op.resolution}, dumpsys {dumped}")
            current = dumped
        asserts.assert_equal([], failures, "codec types that weren't applied")

    def test_b3_multi_step_change_with_the_main_screen_open(self):
        """LDAC 96 kHz, 32-bit, adaptive to 44.1 kHz, 24-bit, 909 kbps within 5 attempts (manual 1.3),
        and the main screen logs none of the codecs on the way as external changes."""
        ldac = next((c for c in self.capabilities() if c.codec_type == bt_oracle.LDAC), None)
        self.require(ldac and ldac.supports(0x8, 0x4) and ldac.supports(0x1, 0x2), "LDAC 96/32 and 44.1/24 aren't offered")
        app.launch(self.adb)
        # The first start logs the current codec, the log is empty
        self.log.wait_for_event("ACTIVITY_OBSERVED_EXTERNAL_CODEC_CHANGE", timeout=10)
        self.snippet.setCodecDirectly(self.address, LDAC_96_32_ADAPTIVE.to_app())
        bt_oracle.wait_for(self.adb, lambda s: same_format(LDAC_96_32_ADAPTIVE, s.device(self.address).codec), 10,
                           what="LDAC 96/32")
        # The main screen polls every second and logs the direct change
        time.sleep(3)
        mark = self.log.last_seq()
        op = self.apply(LDAC_44_24_909)
        attempts = op.resolution_event.get("attempt")
        self.ad.log.info("multi-step change: %s after %s attempts", op.resolution, attempts)
        asserts.assert_equal("CODEC_VERIFIED", op.resolution, str(op))
        asserts.assert_true(same_format(LDAC_44_24_909, self.dumped_codec()), f"dumpsys shows {self.dumped_codec()}")
        time.sleep(3)
        external = [e for e in self.log.events_after(mark) if e["type"] == "ACTIVITY_OBSERVED_EXTERNAL_CODEC_CHANGE"]
        asserts.assert_equal([], external, "the app's own changes were logged as external")

    def test_b8_links(self):
        """Without a link: up to API 33 everything works, on 34-35 changing needs it, on 36 reading too."""
        companion.unlink(self.adb, app.PACKAGE, self.address)
        if self.sdk >= 36:
            with asserts.assert_raises(snippet_errors.ApiError):
                self.snippet.getCurrentCodec(self.address)
        else:
            asserts.assert_is_not_none(self.snippet.getCurrentCodec(self.address), "the codec isn't readable")
        op = self.apply(SBC_44_16)
        expected = "NO_ASSOCIATION" if self.sdk >= 34 else "CODEC_VERIFIED"
        asserts.assert_equal(expected, op.resolution, str(op))
        if self.sdk >= 36:
            app.launch(self.adb)
            time.sleep(4)
            read_failed = [e for e in self.log.read() if e["type"] == "READ_FAILED"]
            asserts.assert_equal(1, len(read_failed), "READ_FAILED is logged once while the screen polls")
            app.go_home(self.adb)
        companion.link(self.adb, app.PACKAGE, self.address)
        op = self.apply(SBC_44_16)
        asserts.assert_equal("CODEC_VERIFIED", op.resolution, f"with a link: {op}")
