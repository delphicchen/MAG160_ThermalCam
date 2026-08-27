#!/usr/bin/env python3
"""Differential experiment: patch mag_cali.bin map regions -> re-run parse+build
under emulation -> diff the resulting NUC tables. Reveals the factory data flow
without reading any more assembly."""
import sys, os, struct
sys.path.insert(0, '/home/delphic/win_share/Delphic/mag160_thermalcam/recon')
import numpy as np
import emu_common
from emu_common import build_parsed_ctx
from emu_build_nuc import seed_gates, BUILD

H, W = 120, 160
NPIX = H * W
BASE_CALI = open('/home/delphic/win_share/Delphic/mag160_thermalcam/recon/mag_cali.bin','rb').read()

def tables_with(cali_bytes, fpa=20000):
    """parse+build with a custom cali file; return dict of table arrays."""
    emu_common.CALI_DATA = cali_bytes
    # serve modified file through the libc hooks
    import emu_harness
    e = emu_harness.Emu(verbose=False)
    u = e.mu
    vf = {"pos": 0, "data": cali_bytes, "open": False}
    emu_common.install_libc(e, vf)
    def dbg(uc, addr, size, ud):
        if addr == emu_harness.BASE + 0x42900:
            uc.reg_write(emu_common._uc.UC_ARM_REG_R0, 0x7fffffff)
    u.hook_add(emu_common._uc.UC_HOOK_CODE, dbg,
               begin=emu_harness.BASE + 0x428f0, end=emu_harness.BASE + 0x42910)
    ctx = e.malloc(emu_common.CTX_SZ); u.mem_write(ctx, b"\0" * emu_common.CTX_SZ)
    path = e.malloc(64); u.mem_write(path, b"/tmp/cali.bin\0")
    for off, val in {0x20c:160, 0x1e2c:160, 0x1e30:120, 0x7740:0x10000}.items():
        u.mem_write(ctx + off, struct.pack('<I', val))
    from emu_build_nuc import seed_gates
    seed_gates(u, ctx)
    u.mem_write(ctx + 0x38, struct.pack('<I', fpa))
    e.call(emu_common.PARSER, args=(ctx, path), count=400_000_000)
    if vf["pos"] != len(cali_bytes):
        print(f"  !! parser consumed {vf['pos']}/{len(cali_bytes)}")
    e.call(BUILD, args=(ctx, 0, 0), count=400_000_000)
    e.call(BUILD, args=(ctx, 1, 2), count=400_000_000)
    def r32(a): return struct.unpack('<I', bytes(u.mem_read(a,4)))[0]
    bufB, bufC = r32(ctx+0x16e0), r32(ctx+0x16ec)
    refp = r32(ctx+0x16d8)
    ref = np.frombuffer(bytes(u.mem_read(refp, NPIX*2)), np.uint16).astype(np.int64)
    bb = np.frombuffer(bytes(u.mem_read(bufB, NPIX*4)), np.int16).astype(np.int64)
    bc = np.frombuffer(bytes(u.mem_read(bufC, 2*NPIX*3*2)), np.uint16).astype(np.int64)
    idx = np.arange(NPIX)
    return dict(
        ref=ref,
        bp=np.stack([bb[2*idx+s] for s in range(2)], axis=1),
        gain=np.stack([bc[2*idx+2*NPIX*s] for s in range(3)], axis=1),
        off=np.stack([bc[2*idx+2*NPIX*s+1] for s in range(3)], axis=1),
    )

def diff(name, T0, T1):
    print(f"--- {name} ---")
    for key in ("ref","bp","gain","off"):
        a, b = T0[key], T1[key]
        if a.shape != b.shape:
            print(f"  {key}: SHAPE {a.shape} vs {b.shape}"); continue
        nd = int((a != b).sum())
        if nd == 0:
            print(f"  {key}: identical")
        else:
            d = np.abs(a - b)
            print(f"  {key}: {nd}/{a.size} differ ({nd/a.size*100:.1f}%) max|d|={d.max()}")

if __name__ == "__main__":
    print("building baseline (unmodified)...")
    T0 = tables_with(BASE_CALI)
    print("  ref mean", T0["ref"].mean(), "gain0 mean", T0["gain"][:,0].mean())

    # A) zero gain map pos0 of block 1 (file offset 1024 + 8*38400)
    for pos, name in ((8,"gain pos0 blk1"), (9,"gain pos1 blk1"),
                      (10,"ref pos2 blk1"), (11,"ref pos3 blk1")):
        mod = bytearray(BASE_CALI)
        off = 1024 + pos*38400
        mod[off:off+38400] = b"\0" * 38400
        print(f"\nzeroing file map {pos} ({name})...")
        try:
            T1 = tables_with(bytes(mod))
            diff(name, T0, T1)
        except Exception as ex:
            print("  FAILED:", ex)
