#!/usr/bin/env python3
"""
Build the HR training set from public thermal datasets + your own captures.

Real-ESRGAN trains on HR images only and synthesises the LR side on the fly, so the job
here is: find every usable thermal image, normalise it the way the viewer does, cut it
into square crops, throw away the flat ones, and write the meta_info list.

Expected input layout (datasets/thermal_raw/<source>/**), any depth:

    datasets/thermal_raw/flir_adas_v2/      # 640x512 16-bit TIFF or 8-bit PNG/JPEG
    datasets/thermal_raw/kaist/             # 640x512 LWIR PNG
    datasets/thermal_raw/pbvs_tisr/         # PBVS TISR challenge HR set
    datasets/thermal_raw/mag160/            # your own captures (see --mag-note below)

Output:

    datasets/thermal_hr/<source>_<n>.png            480x480 8-bit RGB
    datasets/meta_info/thermal_hr.txt               one relative path per line

Usage:
    python scripts/prepare_thermal_dataset.py --crop 480 --stride 360

Note on MAG160Core captures: our sensor is 160x120, i.e. it is the *LR* side. Those
frames cannot serve as HR ground truth — a 4x target does not exist for them. Use them
for validation (scripts/hallucination_check.py) and for measuring the degradation
statistics (scripts/estimate_fpn_stats.py), and keep them out of datasets/thermal_hr/.
"""

import argparse
import pathlib
import sys

import cv2
import numpy as np

EXTS = {'.png', '.jpg', '.jpeg', '.tif', '.tiff', '.bmp'}


def load_gray(path: pathlib.Path) -> np.ndarray | None:
    img = cv2.imread(str(path), cv2.IMREAD_UNCHANGED)
    if img is None:
        return None
    if img.ndim == 3:
        img = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    return img.astype(np.float32)


def normalise(img: np.ndarray, lo_pct: float, hi_pct: float) -> np.ndarray:
    """Percentile stretch to 0..255 — the same mapping the viewer applies to °C, so the
    network sees the contrast statistics it will meet at inference."""
    lo, hi = np.percentile(img, [lo_pct, hi_pct])
    if hi - lo < 1e-6:
        return np.zeros_like(img, dtype=np.uint8)
    return np.clip((img - lo) / (hi - lo) * 255.0, 0, 255).astype(np.uint8)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--raw', default='datasets/thermal_raw')
    ap.add_argument('--out', default='datasets/thermal_hr')
    ap.add_argument('--meta', default='datasets/meta_info/thermal_hr.txt')
    ap.add_argument('--crop', type=int, default=480)
    ap.add_argument('--stride', type=int, default=360)
    ap.add_argument('--min-std', type=float, default=8.0,
                    help='drop crops flatter than this (sky, walls, blank frames)')
    ap.add_argument('--lo-pct', type=float, default=1.0)
    ap.add_argument('--hi-pct', type=float, default=99.0)
    args = ap.parse_args()

    raw = pathlib.Path(args.raw)
    out = pathlib.Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    pathlib.Path(args.meta).parent.mkdir(parents=True, exist_ok=True)

    if not raw.is_dir():
        print(f'no such directory: {raw}', file=sys.stderr)
        return 1

    # Sources are the sub-directories of --raw, plus any images sitting loose in it.
    sources = [p for p in sorted(raw.iterdir()) if p.is_dir() and p.name != 'mag160']
    loose = [p for p in sorted(raw.iterdir()) if p.is_file() and p.suffix.lower() in EXTS]
    if any(p.name == 'mag160' for p in raw.iterdir()):
        print('[skip] mag160: LR-side captures, not HR training data')
    if loose:
        print(f'[{raw.name}] {len(loose)} loose images')
    if not sources and not loose:
        print(f'nothing to read under {raw} — put HR thermal images there', file=sys.stderr)
        return 1

    kept, skipped_flat, skipped_small, names = 0, 0, 0, []
    for src_dir in sources + [raw]:
        if src_dir is raw:
            files = loose
        else:
            files = sorted(q for q in src_dir.rglob('*') if q.suffix.lower() in EXTS)
            print(f'[{src_dir.name}] {len(files)} files')
        before = kept
        for f in files:
            img = load_gray(f)
            if img is None:
                continue
            h, w = img.shape
            if h < args.crop or w < args.crop:
                skipped_small += 1
                continue
            g = normalise(img, args.lo_pct, args.hi_pct)
            for y in range(0, h - args.crop + 1, args.stride):
                for x in range(0, w - args.crop + 1, args.stride):
                    tile = g[y:y + args.crop, x:x + args.crop]
                    if tile.std() < args.min_std:
                        skipped_flat += 1
                        continue
                    name = f'{src_dir.name}_{kept:06d}.png'.replace('/', '_')
                    cv2.imwrite(str(out / name), cv2.cvtColor(tile, cv2.COLOR_GRAY2BGR))
                    names.append(name)
                    kept += 1

        print(f'  -> {kept - before} crops from {src_dir.name}')

    pathlib.Path(args.meta).write_text('\n'.join(names) + '\n')
    print(f'\nwrote {kept} crops to {out}')
    print(f'  skipped: {skipped_flat} flat, {skipped_small} smaller than {args.crop}px')
    print(f'  meta_info: {args.meta}')
    if kept < 200:
        print('\nFATAL: that is not a training set. Check that --raw really contains HR')
        print('thermal images at least {0}x{0}, and that they are readable.'.format(args.crop),
              file=sys.stderr)
        return 1
    if kept < 5000:
        print('\nWARNING: under ~5k crops the GAN stage will overfit; add more sources.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
