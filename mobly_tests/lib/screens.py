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
# The permission dialog's buttons, the same ids on every phone with a runtime permission
PERMISSION_ALLOW = "com.android.permissioncontroller:id/permission_allow_button"
# The device menu's items, whichever it shows; not the status chip, whose texts end in "Preferred Codec" too
MENU_ITEMS = r"(Restore|Delete) Preferred Codec|Unlink.*"
PERMISSION_DENY = ("com.android.permissioncontroller:id/permission_deny_button",
                   "com.android.permissioncontroller:id/permission_deny_and_dont_ask_again_button")


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
        return self.wait_for_card()

    def wait_for_card(self):
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

    def menu_items(self):
        """Opens the device menu and returns the test tags of its items, then closes it. Empty
        without a menu."""
        if not self.ui(res="device_menu").exists:
            return []
        self.ui(res="device_menu").click()
        self.ui(textMatches=MENU_ITEMS).wait.exists(TIMEOUT)
        items = [tag for tag in ("restore_preferred", "delete_preferred", "unlink_device") if self.ui(res=tag).exists]
        self.ad.adb.shell("input keyevent KEYCODE_BACK")
        self.ui(textMatches=MENU_ITEMS).wait.gone(TIMEOUT)
        return items

    def tap_menu_item(self, tag):
        asserts.assert_true(self.ui(res="device_menu").wait.click(TIMEOUT), "no device menu")
        asserts.assert_true(self.ui(res=tag).wait.click(TIMEOUT), f"no menu item {tag}")

    def open_log(self):
        asserts.assert_true(self.ui(res="app_menu").wait.click(TIMEOUT), "no app menu")
        asserts.assert_true(self.ui(res="open_log").wait.click(TIMEOUT), "no Log item")
        return LogScreen(self.ad).wait()

    def answer_permission(self, allow):
        """Answers the system's permission dialog."""
        if allow:
            asserts.assert_true(self.ui(res=PERMISSION_ALLOW).wait.click(TIMEOUT), "no permission dialog")
            return
        asserts.assert_true(self.ui(res=PERMISSION_ALLOW).wait.exists(TIMEOUT), "no permission dialog")
        button = next((res for res in PERMISSION_DENY if self.ui(res=res).exists), None)
        asserts.assert_is_not_none(button, "the permission dialog has no button to deny")
        self.ui(res=button).click()
        self.ui(res=PERMISSION_ALLOW).wait.gone(TIMEOUT)

    def shows_text(self, text):
        return self.ui(text=text).wait.exists(TIMEOUT)

    def tap_text(self, text):
        asserts.assert_true(self.ui(text=text).wait.click(TIMEOUT), f"nothing to tap with {text!r}")

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


class LogScreen:
    def __init__(self, ad):
        self.ad = ad
        self.ui = ad.ui

    def wait(self):
        asserts.assert_true(self.ui(res="share_log").wait.exists(TIMEOUT), "the log screen isn't shown")
        return self

    def entry(self, seq):
        """The entry whose first event has [seq], the trigger of an operation."""
        return self.ui(res=f"log_entry_{seq}")

    def entry_shows(self, seq, pattern):
        """Whether the entry shows a text matching the regular expression [pattern]."""
        return self.entry(seq).child(textMatches=pattern).wait.exists(TIMEOUT)

    def toggle(self, seq):
        asserts.assert_true(self.entry(seq).wait.click(TIMEOUT), f"no log entry {seq}")

    def share(self, full=False):
        """Shares the log from its dialog and closes the system's share sheet."""
        asserts.assert_true(self.ui(res="share_log").wait.click(TIMEOUT), "no share button")
        if full:
            asserts.assert_true(self.ui(text="Full").wait.click(TIMEOUT), "no Full option")
        asserts.assert_true(self.ui(text="Share").wait.click(TIMEOUT), "no Share button")
        self.ui(text="Share").wait.gone(TIMEOUT)
        # The share sheet
        self.ad.adb.shell("sleep 2; input keyevent KEYCODE_BACK")
