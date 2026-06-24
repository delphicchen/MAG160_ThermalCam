#!/usr/bin/env python3
"""Recursive-descent tracer: starting from given functions, follow BL targets
(bounded depth) and report any function that calls libusb_bulk_transfer /
libusb_submit_transfer / libusb_control_transfer, dumping the immediate
constants and pc-relative literals loaded just before those calls (to recover
endpoint numbers and command words)."""
import struct, re, subprocess, sys
from capstone import Cs, CS_ARCH_ARM, CS_MODE_THUMB, CS_MODE_LITTLE_ENDIAN

LIB = "/tmp/apk_x/lib/armeabi-v7a/libcoresdk.so"
DATA = open(LIB, "rb").read()
e_shoff = struct.unpack_from("<I", DATA, 0x20)[0]
e_shentsize = struct.unpack_from("<H", DATA, 0x2e)[0]
e_shnum = struct.unpack_from("<H", DATA, 0x30)[0]
secs = []
for i in range(e_shnum):
    o = e_shoff + i * e_shentsize
    a = struct.unpack_from("<I", DATA, o + 0xc)[0]
    off = struct.unpack_from("<I", DATA, o + 0x10)[0]
    sz = struct.unpack_from("<I", DATA, o + 0x14)[0]
    if a:
        secs.append((a, off, sz))
def v2o(v):
    for a, o, s in secs:
        if a <= v < a + s:
            return o + (v - a)
def read(v, n):
    o = v2o(v); return DATA[o:o + n] if o is not None else b""

SYMS = {}
NAME2ADDR = {}
out = subprocess.check_output(["readelf", "-sW", LIB], text=True, errors="replace")
for line in out.splitlines():
    m = re.match(r"\s*\d+:\s*([0-9a-f]+)\s+\d+\s+FUNC\s+\S+\s+\S+\s+\S+\s+(\S+)", line)
    if m:
        a = int(m.group(1), 16) & ~1
        SYMS[a] = m.group(2); NAME2ADDR[m.group(2)] = a
SORTED = sorted(SYMS)
def nm(a):
    a &= ~1
    if a in SYMS: return SYMS[a]
    import bisect
    i = bisect.bisect_right(SORTED, a) - 1
    if i >= 0 and a - SORTED[i] < 0x800:
        return f"{SYMS[SORTED[i]]}+0x{a-SORTED[i]:x}"
    return f"0x{a:x}"
def funclen(a):
    import bisect
    i = bisect.bisect_right(SORTED, a)
    return min((SORTED[i] - a) if i < len(SORTED) else 0x400, 0x1000)

md = Cs(CS_ARCH_ARM, CS_MODE_THUMB | CS_MODE_LITTLE_ENDIAN)
md.detail = True
USB = {NAME2ADDR.get(n) for n in ("libusb_bulk_transfer", "libusb_submit_transfer",
        "libusb_control_transfer", "libusb_interrupt_transfer") if n in NAME2ADDR}

visited = set()
def lit(ins):
    if ins.mnemonic.startswith("ldr") and "[pc" in ins.op_str:
        mm = re.search(r"\[pc, #(\-?\d+)\]", ins.op_str)
        if mm:
            pcbase = (ins.address + 4) & ~3
            return struct.unpack_from("<I", read(pcbase + int(mm.group(1)), 4))[0]
    return None

def walk(addr, depth, path):
    addr &= ~1
    if addr in visited or depth < 0: return
    visited.add(addr)
    code = read(addr, funclen(addr))
    inss = list(md.disasm(code, addr))
    # does this fn call a usb transfer?
    calls_usb = []
    children = []
    for idx, ins in enumerate(inss):
        if ins.mnemonic in ("bl", "blx") and ins.op_str.startswith("#"):
            tgt = int(ins.op_str[1:], 0) & ~1
            children.append(tgt)
            if tgt in USB:
                calls_usb.append((idx, ins, tgt))
    if calls_usb:
        print(f"\n### {nm(addr)} @0x{addr:x}  (via {' -> '.join(path)})")
        for idx, ins, tgt in calls_usb:
            print(f"   calls {nm(tgt)} @0x{ins.address:x}; preceding setup:")
            for j in range(max(0, idx - 14), idx):
                p = inss[j]
                extra = ""
                L = lit(p)
                if L is not None:
                    extra = f"   ; =0x{L:x} ({L})" + (f" [{SYMS[L&~1]}]" if (L & ~1) in SYMS else "")
                if (p.mnemonic.startswith("mov") or p.mnemonic.startswith("add")
                        or "#" in p.op_str or "ldr" in p.mnemonic):
                    print(f"      0x{p.address:x}: {p.mnemonic:8s} {p.op_str}{extra}")
    for tgt in children:
        if tgt not in USB and v2o(tgt) is not None:
            walk(tgt, depth - 1, path + [nm(tgt)])

if __name__ == "__main__":
    roots = sys.argv[1:] or ["MAG_LinkCameraEx", "MAG_StartProcessImage",
            "MAG_PrepareProcessImage", "MAG_TriggerFFC", "MAG_SetFFCMode", "MAG_ResetCamera"]
    for r in roots:
        a = NAME2ADDR.get(r)
        if a is None:
            print("no sym", r); continue
        walk(a, 6, [r])
