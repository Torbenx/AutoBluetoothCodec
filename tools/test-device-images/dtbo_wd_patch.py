#!/usr/bin/env python3
#######################################################################################
# Watchdog-TE dtbo patch for phones with a dead or disconnected display
#######################################################################################
#
# Adds qcom,mdss-dsi-te-using-wd to every command-mode panel in dtbo.img, so the
# display pipeline uses the MDP's internal watchdog timer as vsync source instead of
# the panel's TE (tearing effect) pin, and outputs it as dtbo-wd.img in the current
# directory.
#
# Usage: dtbo_wd_patch.py <image.zip|dtbo.img>
#
# image.zip is the image-<device>-<build>.zip inside the factory image of the build
# installed on the phone, dtbo.img is read from it. Alternatively pass the stock
# dtbo.img itself.
#
# The stock image ends with an AVB signature and footer. They can't be valid for the
# patched content, so dtbo-wd.img leaves them out. An unlocked bootloader boots it
# anyway, just like the Magisk-patched boot image.
#
#######################################################################################

import os
import struct
import sys
import zipfile

IN = 'dtbo.img'
OUT = 'dtbo-wd.img'
PROP = 'qcom,mdss-dsi-te-using-wd'
FDT_MAGIC = 0xd00dfeed
DTBO_MAGIC = 0xd7b7ab1e


def parse(blob):
    (magic, total, o_struct, o_strings, o_rsv, version, last_comp, cpuid,
     sz_strings, _) = struct.unpack_from('>10I', blob)
    if magic != FDT_MAGIC:
        raise SystemExit('! Entry is not a device tree blob')
    strs = blob[o_strings:o_strings + sz_strings]
    rsv, p = [], o_rsv
    while True:
        a, s = struct.unpack_from('>QQ', blob, p); p += 16
        if a == 0 and s == 0:
            break
        rsv.append((a, s))
    p, stack, root = o_struct, [], None
    while True:
        tok = struct.unpack_from('>I', blob, p)[0]; p += 4
        if tok == 1:    # begin node
            e = blob.index(b'\0', p)
            node = [blob[p:e].decode(), [], []]   # name, props, children
            p = (e + 4) & ~3
            (stack[-1][2] if stack else []).append(node)
            root = root or node
            stack.append(node)
        elif tok == 2:  # end node
            stack.pop()
        elif tok == 3:  # property
            ln, no = struct.unpack_from('>II', blob, p); p += 8
            name = strs[no:strs.index(b'\0', no)].decode()
            stack[-1][1].append([name, blob[p:p + ln]])
            p = (p + ln + 3) & ~3
        elif tok == 9:  # end
            break
        elif tok != 4:  # 4 = nop
            raise ValueError('bad FDT token %d' % tok)
    return root, (version, last_comp, cpuid, rsv)


def build(root, meta):
    version, last_comp, cpuid, rsv = meta
    st, strtab, offs = bytearray(), bytearray(), {}

    def emit(node):
        name, props, children = node
        nm = name.encode() + b'\0'
        st.extend(struct.pack('>I', 1) + nm + b'\0' * (-len(nm) % 4))
        for k, v in props:
            if k not in offs:
                offs[k] = len(strtab); strtab.extend(k.encode() + b'\0')
            st.extend(struct.pack('>III', 3, len(v), offs[k]) + v + b'\0' * (-len(v) % 4))
        for c in children:
            emit(c)
        st.extend(struct.pack('>I', 2))
    emit(root)
    st.extend(struct.pack('>I', 9))
    rsvb = b''.join(struct.pack('>QQ', a, s) for a, s in rsv) + b'\0' * 16
    o_rsv = 40
    o_struct = o_rsv + len(rsvb)
    o_strings = o_struct + len(st)
    total = o_strings + len(strtab)
    hdr = struct.pack('>10I', FDT_MAGIC, total, o_struct, o_strings, o_rsv, version, last_comp,
                      cpuid, len(strtab), len(st))
    return hdr + rsvb + bytes(st) + bytes(strtab)


def walk(node, path=''):
    path = path + '/' + node[0] if node[0] else path
    yield path, node
    for c in node[2]:
        yield from walk(c, path)


def patch(root):
    """Add PROP to every command-mode panel node inside an overlay fragment."""
    panels = []
    for path, node in walk(root):
        if '/__overlay__/' not in path:
            continue
        props = dict(node[1])
        if props.get('qcom,mdss-dsi-panel-type') != b'dsi_cmd_mode\0':
            continue
        if PROP not in props:
            node[1].append([PROP, b''])
        panels.append(node[0])
    return panels


def main():
    if len(sys.argv) != 2:
        raise SystemExit('Usage: %s <image.zip|dtbo.img>' % sys.argv[0])
    path = sys.argv[1]
    if not os.path.isfile(path):
        raise SystemExit('! %s not found' % path)

    if zipfile.is_zipfile(path):
        name = '%s in %s' % (IN, path)
        with zipfile.ZipFile(path) as z:
            try:
                src = z.read(IN)
            except KeyError:
                raise SystemExit('! %s not found in %s' % (IN, path))
    else:
        name = path
        with open(path, 'rb') as f:
            src = f.read()

    print('- Patching panels in %s' % name)
    (magic, total, hdr_size, ent_size, count, ent_off, page_size,
     version) = struct.unpack_from('>8I', src)
    if magic != DTBO_MAGIC:
        raise SystemExit('! %s is not a dtbo image' % name)

    entries = [list(struct.unpack_from('>8I', src, ent_off + i * ent_size)) for i in range(count)]

    # Patch each blob once; mkdtimg may point several entries at the same blob
    blobs, panels, order = {}, {}, []
    for i, e in enumerate(entries):
        size, off, dt_id, rev = e[:4]
        if off not in blobs:
            root, meta = parse(src[off:off + size])
            panels[off] = patch(root)
            if not panels[off]:
                raise SystemExit('! Entry %d (id 0x%x) has no command-mode panel' % (i, dt_id))
            blobs[off] = build(root, meta)
            order.append(off)
        print('- entry %d (id 0x%x): %s'
              % (i, dt_id, ','.join(p.split(',', 1)[1] for p in panels[off])))

    # Same layout as mkdtimg: header, entry table, then the blobs back to back
    data_off = ent_off + count * ent_size
    new_off, pos = {}, data_off
    for off in order:
        new_off[off] = pos
        pos += len(blobs[off])

    out = bytearray(src[:ent_off])
    struct.pack_into('>I', out, 4, pos)   # total_size
    for e in entries:
        e[0], e[1] = len(blobs[e[1]]), new_off[e[1]]
        out += struct.pack('>8I', *e)
    for off in order:
        out += blobs[off]
    # Keep the stock image size, zero-filling where its AVB signature and footer were
    out += b'\0' * max(0, len(src) - len(out))
    with open(OUT, 'wb') as f:
        f.write(out)

    print('- Done: %s' % os.path.join(os.getcwd(), OUT))


if __name__ == '__main__':
    main()
