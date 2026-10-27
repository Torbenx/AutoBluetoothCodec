# Codec applier tests

Manual tests of applying codecs through the process-wide `CodecApplier`: from
the main screen, from `CodecCompanionService` when the device reconnects, and
how the main screen logs codec changes it didn't make. They were run by hand
over adb on 2026-10-27; this file records the procedure and the results so
they can be repeated.

## Environment

| | |
| --- | --- |
| Phone | Galaxy S24 (`SM-S921B`), serial `R0000000036` |
| Android | 16 (API 36), German system language |
| Screen | portrait, default font size |
| Build | debug, the working tree of the commit that made `CodecApplier` process-wide |
| Bluetooth | Qudelix-5K (`0A:1B:2C:3D:4E:5F`), connected over A2DP, linked to the app |
| Preferred codec | LDAC, 44.1 kHz, 24-bit, 909 kbps |
| Remote control | the Qudelix-5K on USB, driven by [qudelix5k.py](qudelix5k.py) |

The commands below use plain `adb`; with several phones attached, set
`ANDROID_SERIAL=R0000000036` first. In Git Bash, also set `MSYS_NO_PATHCONV=1`,
otherwise paths like `/sdcard/ui.xml` are rewritten to Windows paths.

The tap coordinates below are for this phone. On another phone, find an
element's bounds with:

```bash
adb shell uiautomator dump /sdcard/ui.xml
```

```bash
adb exec-out cat /sdcard/ui.xml
```

| Element | Tap |
| --- | --- |
| Device card, expands and collapses it | `158 369` |
| Device menu (⋮) | `964 369` |
| Device menu item "Restore Preferred Codec" | `846 500` |
| Codec chips SBC, AAC, LDAC, aptX HD (card expanded) | `122 524`, `267 524`, `420 524`, `745 524` |
| Sample rate chips 44100, 48000, 96000 Hz | `154 674`, `362 674`, `778 674` |
| Bits per sample chips 16, 24, 32 | `113 824`, `238 824`, `363 824` |
| LDAC bitrate chips Adaptive, 909 kbps | `150 974`, `760 974` |

Each test starts with a label in the event log:

```bash
adb shell am broadcast -n com.tt.autobluetoothcodec/.TestLabelReceiver --es label "'T1 restore preferred'"
```

The results were read from the log file. The event numbers below are its
`seq` values:

```bash
adb shell run-as com.tt.autobluetoothcodec cat files/logs/event_log.jsonl
```

## Part 1: Changes from the main screen

### 1.1 Choose a codec

Expand the card and tap AAC, then LDAC, then 96000 Hz, then SBC, waiting a
few seconds between the taps.

**Expected:** each choice is a `USER_SET_CODEC` operation that ends with
`CODEC_VERIFIED`, and no `ACTIVITY_OBSERVED_EXTERNAL_CODEC_CHANGE` follows it.
**Result:** pass after a fix, each choice verified on attempt 1 (events
165–183). On the first run, AAC ended with `MAX_ATTEMPTS` after 5 attempts,
see the issues below. The aptX HD chip didn't react. The Qudelix doesn't offer
aptX HD to this phone, so its chip is disabled.

### 1.2 Restore the preferred codec

With AAC or SBC active, open the device menu and tap "Restore Preferred Codec".

**Expected:** a `USER_RESTORE_PREFERRED` operation with the preferred codec as
config, ending with `CODEC_VERIFIED` and LDAC, 44.1 kHz, 24-bit, 909 kbps.
**Result:** pass, verified on attempt 1 from AAC (events 160–163) and from SBC
(events 185–188).

### 1.3 Restore over several attempts

The original problem: after a reconnect the Qudelix starts at LDAC, 96 kHz,
32-bit with adaptive bitrate, and the stack takes the sample rate and the
bitrate in separate steps. Set that state with the chips 96000 Hz, 32 and
Adaptive, then restore the preferred codec.

**Expected:** `SET_CODEC_CONFIG` is sent again until the codec matches, and
the intermediate codec isn't logged as an external change.
**Result:** pass (events 204–210). Attempt 1 left LDAC, 44.1 kHz, 24-bit with
adaptive bitrate, attempt 2 verified the preferred codec. No external change
was logged.

### 1.4 Restore twice

Set the state of 1.3, then open the menu and restore twice in a row, quickly
enough that the first restore is still running:

```bash
adb shell "input tap 964 369; sleep 0.4; input tap 846 500; sleep 0.3; input tap 964 369; sleep 0.4; input tap 846 500"
```

**Expected:** the second restore asks for the same codec, so it resolves
`ALREADY_PENDING` with the first operation in its message. The first one
continues with its attempt count.
**Result:** pass (events 224–232). The second restore arrived between attempts
1 and 2 of the first, which verified on attempt 2.

### 1.5 Choose a codec while restoring

Set the state of 1.3, restore, and tap 48000 Hz while the restore is running:

```bash
adb shell "input tap 964 369; sleep 0.4; input tap 846 500; sleep 0.3; input tap 362 674"
```

The run was repeated with `sleep 0.7` before the chip, so the chip arrives
after the restore's first attempt.

**Expected:** the chip asks for a different codec, so the restore resolves
`SUPERSEDED` with the new operation in its message. The new operation starts
at attempt 1. Nothing is logged as an external change.
**Result:** pass after a fix (events 268–275 and 289–300). In the second run
the restore was superseded at attempt 2, and the new operation verified on
its attempt 2. On the first run, an `ACTIVITY_OBSERVED_EXTERNAL_CODEC_CHANGE`
was logged between the new trigger and `SUPERSEDED` (event 250), see the
issues below.

The chip builds its config from the codec the card shows, which was still
96 kHz, 32-bit, adaptive. So the new target was LDAC, 48 kHz, 32-bit,
adaptive, not the restored codec at 48 kHz. That's how the chips worked
before as well.

### 1.6 Rotate while restoring

Set the state of 1.3, lock the rotation, restore, and rotate to landscape right
after:

```bash
adb shell settings put system accelerometer_rotation 0
```

```bash
adb shell "input tap 964 369; sleep 0.4; input tap 846 500; sleep 0.1; settings put system user_rotation 1"
```

Afterwards set `user_rotation` back to 0 and `accelerometer_rotation` back to 1.

**Expected:** the activity is recreated, the restore continues and verifies.
No crash.
**Result:** pass (events 328–334). Logcat showed the rotation at 16:21:11.86,
after attempt 1 and before the verification at 16:21:12.72.

## Part 2: Changes outside the main screen

### 2.1 Reconnect while the app is open

With the main screen open, turn the Qudelix's Bluetooth off and on again:

```bash
python tools/qudelix5k.py cycle
```

**Expected:** `CodecCompanionService` applies the preferred codec on
`A2DP_CONNECTED`, and the main screen doesn't log the codecs the device passes
through as external changes.
**Result:** pass (events 301–312). `DEVICE_APPEARED` resolved `NOT_CONNECTED`,
then `A2DP_CONNECTED` went from LDAC, 96 kHz, 32-bit through 44.1 kHz, 24-bit
to the preferred codec in 3 attempts. No external change was logged.

### 2.2 Change the codec in the developer options

Open the developer options, set "Bluetooth-Audio: Abtastrate" (sample rate) to
48 kHz, and return to the app:

```bash
adb shell am start -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS
```

**Expected:** the main screen logs
`ACTIVITY_OBSERVED_EXTERNAL_CODEC_CHANGE` with LDAC, 48 kHz.
**Result:** pass (event 314).

### 2.3 Turn Bluetooth off and on

With the main screen open:

```bash
adb shell cmd bluetooth_manager disable
```

```bash
adb shell cmd bluetooth_manager enable
```

The Qudelix didn't reconnect by itself within 20 s, so it was cycled with
`qudelix5k.py cycle`. Logcat of the app's process shows the proxies:

```bash
adb logcat -d -v time --pid=<pid> | grep "A2DP connected\|A2DP disconnected"
```

**Expected:** the applier closes its proxy when Bluetooth turns off and opens
exactly one new one with its next request. Only the main screen's proxy is
reconnected by the framework.
**Result:** pass. When Bluetooth turned off, `A2DP disconnected` appeared
three times: for the main screen's proxy, for the applier's, and again when
the applier closed its proxy. When it turned on, one `A2DP connected` appeared
for the main screen's proxy. A second one appeared at 16:22:43 when
`DEVICE_APPEARED` started a request. The reconnect then applied the
preferred codec in 2 attempts (events 336–344).

### 2.4 Unit tests

```bash
./gradlew testDebugUnitTest
```

**Result:** pass. No tests were added for this change.

## Issues found and fixed

Both were fixed in the commit that made `CodecApplier` process-wide, and the
tests were run again afterwards:

| Test | Issue | Fix |
| --- | --- | --- |
| 1.1 | Choosing AAC never verified: the chip leaves the variable bitrate at the system default, the stack reports the one it chose (very high), so the exact comparison failed all 5 attempts | `CodecConfig.isFulfilledBy`: options left at the system default accept any value. The applier verifies with it, and the log screen uses it for "Codec change incomplete" |
| 1.5 | Superseding removed the old request before adding the new one. The main screen collects `pending` on the main thread, so in between it saw the device idle and logged the intermediate codec as an external change | The requests are swapped before `pending` is published, and operations are resolved before `pending` changes |

Installing the build left 8 operations of the old log "Interrupted" (events
138–145). Their `CODEC_SET_SENT` resolutions no longer parse, which was
expected when the event type was removed.

## Not covered

- Linking a device before applying: unlink it, then choose a codec, which
  should log `ASSOCIATION_REQUESTED` before `SET_CODEC_CONFIG`. It wasn't run,
  because linking the device again means accepting the system's companion
  device dialog.
- Linking a device after Bluetooth refused a codec change
  (`onSecurityException`). On API 36 the association check before applying
  always runs first, so the refusal doesn't happen.
- `A2dpConnectReceiver` and its deadline. It only runs on API 30 and lower,
  and the Pixel 3a phones have no audio device.
- The receiver or the service being superseded by a choice on the main screen.
- Toasts for failed changes.
- A request while Bluetooth is off (`PROFILE_UNAVAILABLE`).
- Other phones, codecs other than SBC, AAC and LDAC, and other Android
  versions.
