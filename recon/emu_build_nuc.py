#!/usr/bin/env python3
"""ROUTE A (COMPLETE): reverse out the factory per-pixel radiometric NUC.

Runs the camera SDK's own ARM build chain under emulation to expand mag_cali.bin
into the factory per-pixel piecewise NUC, then extracts the gain/offset tables and
validates a pure-numpy reimplementation of the apply against the emulated firmware.

Pipeline (all emulated from the real libcoresdk.so):
  parser   sub_424d0(ctx, path)         -> populate ctx + the 6.3 MB bufA
  prepare  sub_3a7f8(ctx, mode=0, 0)    -> sub_3a574 (section-select into bufA) +
                                            sub_3b024 (temp-prep, interp weight)
  build    sub_3a7f8(ctx, mode=1, 2)    -> breakpoint interp -> bufB,
                                            gain/offset build (sub_3bc54) -> bufC
  apply    sub_45ca4(ctx, raw)          -> per-pixel piecewise correction

Confirmed table layout (nseg segments, npix pixels):
  breakpoint[i, s] = bufB_int16[2*i + s]              s in 0..nseg-2
  gain[i, s]       = bufC_uint16[2*i + 2*npix*s]      s in 0..nseg-1
  offset[i, s]     = bufC_uint16[2*i + 2*npix*s + 1]
Apply per pixel i:
  v   = (raw[i] - offset_ref[i]) >> 1
  s   = first segment with v <= breakpoint[i, s]  (else last)
  out = clamp( ((v * gain[i, s]) >> shift) + offset[i, s], 0, 65535 )

The numpy apply below is BIT-EXACT to the emulated firmware (max|diff| = 0).
NB: bufB/bufC are interpolated for the FPA temperature in ctx[0x38]; rerun at the
live FPA temp (anchor-table A units) to get the tables for that operating point.
"""
import os, struct
import numpy as np
from unicorn.arm_const import *
from emu_harness import BASE
from emu_common import build_parsed_ctx

HERE = os.path.dirname(os.path.abspath(__file__))
BUILD = BASE + 0x3a7f8
APPLY = BASE + 0x45ca4
W, H = 160, 120
NPIX = W * H
FPA_ANCHOR = 25000          # ctx[0x38]: FPA temp in anchor-table-A units (A=[9564..49091])


def u32(u, a): return struct.unpack('<I', bytes(u.mem_read(a, 4)))[0]
def w32(u, a, v): u.mem_write(a, struct.pack('<I', v & 0xffffffff))


def seed_gates(u, ctx):
    """Seed the radiometric-range / shift gates the build loop checks (normally
    set by the mode==1 branch + full device init)."""
    for off, val in {0x7740: NPIX, 0x7758: 1, 0x775c: 12, 0x7760: 0, 0x7768: 646,
                     0x7764: 0, 0x776c: 646, 0x7770: NPIX, 0x13e0: 0, 0x104: 12}.items():
        w32(u, ctx + off, val)


def build_at_fpa(e, ctx, fpa):
    """Run the prepare+build chain at one FPA temp (anchor units); extract tables.
    Re-runnable on the same parsed ctx (it recomputes from the unchanged bufA)."""
    u = e.mu
    w32(u, ctx + 0x38, fpa)
    bufB, bufC = u32(u, ctx + 0x16e0), u32(u, ctx + 0x16ec)
    e.call(BUILD, args=(ctx, 0, 0), count=400_000_000)   # prepare
    e.call(BUILD, args=(ctx, 1, 2), count=400_000_000)   # build
    nseg, shift = u32(u, ctx + 0x13f4), u32(u, ctx + 0x104)
    ref = np.frombuffer(bytes(u.mem_read(u32(u, ctx + 0x16d8), NPIX * 2)), np.uint16).astype(np.int64)
    bb = np.frombuffer(bytes(u.mem_read(bufB, NPIX * 4)), np.int16).astype(np.int64)
    bc = np.frombuffer(bytes(u.mem_read(bufC, 2 * NPIX * nseg * 2)), np.uint16).astype(np.int64)
    idx = np.arange(NPIX)
    return dict(
        nseg=nseg, shift=shift, ref=ref,
        bp=np.stack([bb[2 * idx + s] for s in range(nseg - 1)], axis=1),
        gain=np.stack([bc[2 * idx + 2 * NPIX * s] for s in range(nseg)], axis=1),
        offset=np.stack([bc[2 * idx + 2 * NPIX * s + 1] for s in range(nseg)], axis=1),
    )


def run_build(fpa=FPA_ANCHOR, verbose=True):
    """Emulate parse + prepare + build; return (emu, ctx, table dict)."""
    e, ctx, _ = build_parsed_ctx(verbose=verbose)
    seed_gates(e.mu, ctx)
    return e, ctx, build_at_fpa(e, ctx, fpa)


def np_apply(tab, raw):
    """Pure-numpy port of the firmware per-pixel piecewise apply (bit-exact)."""
    ref, bp, gain, offset = tab["ref"], tab["bp"], tab["gain"], tab["offset"]
    nseg, shift, idx = tab["nseg"], tab["shift"], np.arange(NPIX)
    v = (np.asarray(raw, np.int64).ravel() - ref) >> 1
    seg = np.zeros(NPIX, np.int64)
    for s in range(nseg - 1):
        seg = np.where((seg == s) & (v > bp[:, s]), s + 1, seg)
    out = ((v * gain[idx, seg]) >> shift) + offset[idx, seg]
    return np.clip(out, 0, 65535)


def main():
    e, ctx, tab = run_build()
    u = e.mu
    print(f"build OK: nseg={tab['nseg']} shift={tab['shift']} "
          f"ref[min/mean/max]={tab['ref'].min()}/{tab['ref'].mean():.0f}/{tab['ref'].max()}")
    print(f"gain seg0 mean={tab['gain'][:,0].mean():.1f} (/4096={tab['gain'][:,0].mean()/4096:.3f})  "
          f"offset seg0 mean={tab['offset'][:,0].mean():.1f}")

    # validate numpy apply vs emulated firmware apply
    offref = e.malloc(NPIX * 2); u.mem_write(offref, tab["ref"].astype(np.uint16).tobytes())
    out = e.malloc(NPIX * 2); raw = e.malloc(NPIX * 2)
    w32(u, ctx + 0x1d58, offref); w32(u, ctx + 0x1d70, 1); w32(u, ctx + 0x22c, out)

    def emu_apply(level):
        u.mem_write(raw, struct.pack('<H', level) * NPIX); u.mem_write(out, b"\0" * NPIX * 2)
        e.call(APPLY, args=(ctx, raw), count=50_000_000)
        return np.frombuffer(bytes(u.mem_read(out, NPIX * 2)), np.uint16).astype(np.int64)

    base = int(tab["ref"].mean())
    ok = True
    for lv in (base + 2000, base + 6000, base + 9000):
        d = int(np.abs(emu_apply(lv) - np_apply(tab, np.full(NPIX, lv))).max())
        ok = ok and d == 0
        print(f"  apply(level={lv}): emu vs numpy max|diff| = {d}")
    print("NUMPY APPLY MATCHES FIRMWARE:", "PASS" if ok else "FAIL")

    np.savez(os.path.join(HERE, "factory_nuc_tables.npz"),
             breakpoints=tab["bp"].reshape(H, W, tab["nseg"] - 1),
             gain=tab["gain"].reshape(H, W, tab["nseg"]),
             offset=tab["offset"].reshape(H, W, tab["nseg"]),
             ref=tab["ref"].reshape(H, W), nseg=tab["nseg"], shift=tab["shift"],
             fpa_anchor=FPA_ANCHOR)
    print("saved factory_nuc_tables.npz")


if __name__ == "__main__":
    main()
