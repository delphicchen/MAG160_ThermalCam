#!/usr/bin/env python3
"""Phase A2: verify slope-table hypothesis.
sub_3a574: after bracketing section i, base = ctx + 8*i;
  ctx[0x13f0] = tbl[i][268] + ([276]-[268])*w
  ctx[0x13ec] = tbl[i][264] + ([272]-[264])*w   (w = interp weight vs anchors A)
Run prepare at several FPAs, dump the raw source floats, check consistency."""
import sys, os, struct
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from emu_common import build_parsed_ctx
from emu_build_nuc import seed_gates, BUILD
from unicorn.arm_const import *
import numpy as np

def r32(e, a): return struct.unpack('<I', bytes(e.mu.mem_read(a, 4)))[0]
def rf32(e, a): return struct.unpack('<f', bytes(e.mu.mem_read(a, 4)))[0]

e, ctx, _ = build_parsed_ctx(verbose=False)
seed_gates(e.mu, ctx)

# --- dump candidate source region BEFORE any prepare ---
print("== source region before prepare ==")
for d in range(0x100, 0x140, 4):
    u, f = r32(e, ctx + d), rf32(e, ctx + d)
    if u: print(f"  ctx[{d:#06x}] = {u:#010x} f32={f:.6g}")

def prepare_at(fpa):
    e.w32(ctx + 0x38, fpa)
    e.call(BUILD, args=(ctx, 0, 0), count=400_000_000)   # prepare (sub_3a7f8 mode 0)
    return dict(sec=r32(e, ctx + 0x16d4),
                w=r32(e, ctx + 0x13e0),
                k_slope=rf32(e, ctx + 0x13ec),
                b_slope=rf32(e, ctx + 0x13f0))

print("\n== prepare at various FPA ==")
A = [9564, 19330, 29108, 34111, 39244, 49091]
results = {}
for fpa in (9564, 15000, 19330, 20000, 25000, 29108, 34000):
    r = prepare_at(fpa)
    results[fpa] = r
    print(f"  FPA={fpa:5d}: sec={r['sec']} weight={r['w']:#010x} "
          f"k_slope={r['k_slope']:.6g} b_slope={r['b_slope']:.6g}")

# --- reconstruct source tables from two probes within same section ---
print("\n== back out source floats ==")
# within one section, w linear in FPA: slope_k(FPA) = t264 + (t272-t264)*w
for lo, hi in ((19330, 29108), (9564, 19330)):
    xs = [fpa for fpa in results if lo <= fpa <= hi]
    if len(xs) < 2: continue
    xs.sort()
    f1, f2 = xs[0], xs[-1]
    w1 = results[f1]['w'] / 65536.0
    w2 = results[f2]['w'] / 65536.0
    k1, k2 = results[f1]['k_slope'], results[f2]['k_slope']
    b1, b2 = results[f1]['b_slope'], results[f2]['b_slope']
    if w2 == w1: continue
    t272_minus_t264 = (k2 - k1) / (w2 - w1)
    t264 = k1 - t272_minus_t264 * w1
    tb276_minus_tb268 = (b2 - b1) / (w2 - w1)
    tb268 = b1 - tb276_minus_tb268 * w1
    print(f"  section [{lo},{hi}]: k-src ≈ [{t264:.6g}, {t272_minus_t264+t264:.6g}]"
          f"  b-src ≈ [{tb268:.6g}, {tb276_minus_tb268+tb268:.6g}]")

# --- now read the actual memory at ctx + 8*sec + 264.. ---
print("\n== direct read at ctx + 8*sec + {264,268,272,276} ==")
for sec in range(6):
    base = ctx + 8 * sec
    vals = [rf32(e, base + d) for d in (264, 268, 272, 276)]
    print(f"  sec={sec}: {[f'{v:.6g}' for v in vals]}")
