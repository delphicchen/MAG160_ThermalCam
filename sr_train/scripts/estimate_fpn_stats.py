#!/usr/bin/env python3
"""
Check one camera's noise against the degradation's `sensor_*` ranges (°C).

The defaults are generic microbolometer ranges that already cover the MAG160 frames they
were checked against; run this only to see whether *your* unit is noisier. Capture a few
hundred frames of a *static*, roughly uniform scene (a wall, a palm held still) without
an FFC — e.g. a MagViewer recording with "Save temperature data" on, whose .mgt holds
°C — save them as one (N, H, W) .npy of °C, then:

    python scripts/estimate_fpn_stats.py captures/wall_300.npy

The temporal mean cancels shot noise and leaves the fixed pattern; the column and row
means of that residual are the stripes, what is left is the 2-D residual.
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

    s = fpn_from_frames(f)
    print(f'{f.shape[0]} frames of {f.shape[2]}x{f.shape[1]}  (units of the input, °C for .mgt)')
    rows = (('sensor_noise_c', s['noise'], 0.20),
            ('sensor_stripe_c', max(s['stripe_col'], s['stripe_row']), 0.10),
            ('sensor_map_c', s['map'], 0.05))
    for key, v, default_hi in rows:
        verdict = 'covered' if v * 1.5 <= default_hi else f'WIDEN: {key}: [0.0, {v * 1.5:.3f}]'
        print(f'  {key:16s} measured {v:.4f}   default upper {default_hi:.2f}   -> {verdict}')
    print(f'  (stripes: columns {s["stripe_col"]:.4f}, rows {s["stripe_row"]:.4f})')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
