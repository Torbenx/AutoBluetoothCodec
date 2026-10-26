#!/usr/bin/env python3
#######################################################################################
# enable_adb.rc generation
#######################################################################################
#
# Replaces the @ADB_KEYS@ line of the template with one exec line per key in the keys
# file, which together write /data/misc/adb/adb_keys at boot.
#
# Usage: gen_enable_adb_rc.py <adbkeys> <enable_adb.rc.in> <enable_adb.rc>
#
# The keys file holds one public key per line, as found in ~/.android/adbkey.pub:
# the base64 key, optionally followed by a space and a user@host comment. Empty lines
# and lines starting with # are ignored.
#
# Each key ends up inside quotes that both init and sh parse, so keys are checked to be
# Android RSA-2048 public keys and comments may only contain letters, digits and ._@-
#
#######################################################################################

import base64
import binascii
import re
import struct
import sys

MARKER = '@ADB_KEYS@'
KEY_SIZE = 524          # Android RSAPublicKey: len, n0inv, n[64], rr[64], exponent
MODULUS_WORDS = 64      # 2048-bit modulus as 32-bit words
COMMENT = re.compile(r'[A-Za-z0-9._@-]+')
EXEC = 'exec u:r:magisk:s0 root root -- /system/bin/sh -c "echo \'%s\' %s /data/misc/adb/adb_keys"'


def read_keys(path):
    keys, seen = [], set()
    with open(path, encoding='utf-8') as f:
        lines = f.read().splitlines()
    for no, line in enumerate(lines, 1):
        line = line.strip()
        if not line or line.startswith('#'):
            continue

        def fail(msg):
            raise SystemExit('! %s:%d: %s' % (path, no, msg))

        if line.startswith('-----BEGIN'):
            fail('this is a private key; use the content of adbkey.pub instead')
        key, _, comment = line.partition(' ')
        try:
            blob = base64.b64decode(key, validate=True)
        except binascii.Error:
            fail('key is not valid base64')
        if len(blob) != KEY_SIZE or struct.unpack_from('<I', blob)[0] != MODULUS_WORDS:
            fail('not an ADB public key (expected %d bytes for RSA-2048, got %d)'
                 % (KEY_SIZE, len(blob)))
        if comment and not COMMENT.fullmatch(comment):
            fail('comment %r may only contain letters, digits and ._@-' % comment)
        if key in seen:
            fail('duplicate key')
        seen.add(key)
        keys.append((key + ' ' + comment).rstrip())
    if not keys:
        raise SystemExit('! %s contains no keys' % path)
    return keys


def main():
    if len(sys.argv) != 4:
        raise SystemExit('Usage: %s <adbkeys> <enable_adb.rc.in> <enable_adb.rc>' % sys.argv[0])
    keys_path, template_path, out_path = sys.argv[1:]

    keys = read_keys(keys_path)
    with open(template_path, encoding='utf-8') as f:
        lines = f.read().split('\n')
    markers = [i for i, l in enumerate(lines) if l.strip() == MARKER]
    if len(markers) != 1:
        raise SystemExit('! %s must contain exactly one %s line' % (template_path, MARKER))

    # The first key creates the file, the others are appended
    i = markers[0]
    indent = lines[i][:len(lines[i]) - len(lines[i].lstrip())]
    lines[i:i + 1] = [indent + EXEC % (k, '>' if n == 0 else '>>') for n, k in enumerate(keys)]
    with open(out_path, 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines))

    print('- Generated %s with %d ADB key(s) from %s' % (out_path, len(keys), keys_path))


if __name__ == '__main__':
    main()
