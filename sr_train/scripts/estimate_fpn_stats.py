#!/usr/bin/env python3
"""
Measure this camera's fixed-pattern noise, so the degradation amplitudes in the yml are
the sensor's real numbers instead of a guess.

Capture a few hundred frames of a *static*, roughly uniform scene (a wall, a palm held
still) without triggering an FFC, save them as a single (N, H, W) .npy of raw counts or
°C, then:

    python scripts/estimate_fpn_stats.py captures/wall_300.npy

It prints the three sigmas to paste into the yml, plus the temporal noise for context.
The temporal mean cancels shot noise and leaves the fixed pattern; the column and row
means of that residual are the structured part, and what is left is the 2-D residual.
"""

import argparse
import sys

import numpy as np

sys.path.insert(0, str(__import__('pathlib').Path(__file__).resolve().parents[1]))
from thermal_arch.thermal_degradation import fpn_from_frames  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('frames', help='(N, H, W) .npy of raw counts or °C')
    args = ap.parse_args()

    f = np.load(args.frames).astype(np.float64)
    if f.ndim != 3:
        print(f'expected (N, H, W), got {f.shape}', file=sys.stderr)
        return 1

    stats = fpn_from_frames(f)
    scale = float(np.percentile(f, 99) - np.percentile(f, 1))
    temporal = float(f.std(axis=0).mean())

    print(f'{f.shape[0]} frames of {f.shape[2]}x{f.shape[1]}')
    print(f'full-scale span (p1..p99) : {scale:.3f}')
    print(f'temporal noise per pixel   : {temporal:.4f}  ({temporal / scale * 100:.2f}% FS)')
    print()
    print('paste into the yml (upper bound = 1.5x the measured sigma, so training covers')
    print('a worse NUC than yours):')
    for k in ('fpn_col_sigma', 'fpn_row_sigma', 'fpn_map_sigma'):
        v = stats[k]
        print(f'  {k}: [0.0, {v * 1.5:.4f}]        # measured {v:.4f} FS')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
