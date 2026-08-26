#!/usr/bin/env python3
"""Differential experiment v2: swap mag_cali.bin on disk -> official build_parsed_ctx
-> build -> diff tables. Reveals the factory data flow without reading assembly."""
import sys, os, struct, shutil
sys.path.insert(0, '/home/delphic/win_share/Delphic/mag160_thermalcam/recon')
import numpy as np

RECON = '/home/delphic/win_share/Delphic/mag160_thermalcam/recon'
CALI_PATH = os.path.join(RECON, 'mag_cali.bin')
BACKUP = '/tmp/opencode/mag_cali_orig.bin'
BASE_CALI = open(CALI_PATH, 'rb').read()
shutil.copy(CALI_PATH, BACKUP)

H, W = 120, 160
NPIX = H * W

def tables_with(mod_bytes, fpa=20000):
    with open(CALI_PATH, 'wb') as f:
        f.write(mod_bytes)
    # fresh import each time so emu_common re-reads the file
    for m in list(sys.modules):
        if m.startswith('emu_') or m == 'unicorn.arm_const':
            del sys.modules[m]
    import emu_common
    from emu_common import build_parsed_ctx
    from emu_build_nuc import seed_gates, BUILD
    e, ctx, _ = build_parsed_ctx(verbose=False)
    seed_gates(e.mu, ctx)
    e.w32(ctx + 0x38, fpa)
    e.call(BUILD, args=(ctx, 0, 0), count=400_000_000)
    e.call(BUILD, args=(ctx, 1, 2), count=400_000_000)
    def r32(a): return struct.unpack('<I', bytes(e.mu.mem_read(a, 4)))[0]
    bufB, bufC = r32(ctx + 0x16e0), r32(ctx + 0x16ec)
    refp = r32(ctx + 0x16d8)
    ref = np.frombuffer(bytes(e.mu.mem_read(refp, NPIX * 2)), np.uint16).astype(np.int64)
    bb = np.frombuffer(bytes(e.mu.mem_read(bufB, NPIX * 4)), np.int16).astype(np.int64)
    bc = np.frombuffer(bytes(e.mu.mem_read(bufC, 2 * NPIX * 3 * 2)), np.uint16).astype(np.int64)
    idx = np.arange(NPIX)
    return dict(
        ref=ref,
        bp=np.stack([bb[2 * idx + s] for s in range(2)], axis=1),
        gain=np.stack([bc[2 * idx + 2 * NPIX * s] for s in range(3)], axis=1),
        off=np.stack([bc[2 * idx + 2 * NPIX * s + 1] for s in range(3)], axis=1),
    )

def diff(name, T0, T1):
    print(f"--- {name} ---")
    for key in ("ref", "bp", "gain", "off"):
        a, b = T0[key], T1[key]
        nd = int((a != b).sum())
        if nd == 0:
            print(f"  {key}: identical")
        else:
            dd = np.abs(a - b)
            print(f"  {key}: {nd}/{a.size} differ ({nd/a.size*100:.1f}%) max|d|={int(dd.max())}")

if __name__ == "__main__":
    try:
        print("baseline...")
        T0 = tables_with(BASE_CALI)
        print("  ref mean", T0["ref"].mean(), " gain0 mean", T0["gain"][:, 0].mean())
        for pos, name in ((8, "zero gain pos0 blk1"), (9, "zero gain pos1 blk1"),
                          (10, "zero ref pos2 blk1"), (11, "zero ref pos3 blk1"),
                          (0, "zero gain pos0 blk0"), (2, "zero ref pos2 blk0")):
            mod = bytearray(BASE_CALI)
            off = 1024 + pos * 38400
            mod[off:off + 38400] = b"\0" * 38400
            print(f"\nzeroing file map {pos} ({name}):")
            try:
                T1 = tables_with(bytes(mod))
                diff(name, T0, T1)
            except Exception as ex:
                print("  FAILED:", type(ex).__name__, ex)
    finally:
        shutil.copy(BACKUP, CALI_PATH)
        print("\noriginal cali restored")
