#!/usr/bin/env python3
"""
MagViewer temperature captures (.mgt) → validation frames for hallucination_check.py.

The app's "Save temperature data with captures" writes every pixel's °C (format:
android2/docs/THERMAL_CAPTURE.md). Those are real MAG160 frames in exactly the domain the
model runs on — the best check set there is:

    python scripts/mgt_to_png.py captures/*.mgt --out datasets/val_mag160
    # then copy datasets/val_mag160/*.png to Drive/thermal_sr/val_mag160/ for Colab

Frames are written as 16-bit grey PNG, min..max of each frame over 0..65535, so no
temperature detail is lost to 8 bits; hallucination_check.py then applies the app's own
1-99 % stretch. A snapshot holds one frame; from a recording every --every-th frame is
kept (default 15 ≈ one per second), at most --max per file.
"""

import argparse
import pathlib
import struct
import sys

import cv2
import numpy as np

HEADER = 32


def read_mgt(path: pathlib.Path):
    """Yield (index, t_ms, °C array HxW) for every frame in the file."""
    data = path.read_bytes()
    if len(data) < HEADER or data[:4] != b'MAGT':
        raise ValueError(f'{path.name}: not a MagViewer temperature capture')
    _ver, hdr, w, h = struct.unpack_from('<4H', data, 4)
    hdr = max(hdr, HEADER)
    declared = struct.unpack_from('<I', data, 28)[0]
    stride = 4 + w * h * 2
    avail = (len(data) - hdr) // stride            # a cut-short recording: trust the length
    n = declared if 0 < declared <= avail else avail
    for i in range(n):
        off = hdr + i * stride
        t_ms = struct.unpack_from('<I', data, off)[0]
        c = np.frombuffer(data, '<i2', w * h, off + 4).reshape(h, w) / 100.0
        yield i, t_ms, c


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('files', nargs='+', type=pathlib.Path)
    ap.add_argument('--out', type=pathlib.Path, default=pathlib.Path('datasets/val_mag160'))
    ap.add_argument('--every', type=int, default=15, help='keep every N-th frame of a recording')
    ap.add_argument('--max', type=int, default=20, help='frames per file at most')
    args = ap.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)
    written = 0
    for f in args.files:
        try:
            frames = list(read_mgt(f))
        except (ValueError, struct.error) as e:
            print(e, file=sys.stderr)
            continue
        kept = frames[::max(1, args.every)][:args.max]
        for i, t_ms, c in kept:
            lo, hi = float(c.min()), float(c.max())
            u16 = np.round((c - lo) / max(hi - lo, 1e-6) * 65535).astype(np.uint16)
            name = f.stem if len(frames) == 1 else f'{f.stem}_{i:05d}'
            cv2.imwrite(str(args.out / f'{name}.png'), u16)
            written += 1
        print(f'{f.name}: {len(frames)} frame(s) {frames[0][2].shape[1]}x{frames[0][2].shape[0]}, '
              f'kept {len(kept)}')
    print(f'wrote {written} PNG(s) to {args.out}')
    return 0 if written else 1


if __name__ == '__main__':
    sys.exit(main())
