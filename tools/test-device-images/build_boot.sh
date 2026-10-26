#!/usr/bin/env bash
#######################################################################################
# Boot image build
#######################################################################################
#
# Builds new-boot-with-adb.img from a Magisk APK and a stock boot.img. All files are
# put in a new work folder build-<date>-<time> in the current directory, which is kept
# afterwards.
#
# Usage: build_boot.sh <Magisk.apk> <image.zip|boot.img>
#
# image.zip is the image-<device>-<build>.zip inside a factory image, boot.img is
# extracted from it.
#
# Required next to this script
#
# adb_patch.sh           Adds enable_adb.rc, busybox and magiskpolicy to new-boot.img
# gen_enable_adb_rc.py   Generates enable_adb.rc from adbkeys and enable_adb.rc.in
# enable_adb.rc.in       Template of the init script that magiskinit appends to init.rc
# adbkeys                Public ADB keys to authorize, one per line
#
#######################################################################################

set -e

[ $# -eq 2 ] || { echo "Usage: $0 <Magisk.apk> <image.zip|boot.img>" >&2; exit 1; }
for f in "$1" "$2"; do
    [ -f "$f" ] || { echo "! $f not found" >&2; exit 1; }
done
APK=$(realpath "$1")
IMAGE=$(realpath "$2")

SRC=$(dirname "$(realpath "$0")")
for f in adb_patch.sh gen_enable_adb_rc.py enable_adb.rc.in adbkeys; do
    [ -f "$SRC/$f" ] || { echo "! $SRC/$f not found" >&2; exit 1; }
done
for c in unzip python3; do
    command -v "$c" >/dev/null || { echo "! $c not found" >&2; exit 1; }
done

# Zip files start with PK, boot images with ANDROID!
IMAGE_ZIP=
if [ "$(head -c 2 "$IMAGE")" = PK ]; then
    unzip -l "$IMAGE" boot.img >/dev/null 2>&1 || { echo "! boot.img not found in $IMAGE" >&2; exit 1; }
    IMAGE_ZIP=1
elif [ "$(head -c 8 "$IMAGE")" != ANDROID! ]; then
    echo "! $IMAGE is neither a zip file nor a boot image" >&2; exit 1
fi

# magiskboot runs on this host, everything else on the phone
case "$(uname -m)" in
    x86_64)        HOST_ABI=x86_64 ;;
    aarch64|arm64) HOST_ABI=arm64-v8a ;;
    *)             echo "! Unsupported host architecture $(uname -m)" >&2; exit 1 ;;
esac

WORK="$PWD/build-$(date +%Y%m%d-%H%M%S)"
mkdir "$WORK"
echo "- Work folder: $WORK"

python3 "$SRC/gen_enable_adb_rc.py" "$SRC/adbkeys" "$SRC/enable_adb.rc.in" "$WORK/enable_adb.rc"

echo "- Extracting Magisk from $APK"
unzip -qjo "$APK" -d "$WORK" \
    lib/arm64-v8a/libinit-ld.so lib/arm64-v8a/libmagisk.so lib/arm64-v8a/libmagiskinit.so \
    lib/arm64-v8a/libbusybox.so lib/arm64-v8a/libmagiskpolicy.so "lib/$HOST_ABI/libmagiskboot.so" \
    assets/boot_patch.sh assets/util_functions.sh
for n in init-ld magisk magiskinit busybox magiskpolicy magiskboot; do
    mv "$WORK/lib$n.so" "$WORK/$n"
done

if [ -n "$IMAGE_ZIP" ]; then
    echo "- Extracting boot.img from $IMAGE"
    unzip -qjo "$IMAGE" boot.img -d "$WORK"
else
    cp "$IMAGE" "$WORK/boot.img"
fi
cp "$SRC/adb_patch.sh" "$WORK/"

# boot_patch.sh can't detect these on a PC; see README, "Detailed explanations".
# BOOTMODE=true makes Magisk print its messages to stdout instead of a recovery's fd
(cd "$WORK" && BOOTMODE=true PREINITDEVICE=metadata LEGACYSAR=true bash ./boot_patch.sh boot.img)
bash "$WORK/adb_patch.sh"

echo "- Test it from fastboot with: fastboot boot $WORK/new-boot-with-adb.img"
