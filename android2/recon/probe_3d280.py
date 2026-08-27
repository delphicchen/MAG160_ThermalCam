#!/usr/bin/env python3
"""Black-box probe of sub_3d280 (per-frame k,b derivation) under Unicorn emulation.

sub_3d280(r4=ctx, s0,s4,s18 floats, r0,r5 ints, [sp]) writes:
    ctx[0x50] = k   (float)
    ctx[0x54] = b   (float)
consumed by sub_3f970: v = raw*(k+1)+b -> Planck radLUT -> U(milli-K).
"""
import sys, os, struct
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from emu_common import build_parsed_ctx
from emu_harness import Emu, BASE, STACK
from emu_build_nuc import seed_gates
from unicorn.arm_const import UC_ARM_REG_R0, UC_ARM_REG_R4, UC_ARM_REG_R5
from unicorn.arm_const import UC_ARM_REG_S0, UC_ARM_REG_LR, UC_ARM_REG_SP

SP = STACK - 0x100                      # inside the mapped stack region
RET = 0xdeadbee0

def rf(e, a): return struct.unpack('<f', e.mu.mem_read(a, 4))[0]
def r32(e, a): return struct.unpack('<I', bytes(e.mu.mem_read(a, 4)))[0]

def call_3d280(e, ctx, s0=0.0, s4=0.0, s18=0.0, r0=0, r5=0, sp0=0):
    e.w32(SP, sp0)                          # [sp] stack arg read at entry
    u = e.mu
    u.reg_write(UC_ARM_REG_R4, ctx)         # ctx in r4!
    u.reg_write(UC_ARM_REG_R0, r0 & 0xffffffff)
    u.reg_write(UC_ARM_REG_R5, r5 & 0xffffffff)
    for idx, val in ((0, s0), (4, s4), (18, s18)):
        bits = struct.unpack('<I', struct.pack('<f', val))[0]
        u.reg_write(UC_ARM_REG_S0 + idx, bits)
    u.reg_write(UC_ARM_REG_LR, RET | 1)
    u.reg_write(UC_ARM_REG_SP, SP)
    u.emu_start(BASE + 0x3d280 | 1, RET, count=2_000_000)
    return rf(e, ctx + 0x50), rf(e, ctx + 0x54)

def main():
    e, ctx, _ = build_parsed_ctx(verbose=False)
    seed_gates(e.mu, ctx)

    print("== gates after seed ==")
    for off in (0x7754, 0x7758, 0x775c):
        print(f"  ctx[{off:#06x}] = {r32(e, ctx+off):#10x}")

    # controlled environment: simple paths everywhere
    setup = {
        0x16f4: 0,          # !=0 -> block-array path; 0 -> r2 base = 0
        0x38: 19000,        # FPA temp (anchor-A units)
        0x3c: 19500,
        0x40: 200,
        0x44: 300,
        0x48: 1,            # !=0 -> skip the +-1000 clamp branch
    }
    for off, val in setup.items():
        e.w32(ctx + off, val)

    print("\n== probe 1: all-zero inputs ==")
    k, b = call_3d280(e, ctx)
    print(f"  k={k:.6g} b={b:.6g}")

    print("\n== probe 2: vary s18 (b base?) ==")
    for s18 in (-250000.0, 0.0, 250000.0):
        k, b = call_3d280(e, ctx, s18=s18)
        print(f"  s18={s18:>9.0f} -> k={k:.6g} b={b:.6g}")

    print("\n== probe 3: vary s0/s4 (k = product?) ==")
    for s0, s4 in ((1.0, 1.0), (2.0, 3.0), (0.5, -4.0)):
        k, b = call_3d280(e, ctx, s18=-250000.0, s0=s0, s4=s4)
        print(f"  s0={s0} s4={s4} -> k={k:.6g} b={b:.6g}")

    print("\n== probe 4: vary stack arg [sp] (sub_3d9f4 shift input?) ==")
    for spv in (0, 8, 16000, 48000, 10**6):
        k, b = call_3d280(e, ctx, s18=-250000.0, sp0=spv)
        print(f"  [sp]={spv} -> k={k:.6g} b={b:.6g}")

    print("\n== probe 5: block-array path (ctx[0x16f4]!=0) ==")
    e.w32(ctx + 0x16f4, 1)
    e.w32(ctx + 0x16f8, 8)          # table length bound for idx
    for idx in range(8):            # fill both arrays for idx 0..7
        p = ctx + idx * 0x21c
        e.w32(p + 0x1730, 1000 + idx * 100)   # array A[idx]
        e.w32(p + 0x1734, 2000 + idx * 100)   # array B[idx]
    for t in (0, 3, 7, 9):          # 9 >= len(8) -> clamps idx->0?
        e.w32(ctx + 0x80, t)
        k, b = call_3d280(e, ctx, s18=-250000.0, sp0=16000)
        print(f"  ctx[0x80]={t} -> k={k:.6g} b={b:.6g}")
    print("  (array A=+0x1730, B=+0x1734; B-A=1000 const)")

if __name__ == "__main__":
    main()
