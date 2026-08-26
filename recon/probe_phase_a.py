#!/usr/bin/env python3
"""Phase A: dump ctx state after the full parse+prepare+build chain, to locate
the data sources sub_3d228 reads (slope tables, parallel arrays, index fields)."""
import sys, os, struct
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from emu_common import build_parsed_ctx
from emu_build_nuc import seed_gates, build_at_fpa
import numpy as np

def r32(e, a): return struct.unpack('<I', bytes(e.mu.mem_read(a, 4)))[0]
def rf32(e, a): return struct.unpack('<f', bytes(e.mu.mem_read(a, 4)))[0]

def main():
    e, ctx, _ = build_parsed_ctx(verbose=False)
    seed_gates(e.mu, ctx)
    tab = build_at_fpa(e, ctx, 20000)     # prepare + build at a realistic FPA

    print("== after build @20000 ==")
    for off in (0x38, 0x3c, 0x40, 0x44, 0x48, 0x50, 0x54, 0x58, 0x80):
        v = r32(e, ctx + off)
        print(f"  ctx[{off:#06x}] = {v:#10x}  f32={rf32(e, ctx+off):.6g}")
    for off in (0x16f4, 0x16f8, 0x13e0, 0x13ec, 0x13f0, 0x13f4, 0x13f8,
                0x13fc, 0x1400, 0x12d4, 0x12dc, 0x16d4):
        print(f"  ctx[{off:#06x}] = {r32(e, ctx+off):#10x}  f32={rf32(e, ctx+off):.6g}")

    # slope source table: base = ctx + ctx[0x13f4]?? verify both readings
    base_off = r32(e, ctx + 0x13f4)
    print(f"\n  ctx[0x13f4] as base offset -> base=ctx+{base_off:#x}")
    for sec in range(-2, 3):
        b = ctx + base_off + sec * 8 * 0  # placeholder; dump around +264..276
    for delta in (264, 268, 272, 276):
        a = ctx + base_off + delta
        print(f"    [ctx+{base_off:#x}+{delta}] = {r32(e,a):#010x} f32={rf32(e,a):.6g}")

    # scan wider: floats near base+0..512 that look like plausible k/b slopes
    print("\n  float scan [ctx+0x13ec region ±]:")
    for d in range(0x1300, 0x1420, 4):
        f = rf32(e, ctx + d)
        if f != 0 and abs(f) < 1e6 and f == f:
            print(f"    ctx[{d:#06x}] f32={f:.6g} u32={r32(e,ctx+d):#010x}")

    # parallel arrays p[0x1730/1734] for idx 0..11
    print("\n== parallel arrays (stride 0x21c) ==")
    for idx in range(12):
        p = ctx + idx * 0x21c
        a, b = r32(e, p + 0x1730), r32(e, p + 0x1734)
        print(f"  idx={idx:2d}: p[0x1730]={a:#10x} p[0x1734]={b:#10x}"
              + (f"  f32={rf32(e,p+0x1730):.5g},{rf32(e,p+0x1734):.5g}" if (a or b) else ""))

    # anchors A for reference
    print("\n== anchor table A ==")
    print("  ", [r32(e, ctx + 0x1404 + 4 * i) for i in range(6)])

if __name__ == "__main__":
    main()
