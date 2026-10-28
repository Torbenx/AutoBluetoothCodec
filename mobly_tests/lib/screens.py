"""Screen objects for the journeys, through snippet-uiautomator (D4).

Elements are found by the test tags the app exposes as resource ids, and by
their texts. System dialogs are found by their package, since their texts
follow the phone's language: the S24 runs in German.
"""

import datetime

from mobly import asserts
from snippet_uiautomator import uiautomator

from lib import app

TIMEOUT = datetime.timedelta(seconds=10)
# The companion device manager's dialog, which asks to link a device
COMPANION_PACKAGES = ("com.android.companiondevicemanager", "com.samsung.android.companiondevicemanager")
# Its positive button, by text in the phones' languages
ALLOW = r"(?i)(allow|zulassen|erlauben|ok|verknüpfen|koppeln|pair|link)"


def register(ad):
    """Starts snippet-uiautomator on the phone, it runs in its own process."""
    if not ad.services.has_service_by_name(uiautomator.ANDROID_SERVICE_NAME):
        ad.services.register(uiautomator.ANDROID_SERVICE_NAME, uiautomator.UiAutomatorService)


class MainScreen:
    def __init__(self, ad, headset):
        self.ad = ad
        self.ui = ad.ui
        self.headset = headset
        self.card_res = "device_" + headset.address.upper()

    def open(self):
        app.launch(self.ad.adb)
        asserts.assert_true(self.ui(res=self.card_res).wait.exists(TIMEOUT), "the device's card isn't shown")
        return self

    def toggle(self):
        """Expands or collapses the card, by tapping the device's name."""
        asserts.assert_true(self.ui(res=self.card_res).child(text=self.headset.name).wait.click(TIMEOUT),
                            "can't tap the device's name")

    def chip(self, text):
        return self.ui(res=self.card_res).child(text=text)

    def tap_chip(self, text):
        asserts.assert_true(self.chip(text).wait.click(TIMEOUT), f"no chip {text!r}")

    def shows(self, *texts):
        """Whether the card shows all [texts]."""
        return all(self.chip(text).wait.exists(TIMEOUT) for text in texts)

    def status(self):
        """The status chip's text, None if there is none."""
        chip = self.ui(res="status_chip")
        if not chip.wait.exists(TIMEOUT):
            return None
        text = chip.text
        return text or chip.child(textMatches=".+").text

    def tap_status(self):
        asserts.assert_true(self.ui(res="status_chip").wait.click(TIMEOUT), "no status chip")

    def tap_link(self):
        asserts.assert_true(self.ui(res="link_device").wait.click(TIMEOUT), "no Allow button")

    def accept_link_dialog(self):
        """Accepts the companion device manager's dialog that links the device."""
        dialog = None
        for package in COMPANION_PACKAGES:
            if self.ui(pkg=package).wait.exists(TIMEOUT):
                dialog = package
                break
        asserts.assert_is_not_none(dialog, "the dialog to link the device didn't appear")
        # Some versions list the device to choose first
        name = self.ui(pkg=dialog, textContains=self.headset.name)
        allow = self.ui(pkg=dialog, textMatches=ALLOW, enabled=True)
        if not allow.wait.exists(datetime.timedelta(seconds=3)) and name.exists:
            name.click()
        asserts.assert_true(allow.wait.click(TIMEOUT), "the dialog has no button to allow linking")
        self.ui(pkg=dialog).wait.gone(TIMEOUT)
