#!/usr/bin/env python3
"""Pre-store the factory per-pixel NUC tables over a grid of FPA temperatures.

Runs the emulated factory build chain (emu_build_nuc.build_at_fpa) once per grid
point and stacks the extracted tables into `../factory_nuc_grid.npz`, which the
live viewer loads and interpolates to the camera's live FPA temp (frame tail+8,
same anchor-A units). Parse once, rebuild per FPA (each ~0.1 s).

Valid FPA range is ~[9000, 29000] anchor units (the cold-side cali sections; the
hot anchors A[3..5] are empty in mag_cali.bin). The camera's real operating point
(~19000-21000) sits comfortably inside.
"""
import os, time
import numpy as np
from emu_build_nuc import build_parsed_ctx, seed_gates, build_at_fpa, H, W

OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   "factory_nuc_grid.npz")
GRID = list(range(9000, 29001, 1000))   # FPA anchor units


def main():
    e, ctx, _ = build_parsed_ctx(verbose=False)
    seed_gates(e.mu, ctx)
    t0 = time.time()
    fpas, refs, bps, gains, offs = [], [], [], [], []
    nseg = shift = None
    for fpa in GRID:
        tab = build_at_fpa(e, ctx, fpa)
        if tab["gain"][:, 0].mean() <= 0 or tab["ref"].mean() <= 0:
            print(f"  fpa={fpa}: empty section, skipped")
            continue
        nseg, shift = tab["nseg"], tab["shift"]
        fpas.append(fpa)
        refs.append(tab["ref"].reshape(H, W).astype(np.int16))
        bps.append(tab["bp"].reshape(H, W, nseg - 1).astype(np.int16))
        gains.append(tab["gain"].reshape(H, W, nseg).astype(np.uint16))
        offs.append(tab["offset"].reshape(H, W, nseg).astype(np.uint16))
        print(f"  fpa={fpa}: ref.mean={tab['ref'].mean():.0f} "
              f"gain0.mean={tab['gain'][:,0].mean():.0f}")
    np.savez_compressed(
        OUT, fpa=np.array(fpas, np.int32),
        ref=np.stack(refs), breakpoints=np.stack(bps),
        gain=np.stack(gains), offset=np.stack(offs),
        nseg=nseg, shift=shift)
    print(f"saved {OUT}: {len(fpas)} grid points, nseg={nseg} shift={shift} "
          f"({time.time()-t0:.1f}s)")


if __name__ == "__main__":
    main()
