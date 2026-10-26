# Pixel 3a test device boot image

Patches a Pixel 3a (`sargo`) boot image so the phone can be used as a headless
test device, for example one with a dead display. The patched image:

- roots the device with Magisk and grants `su` to `adb shell`, without the
  Magisk app
- pre-authorizes your computers' ADB keys and enables ADB on every boot, so no
  on-screen authorization prompt is needed
- disables the swipe lock screen
- keeps the screen on while plugged in
- skips the setup wizard after a wipe

Tested with builds `PQ3B.190801.002` (Android 9) and `SP2A.220505.008`
(Android 12) and Magisk v31.1.

## Files

| File                   | Description                                                        |
| ---------------------- | ------------------------------------------------------------------ |
| `build_boot.sh`        | Builds the patched boot image from the Magisk APK and the image zip; see [step 3](#3-build-and-flash) |
| `adbkeys`              | Public ADB keys of your computers. **You must add your keys to it.** |
| `enable_adb.rc.in`     | Template of the init script run at boot                            |
| `gen_enable_adb_rc.py` | Generates `enable_adb.rc` from `adbkeys` and `enable_adb.rc.in`    |
| `adb_patch.sh`         | Adds `enable_adb.rc`, Magisk's `busybox` and `magiskpolicy` to a Magisk-patched `new-boot.img` |
| `Magisk-*.apk`         | Input: Magisk release, from <https://github.com/topjohnwu/Magisk/releases> |
| `image-*.zip`          | Input: partition images of the build installed on the phone, from its factory image; see [step 1](#1-get-the-stock-image-zip) |
| `dtbo_wd_patch.py`     | Optional: fixes the slow UI of a phone with a dead display; see [step 4](#4-optional-fix-the-slow-ui-with-a-dead-display) |

`boot_patch.sh`, `util_functions.sh` and the Magisk binaries are extracted from
the APK in step 3. They stay unmodified, so updating Magisk only means
replacing the APK.

## Requirements

- A Linux x86_64 or ARM64 host with `bash`, `unzip`, `python3` and the Android
  platform tools (`adb`, `fastboot`).
- A Pixel 3a with an **unlocked bootloader**. Unlocking requires "OEM unlocking"
  in Developer options and a confirmation on the device, so do it while you
  still have a working screen. Unlocking wipes the device. Verizon units cannot
  be unlocked.

## 1. Get the stock image zip

The boot image must come from the **same build** that is installed on the
phone. Otherwise the kernel won't match the system and vendor partitions.

1. Download the desired factory image from <https://developers.google.com/android/images>.
   For the Pixel 3a use the `sargo` images.
2. Extract it. The factory zip contains the image zip, that holds
   the partition images:

   ```bash
   unzip sargo-pq3b.190801.002-factory-56817dc2.zip
   ```

   The image zip is then `sargo-pq3b.190801.002/image-sargo-pq3b.190801.002.zip`.
   `build_boot.sh` and `dtbo_wd_patch.py` read `boot.img` and `dtbo.img` from it
   directly. Both also accept the extracted `.img` file instead.

To get a known starting point, flash the whole factory image first with
`flash-all.sh` from the extracted folder. This **wipes the device**; remove `-w`
from the script to keep data. To check which build is installed:
`adb shell getprop ro.build.id`.

## 2. Add your ADB keys to adbkeys

`adbkeys` lists the computers whose ADB keys are authorized on the phone, one
key per line. Add your computer's public ADB key:

| Host           | Key file                             |
| -------------- | ------------------------------------ |
| Linux / macOS  | `~/.android/adbkey.pub`              |
| Windows        | `%USERPROFILE%\.android\adbkey.pub`  |

The key file is created the first time `adb` runs. If it is missing, run
`adb start-server` once.

Append the whole content of the file as a new line. It is a single line,
ending in `user@host`:

```
QAAAAM...= user@host
```

Empty lines and lines starting with `#` are ignored. Remove the keys of
computers that should not have access.

`gen_enable_adb_rc.py` checks each line in step 3 and stops with the line number
if a line is not a public ADB key (2048-bit RSA, base64), or if a key is listed
twice. The `user@host` comment is optional, but may only contain letters, digits
and `._@-`, because the key is placed inside quotes in `enable_adb.rc`.

Why this is needed: Pixel factory images are `user` builds. On a user build,
adbd always requires an authorized key and ignores `ro.adb.secure`. With a dead
screen you can't accept the authorization prompt, so the key has to be in place
before adbd starts.

The file is rewritten on every boot, so keys accepted later through the
on-device prompt disappear at the next reboot. Add every computer here instead.

## 3. Build and flash

**Build.** Run `build_boot.sh` with the Magisk APK and the image zip:

```bash
./build_boot.sh Magisk-v31.1.apk sargo-pq3b.190801.002/image-sargo-pq3b.190801.002.zip
```

Instead of the image zip, you can also pass a stock `boot.img`.

The script creates a new work folder `build-<date>-<time>` in the current
folder and does everything in there:

1. Generates `enable_adb.rc` from `enable_adb.rc.in` and the keys in `adbkeys`,
   checking each key (see [step 2](#2-add-your-adb-keys-to-adbkeys)).
2. Extracts Magisk from the APK. The binaries that run on the phone come from
   `arm64-v8a`; `magiskboot`, which runs on the PC, comes from `x86_64` or
   `arm64-v8a` to match the PC.
3. Extracts `boot.img` from the image zip, or copies the given `boot.img`, and
   copies `adb_patch.sh` into the folder.
4. Patches `boot.img` with Magisk's `boot_patch.sh`, which writes `new-boot.img`.
   `LEGACYSAR=true` is required; see [Pixel 3a specifics](#legacysartrue).
   `PREINITDEVICE=metadata` is recommended; see
   [PREINITDEVICE=metadata](#preinitdevicemetadata).
5. Runs `adb_patch.sh`, which adds `enable_adb.rc`, `busybox` and `magiskpolicy`
   and writes `new-boot-with-adb.img`.

The work folder is kept, so you can inspect every intermediate file. Each run
creates a new one; delete old ones yourself. At the end, the script prints the
path of the image.

**Enter fastboot.** If ADB already works:

```bash
adb reboot bootloader
```

Otherwise, power the phone off and hold **Power + Volume Down**.

**Test without flashing.** This boots the image once. A normal reboot returns to
the installed image.

```bash
fastboot boot "<build-folder>/new-boot-with-adb.img"
```

**Flash.** Once the test works, go back to fastboot and flash it. The Pixel 3a
has A/B slots; this flashes the active slot.

```bash
fastboot flash boot "<build-folder>/new-boot-with-adb.img"
```

```bash
fastboot reboot
```

After booting, check the result:

```bash
adb devices -l
```

```bash
adb shell 'locksettings get-disabled; settings get global stay_on_while_plugged_in'
```

```bash
adb shell su -c id
```

The phone should be listed as `device` (not `unauthorized`), the second
command should print `true` and `7`, and the third should print `uid=0(root)`.

To undo everything, flash the stock image, of which the work folder keeps a
copy:

```bash
fastboot flash boot "<build-folder>/boot.img"
```

## 4. Optional: fix the slow UI with a dead display

**Issue.** The Pixel 3a's panel runs in DSI command mode. The display hardware
only sends a frame after the panel signals on its TE (tearing effect) pin that
it's ready. A dead or disconnected panel never signals, so every frame waits
for a timeout. Once the screen has been off, the kernel also reports the panel
as dead, and the display HAL power-cycles it about every 0.5 s. The UI runs at
about 2 fps, including over scrcpy. To check for it:

```bash
adb logcat -d | grep -E "HandlePanelDead|resetting the panel"
```

**Applicability.** Only for phones whose display is dead or disconnected. On a
working display, frames are no longer synchronized with the panel, which can
cause tearing.

**Fix.** `dtbo_wd_patch.py` adds `qcom,mdss-dsi-te-using-wd` to every
command-mode panel in `dtbo.img`. The kernel then uses an internal watchdog
timer as vsync source instead of the TE pin. On a phone with a dead display,
the UI runs at about 60 fps again. The fix is independent of the boot image and
needs `python3` on the PC.

Run the script with the image zip of the installed build from
[step 1](#1-get-the-stock-image-zip). This writes `dtbo-wd.img` in the current
folder. Instead of the image zip, you can also pass a stock `dtbo.img`.

```bash
python3 ./dtbo_wd_patch.py sargo-pq3b.190801.002/image-sargo-pq3b.190801.002.zip
```

There is no way to test a `dtbo` without flashing it. From fastboot, flash it
to the active slot and reboot:

```bash
fastboot flash dtbo dtbo-wd.img
```

```bash
fastboot reboot
```

To undo it, extract the stock image from the image zip and flash it:

```bash
unzip -jo sargo-pq3b.190801.002/image-sargo-pq3b.190801.002.zip dtbo.img
```

```bash
fastboot flash dtbo dtbo.img
```

Flashing a whole factory image also restores the stock `dtbo`, so run the
script again afterwards.

The stock `dtbo.img` ends with an AVB signature, which can't be valid for the
patched content, so `dtbo-wd.img` leaves it out. An unlocked bootloader boots
it anyway, like the Magisk-patched boot image.

## Detailed explanations

### LEGACYSAR=true

On Android 9, the Pixel 3a is a *legacy system-as-root* device. On a normal
boot the bootloader passes `skip_initramfs` to the kernel, which then mounts
`/system` as root and **ignores the boot image's ramdisk**. Magisk works around
this by renaming the string in the kernel to `want_initramfs`.

`boot_patch.sh` can only detect legacy SAR when it runs on the device. On a PC
it assumes `false`, so the kernel stays unpatched and neither Magisk nor
`enable_adb.rc` ever runs. That's why `build_boot.sh` sets `LEGACYSAR=true`.
On a kernel without `skip_initramfs` this setting does nothing, so it is safe
to keep for later Android versions.

To check a built image, unpack it in a new subfolder of the work folder, because
`unpack` overwrites `kernel` and `ramdisk.cpio`:

```bash
mkdir "<build-folder>/check" && cd "<build-folder>/check"
```

```bash
../magiskboot unpack ../new-boot-with-adb.img && grep -ac want_initramfs kernel
```

This must print `1`.

### PREINITDEVICE=metadata

Magisk keeps files it needs before `/data` is decrypted, such as the
`sepolicy.rule` files of modules, on a separate "preinit" partition. Like
legacy SAR, `boot_patch.sh` only detects this partition when it runs on the
device, so on a PC it is left empty. Magisk then logs
`mount: preinit dir not found` at boot, and the SELinux rules of modules are
not loaded.

On the Pixel 3a, `adb shell su -c 'magisk --preinit-device'` prints `metadata`
on both Android 9 and 12. With `PREINITDEVICE=metadata`, `magisk.log` shows
`Found preinit dir: /metadata/magisk` (Android 9) or
`/metadata/watchdog/magisk` (Android 12) instead.

### What enable_adb.rc does

magiskinit appends every `*.rc` file in the ramdisk's `overlay.d` to `init.rc`.
The file uses init syntax (`on <trigger>` blocks), not shell syntax. Commands
that need more than init's built-ins run through `exec` in Magisk's SELinux
domain (`u:r:magisk:s0`), as root. magiskinit inserts these files before its
own `init.rc` lines, so the `post-fs-data` block runs before Magisk's own
post-fs-data step. magiskinit also replaces `${MAGISKTMP}` with the folder
holding the Magisk binaries, which differs between Android versions (see
[Magisk binaries in PATH](#magisk-binaries-in-path)).

| Trigger                 | Action                                                                        |
| ----------------------- | ----------------------------------------------------------------------------- |
| `post-fs-data`          | Creates `/data/adb` and `/data/misc/adb` with init's `mkdir`, so `/data/adb` gets its encryption policy on the first boot after a wipe. See [First boot after a wipe](#first-boot-after-a-wipe) |
| `post-fs-data`          | Copies `busybox` to `/data/adb/magisk`. See [Magisk binaries in PATH](#magisk-binaries-in-path) |
| `post-fs-data`          | Writes `/data/misc/adb/adb_keys` and fixes its owner, mode and SELinux label; sets `persist.sys.usb.config` |
| `sys.boot_completed=1`  | Enables ADB in Settings, sets the USB mode and restarts adbd                  |
| `sys.boot_completed=1`  | `locksettings set-disabled true`: no swipe lock screen. Only works while no PIN, pattern or password is set |
| `sys.boot_completed=1`  | `stay_on_while_plugged_in 7`: screen stays on with USB, AC or wireless power  |
| `sys.boot_completed=1`  | Marks setup as complete and disables `com.google.android.setupwizard`         |
| `sys.boot_completed=1`  | Adds an "allow forever" `su` rule for the shell UID (2000) to Magisk's database. See [Root](#root-without-the-magisk-app) |

To change these actions, edit `enable_adb.rc.in`; `build_boot.sh` generates
`enable_adb.rc` from it on every build. The `@ADB_KEYS@` line is replaced with
one line per key in `adbkeys` and must stay. Comments are allowed, but only on
their own lines starting with `#`.

### First boot after a wipe

On a device with file-based encryption, init gives each top-level folder in
`/data` an encryption policy when it creates the folder with `mkdir`. Android
creates `/data/adb` in the `post-fs-data` block of `init.usb.rc`, which runs
after the overlay's block. After a wipe, `/data/adb` doesn't exist yet when
`enable_adb.rc` copies busybox into it. If the shell created it, the folder
would have no policy. `init.usb.rc`'s `mkdir` then can't set one on a folder
that isn't empty, and init reboots into recovery with
`set_policy_failed:/data/adb`. That's why the phone wouldn't boot after a wipe
before this fix, but booted if the stock image had already created the folder.

`enable_adb.rc` therefore creates `/data/adb` itself with init's `mkdir`, before
anything writes to it. Its `encryption=` option is left out because Android 9
doesn't know it; Android 12 then uses `encryption=Require` and logs a warning.
It also creates `/data/misc/adb`, so the ADB keys are written on the first boot
too.

### Magisk binaries in PATH

Magisk keeps its binaries on a tmpfs. Where it is depends on the Android
version:

| Android | Folder           | In `PATH` | How `magisk` and `su` get into `PATH`                     |
| ------- | ---------------- | --------- | --------------------------------------------------------- |
| 9       | `/sbin`          | yes       | Already there                                             |
| 11+     | `/debug_ramdisk` | no        | Magisk mounts them into a `PATH` folder, e.g. `/product/bin` |

Magisk only adds them to `PATH` when its post-fs-data step runs to the end.
That step stops early with "Magisk environment incomplete" if
`/data/adb/magisk/busybox` is missing. Normally the Magisk app sets up this
folder. Without the app, `adb_patch.sh` adds Magisk's busybox to
`overlay.d/sbin`, which magiskinit moves into Magisk's tmpfs, and
`enable_adb.rc` copies it to `/data/adb/magisk` on every boot. Without this,
`magisk` and `su` are not found in `adb shell` on Android 12.

`magiskpolicy` (and its `supolicy` link) is added to `PATH` together with
`magisk` and `su`. `boot_patch.sh` doesn't put it in the ramdisk, and Magisk
otherwise copies it from `/data/adb/magisk` at boot. `adb_patch.sh` therefore
adds it to `overlay.d/sbin` too, so it is in Magisk's tmpfs from the start.
Without it, `magisk.log` shows `bind_mount '/debug_ramdisk/magiskpolicy' ...
No such file or directory` on Android 11+, and `supolicy` is a broken link on
Android 9.

Because Magisk's folder isn't in `PATH` on Android 11+, `enable_adb.rc` calls
`${MAGISKTMP}/magisk` instead of relying on `PATH`.

### Root without the Magisk app

`su` and the Magisk daemon (`magiskd`) are part of the patched boot image. The
Magisk app only shows the allow/deny prompt and saves the answer. Before
asking the app, `magiskd` checks its policy table in `/data/adb/magisk.db`. A
saved rule is applied right away. Without a rule and without the app, the
request is denied. UID 0 is always allowed, which is why the `exec` lines in
`enable_adb.rc` work.

`enable_adb.rc` writes a rule for `adb shell` (UID 2000) on every boot, using
`magisk --sqlite`. `policy` 2 means allow, `until` 0 means it never expires.
To grant root to an app as well, copy the line and replace `2000` with the
app's UID, from `adb shell pm list packages -U <package>`.

If no Magisk app is found at boot, Magisk may install a small stub app by
itself. You can ignore it; saved rules are checked before the app is asked.
To get on-screen prompts instead, install the full app:
`adb install Magisk-v31.1.apk`.

`adb root` doesn't work. On user builds adbd can't run as root.

## Notes

- **Don't set a lock screen PIN.** The Pixel 3a encrypts each user's storage.
  With a PIN set, app data stays locked after every reboot until someone
  unlocks the phone, and most apps won't start.
- **Setup wizard after a wipe.** The wizard appears briefly on the first boot,
  then closes by itself once boot completes, and the launcher takes over.
  Nothing on the phone asks for a Google account; add one under Settings →
  Accounts if needed. To turn the wizard back on:
  `adb shell pm enable com.google.android.setupwizard`.

## Troubleshooting

| Symptom                              | Likely cause                                                                         |
| ------------------------------------ | ------------------------------------------------------------------------------------ |
| No ADB device at all after boot      | Kernel not patched: `LEGACYSAR=true` missing; check for `want_initramfs` as above     |
| `su` not found on Android 11+        | `busybox` missing from the image, so Magisk's post-fs-data stopped early. See [Magisk binaries in PATH](#magisk-binaries-in-path). Check with `adb shell /debug_ramdisk/magisk -c` that Magisk runs at all |
| `su` says `Permission denied`        | The `su` rule wasn't written, for example because a newer Magisk changed the database columns. Look for SQL errors in `adb logcat -d -s Magisk` |
| ADB device shows `unauthorized`      | Key missing or wrong in `adbkeys`, or Windows adb uses another key folder (`ANDROID_USER_HOME` / `ANDROID_SDK_HOME`) |
| Boot loop                            | `boot.img` doesn't match the installed build; flash the stock `boot.img` back         |
