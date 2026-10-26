# Log UI tests

Manual tests of the event log screen (`LogActivity`), the debug test label
receiver and the log export. They were run by hand over adb on 2026-10-26;
this file records the procedure and the results so they can be repeated.

## Environment

| | |
| --- | --- |
| Phone | Pixel 3a (`sargo`), serial `0000000028`, patched with [test-device-images](test-device-images/README.md) |
| Android | 9 (API 28), UTC, clock set to 2019-06-20 |
| Screen | 1080 × 2220 px, light theme, default font size |
| Build | debug, from commit `7c4faa8` (smoke tests), the commit that added this file (sample log tests) and the commit that renamed `VALIDATE_CODEC_CONFIG` (retest after review) |
| Bluetooth | no audio device connected |

The tap coordinates below are for this screen size. On another phone, find
an element's bounds with:

```bash
adb shell uiautomator dump /sdcard/ui.xml
```

```bash
adb exec-out cat /sdcard/ui.xml
```

| Element | Tap |
| --- | --- |
| Main screen overflow menu (⋮) | `1003 154` |
| Overflow menu item "Log" | `827 308` |
| Log screen: Share | `871 154` |
| Log screen: Summary / Detailed / Debug | `209 330` / `540 330` / `870 330` |
| Share dialog: Share button | `831 1418` |

Screenshots were taken with `adb exec-out screencap -p > page.png`, scrolling
between pages with `adb shell input swipe 540 1800 540 600 800`.

## Part 1: Smoke tests

Run against an empty log, right after installing the debug build.

### 1.1 Install and launch

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

```bash
adb shell am start -W -n com.tt.autobluetoothcodec/.MainActivity
```

**Expected:** the app starts, the top bar has an overflow menu (⋮).
**Result:** pass.

### 1.2 Test label receiver

```bash
adb shell am broadcast -n com.tt.autobluetoothcodec/.TestLabelReceiver --es label smoke_test
```

```bash
adb shell run-as com.tt.autobluetoothcodec cat files/logs/event_log.jsonl
```

**Expected:** the broadcast completes, the log file has one `TEST_LABEL` event
with the message `smoke_test`, and Logcat shows `I EventLog: TEST_LABEL: smoke_test`.
**Result:** pass.

### 1.3 Open the log screen

Tap the overflow menu, then "Log".

**Expected:** the log screen opens with Share and Clear in the top bar, the
detail level selection, a "Today" header and the label as "Test: smoke_test".
**Result:** pass.

### 1.4 Debug level and JSON

Tap "Debug", then the label's row.

**Expected:** Debug is selected and the row expands to the event's JSON.
**Result:** pass.

### 1.5 Share dialog

Tap Share.

**Expected:** a dialog with "Anonymised (recommended)", selected, and "Full",
each with a description, and Cancel and Share buttons.
**Result:** pass.

### 1.6 Export

Tap Share in the dialog, then go back without picking an app.

```bash
adb shell run-as com.tt.autobluetoothcodec ls -l cache/exports
```

**Expected:** the system share sheet opens, and `cache/exports` holds one
`auto-bluetooth-codec-log-<timestamp>.jsonl` file.
**Result:** pass, the share sheet listed Bluetooth, Gmail, Messages, Drive and
others.

## Part 2: Sample log tests

[sample_event_log.py](sample_event_log.py) generates a log with all 35 event
types (82 events). The events cover three days, two named devices, one device
without a name, one with a long name and one with a codec the app doesn't
know. The last operation is never resolved.

### 2.1 Load the sample log

Generate the sample against the phone's clock, so the day headers come out
right:

```bash
adb shell date +%s%z
```

```bash
python tools/sample_event_log.py --now-ms <seconds>000 --utc-offset <offset> -o sample.jsonl
```

```bash
adb push sample.jsonl /data/local/tmp/sample.jsonl
```

```bash
adb shell am force-stop com.tt.autobluetoothcodec
```

```bash
adb shell run-as com.tt.autobluetoothcodec sh -c "'mkdir -p files/logs && cat /data/local/tmp/sample.jsonl > files/logs/event_log.jsonl'"
```

Then launch the app and open the log screen. The log is only loaded once
something uses it, so the log screen has to be opened.

```bash
adb shell run-as com.tt.autobluetoothcodec wc -l files/logs/event_log.jsonl
```

**Expected:**
- All 82 lines parse. A line that doesn't parse makes the app rewrite the
  file without it, so the line count would drop.
- The open operation is resolved on load: the file has 83 lines and Logcat
  shows `EventLog: OPERATION_INTERRUPTED`.

**Result:** pass, every time the sample was loaded.

### 2.2 Summary level

Tap "Summary" and screenshot every page from the top.

**Expected:**
- Day headers "Today", "Yesterday" and a full date, newest first.
- One line per operation, "trigger → resolution (duration)", and standalone
  events like test labels and permission changes on their own.
- Detailed-level operations, like a device appearing before audio connects,
  are hidden.
- Failed outcomes in the error color.
- The device name above each entry, or its address if it has no name.
- An `ERROR` resolution shows its message under the summary line.
- Each time shares its baseline with the first line of its text, on every
  level.

**Result:** first run failed, see [issues](#issues-found-and-fixed). Passed
after the fixes.

### 2.3 Detailed level

Tap "Detailed" and screenshot every page.

**Expected:**
- Operations list their trigger, elements and resolution, without a summary
  line repeating them. The duration is on the resolution.
- Event messages, like "companion service" or exception texts, are shown in a
  smaller line below the event.
- The retry sequences show each attempt. Validations that don't match say
  "Codec not applied yet, active: …", ones that match "Codec validated: …".
  Running out of attempts says which codec stayed.
- Numbers stay on one line with their units, for example "48 kHz".
- Debug-level events are hidden.

**Result:** first run failed, see [issues](#issues-found-and-fixed). Passed
after the fixes.

### 2.4 Debug level

Tap "Debug", screenshot every page, then tap a "Setting codec" or "Codec not
applied yet" row.

**Expected:**
- The debug-level events appear: "Already in progress" with the uid of the
  pending operation, "Couldn't read the codec" and the presence observing
  changes.
- Tapping a row expands its JSON with a 2 space indent, scrolling sideways
  instead of wrapping. Codec events show both the `android` and the `own`
  model, for example `"type": "LDAC"`, `"sampleRate": 96000`,
  `"bitrate": "ADAPTIVE"`.

**Result:** first run failed, the JSON wrapped inside strings. Passed after
the fix.

### 2.5 Detail level is remembered

Leave the log screen at a level, force-stop the app and open the log screen
again.

**Expected:** it opens at the level last chosen.
**Result:** pass, it opened at Debug, the level chosen before, after both
reloads where that was checked.

### 2.6 Anonymised export of the sample

On the log screen, tap Share, then Share with "Anonymised" selected, and go
back from the share sheet. Read the exported file:

```bash
adb shell run-as com.tt.autobluetoothcodec ls cache/exports
```

```bash
adb shell run-as com.tt.autobluetoothcodec cat cache/exports/<file> > export.jsonl
```

Check it for addresses and names:

```bash
grep -cE '([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}' export.jsonl
```

```bash
grep -cE 'WH-1000XM4|Qudelix|Galaxy Buds|"name"' export.jsonl
```

```bash
grep -oE '"address":"[^"]*"' export.jsonl | sort | uniq -c
```

**Expected:** 83 lines, no addresses and no names. Each of the 4 devices has
one id like `device-KUHS`, and messages use the same ids and `[name removed]`.
**Result:** pass, for example the message
`Need BLUETOOTH_PRIVILEGED permission or a companion association for device device-VSHQ ([name removed])`.

## Issues found and fixed

The first run of the sample log tests found these, fixed in the commit that
added this file:

| Level | Issue | Fix |
| --- | --- | --- |
| All | Codecs read "LDAC, 96.0 kHz, 24 bits/sample", wrapped at the slash and left out the bitrate | "LDAC, 96 kHz, 24-bit, adaptive bitrate", with the AAC and Opus options too |
| All | Unknown codec read "unknown codec 5" | "unknown codec (type 5)" |
| Summary | Instant operations showed "(0.0 s)" | Durations under 0.1 s are left out |
| Summary | Interrupted operations showed the time until the app was opened again, "(52077.9 s)" | No duration for interrupted operations, long durations in minutes or hours |
| Summary | "Failed" didn't say why | `ERROR` shows its message |
| Detailed, Debug | The trigger and resolution were repeated below the summary line | Events are listed without a summary line, the duration is on the resolution |
| Detailed, Debug | Reads didn't say whether they matched the target | "…, not the target" when they don't |
| Detailed, Debug | Running out of attempts didn't say which codec stayed | "…, still LDAC, 48 kHz, 24-bit, 990 kbps" |
| Debug | JSON used a 4 space indent and wrapped inside strings | 2 space indent, scrolls sideways |

A review of the screenshots found these, fixed in the commit that renamed
`VALIDATE_CODEC_CONFIG`. Tests 2.1 to 2.3 were run again afterwards and passed:

| Level | Issue | Fix |
| --- | --- | --- |
| All | Times are smaller than the text and were top-aligned, so they didn't line up with the first line, hard to follow in runs of one-line events | Time and text share their first baseline |
| All | "Interrupted, the app was closed" claimed a cause that isn't known, the app may have crashed or been force-stopped | "Never finished, the app stopped running first" |
| Detailed, Debug | `GET_CODEC_CONFIG` didn't say what the read was for | Renamed to `VALIDATE_CODEC_CONFIG`, reads "Codec validated: …" or "Codec not applied yet, active: …" |
| Detailed | `CODEC_CHANGE_OBSERVED` didn't say who observed it, and "Codec changed to …" suggested the change happened then | Renamed to `ACTIVITY_OBSERVED_CODEC_CHANGE`, reads "App observed a different active codec: …" |
| Detailed, Debug | Lines broke between a number and its unit, "48 / kHz" | Non-breaking spaces before kHz and kbps |

## Not covered

- Clearing the log and its confirmation dialog.
- A full (not anonymised) export on the phone. It's covered by `LogExportTest`.
- Events from real Bluetooth connections. No audio device was connected; all
  events came from the sample log or the test label receiver.
- Other Android versions, especially API 31+ with `CodecCompanionService` and
  API 36 with its association requirements.
- Other screen sizes, landscape, larger font sizes and dark mode (the app has
  no dark theme yet).
- The log screen updating live while it's open.
