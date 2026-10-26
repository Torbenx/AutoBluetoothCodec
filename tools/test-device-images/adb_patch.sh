#!/usr/bin/env bash
#######################################################################################
# ADB-at-boot injection
#######################################################################################
#
# Adds enable_adb.rc, busybox and magiskpolicy to the ramdisk's overlay.d of new-boot.img
# and outputs it as new-boot-with-adb.img. build_boot.sh copies this script into its
# work folder and runs it there.
#
# Required in this directory
#
# magiskboot         The magisk tool to modify boot images
# new-boot.img       The magisk modified boot image
# enable_adb.rc      Init script that magiskinit appends to init.rc
# busybox            Magisk's busybox, magiskinit moves it into Magisk's tmpfs
# magiskpolicy       Magisk's sepolicy tool, magiskinit moves it into Magisk's tmpfs
#
#######################################################################################

set -e

cd "$(dirname "$(realpath "$0")")"
RC=enable_adb.rc
[ -f "$RC" ] || { echo "! $RC not found" >&2; exit 1; }
for f in busybox magiskpolicy; do
    [ -f "$f" ] || { echo "! $f not found" >&2; exit 1; }
done

echo "- Injecting $RC, busybox and magiskpolicy into overlay.d"
MAGISKBOOT="$PWD/magiskboot"
"$MAGISKBOOT" unpack new-boot.img
"$MAGISKBOOT" cpio ramdisk.cpio "add 0644 overlay.d/$RC $RC" "add 0755 overlay.d/sbin/busybox busybox" \
    "add 0755 overlay.d/sbin/magiskpolicy magiskpolicy"
"$MAGISKBOOT" repack new-boot.img new-boot-with-adb.img

echo "- Done: $PWD/new-boot-with-adb.img"
