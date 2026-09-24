#!/usr/bin/env python3
"""
Fake-hot-spot check: does the network invent temperature that is not in the data?

This is the validation that matters for a thermal camera. PSNR against a synthetic pair
says nothing about the failure mode we care about — a GAN-trained upscaler drawing a
bright blob where the sensor saw noise, which a user reads as a hot component.

Run it on real MAG160Core captures (the 20 held-out frames) after every checkpoint:

    python scripts/hallucination_check.py --ckpt experiments/.../net_g_50000.pth \
        --lr datasets/val_mag160 --out results/val_50k

Three measurements, cheap and blunt on purpose:

1. **Energy preservation.** Box-average the 4x output back down to the input grid. A
   faithful upscaler reproduces the input almost exactly (it may sharpen *within* a
   pixel, but the mean of each 4x4 block is the measurement). Reported as mean and max
   absolute error in 8-bit levels. Drift here means the whole image is being re-lit.

2. **Invented local extrema.** Find local maxima in the output that (a) exceed the
   bicubic reference at the same place by more than --delta and (b) have no
   corresponding local maximum in the input neighbourhood. Those are new hot spots — the
   count per frame is the number to watch across checkpoints.

3. **Range inflation.** How far the output's min/max exceed the input's. A network that
   pushes peaks 10 levels brighter will make every warm object look hotter than it is.

Outputs `<out>/<name>_check.png` (input, bicubic, model, marked spots) and a summary CSV.
Treat the numbers as a *comparison between checkpoints*, not as an absolute pass mark.
"""

import argparse
import csv
import pathlib
import sys

import cv2
import numpy as np
import torch
from scipy.ndimage import maximum_filter

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from export_onnx import load_generator  # noqa: E402  (sibling script, not a package)


def load_lr(p: pathlib.Path) -> np.ndarray:
    """Return HxW float 0..1 (percentile-stretched, as the viewer displays it)."""
    if p.suffix == '.npy':
        img = np.load(p).astype(np.float32)
    else:
        img = cv2.imread(str(p), cv2.IMREAD_UNCHANGED).astype(np.float32)
        if img.ndim == 3:
            img = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    lo, hi = np.percentile(img, [1, 99])
    return np.clip((img - lo) / max(hi - lo, 1e-6), 0, 1)


def analyse(lr: np.ndarray, sr: np.ndarray, delta: float, nbhd: int):
    h, w = lr.shape
    bicubic = cv2.resize(lr, (w * 4, h * 4), interpolation=cv2.INTER_CUBIC)

    # 1. energy preservation
    back = sr.reshape(h, 4, w, 4).mean(axis=(1, 3))
    err = np.abs(back - lr)

    # 2. invented local maxima
    sr_peaks = (sr == maximum_filter(sr, size=nbhd)) & (sr > bicubic + delta)
    lr_peak_map = (lr == maximum_filter(lr, size=3))
    lr_peaks_up = cv2.resize(lr_peak_map.astype(np.uint8), (w * 4, h * 4),
                             interpolation=cv2.INTER_NEAREST)
    # dilate the input peaks so a genuine peak that moved a pixel or two still counts
    lr_peaks_up = cv2.dilate(lr_peaks_up, np.ones((nbhd * 2 + 1,) * 2, np.uint8))
    fake = sr_peaks & (lr_peaks_up == 0)

    n, labels = cv2.connectedComponents(fake.astype(np.uint8))
    return {
        'bicubic': bicubic,
        'energy_mean_levels': float(err.mean() * 255),
        'energy_max_levels': float(err.max() * 255),
        'fake_spots': int(n - 1),
        'fake_max_excess_levels': float(((sr - bicubic)[fake].max() * 255) if fake.any() else 0.0),
        'range_inflation_hi': float((sr.max() - lr.max()) * 255),
        'range_inflation_lo': float((lr.min() - sr.min()) * 255),
        'mask': fake,
    }


def panel(lr, bicubic, sr, mask) -> np.ndarray:
    def u8(x):
        return cv2.applyColorMap((np.clip(x, 0, 1) * 255).astype(np.uint8), cv2.COLORMAP_INFERNO)
    h, w = lr.shape
    a = cv2.resize(u8(lr), (w * 4, h * 4), interpolation=cv2.INTER_NEAREST)
    b = u8(bicubic)
    c = u8(sr)
    d = c.copy()
    ys, xs = np.nonzero(mask)
    for y, x in zip(ys, xs):
        cv2.circle(d, (int(x), int(y)), 9, (255, 255, 255), 1)
    for img, label in ((a, 'input x4 nearest'), (b, 'bicubic'), (c, 'model'), (d, 'invented peaks')):
        cv2.putText(img, label, (8, 22), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (255, 255, 255), 1)
    return np.hstack([a, b, c, d])


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--ckpt', required=True)
    ap.add_argument('--arch', default='srvgg', choices=['srvgg', 'rrdb'])
    ap.add_argument('--key', default='params_ema')
    ap.add_argument('--lr', default='datasets/val_mag160')
    ap.add_argument('--out', default='results/val')
    ap.add_argument('--delta', type=float, default=0.03,
                    help='how far above bicubic counts as a new peak (fraction of FS)')
    ap.add_argument('--nbhd', type=int, default=7, help='local-max window in output pixels')
    ap.add_argument('--device', default='cuda' if torch.cuda.is_available() else 'cpu')
    args = ap.parse_args()

    net, in_ch = load_generator(args.ckpt, args.arch, args.key)   # 1- or 3-channel
    net = net.to(args.device)

    out = pathlib.Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    files = sorted(p for p in pathlib.Path(args.lr).iterdir()
                   if p.suffix.lower() in {'.png', '.npy', '.tif', '.tiff', '.jpg'})
    if not files:
        print(f'no frames in {args.lr}', file=sys.stderr)
        return 1

    rows = []
    for f in files:
        lr = load_lr(f)
        x = torch.from_numpy(np.repeat(lr[None], in_ch, 0)[None]).float().to(args.device)
        with torch.no_grad():
            y = net(x)[0].clamp(0, 1).mean(0).cpu().numpy()   # back to one channel
        r = analyse(lr, y, args.delta, args.nbhd)
        cv2.imwrite(str(out / f'{f.stem}_check.png'), panel(lr, r['bicubic'], y, r['mask']))
        rows.append({'frame': f.name, **{k: v for k, v in r.items()
                                         if k not in ('bicubic', 'mask')}})
        print(f"{f.name:28s} spots={r['fake_spots']:3d} "
              f"energy(mean/max)={r['energy_mean_levels']:.2f}/{r['energy_max_levels']:.2f} "
              f"range+{r['range_inflation_hi']:.1f}")

    csv_path = out / 'summary.csv'
    with csv_path.open('w', newline='') as fh:
        wtr = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
        wtr.writeheader()
        wtr.writerows(rows)

    n = len(rows)
    print('\n---- mean over %d frames ----' % n)
    for k in ('fake_spots', 'energy_mean_levels', 'energy_max_levels', 'range_inflation_hi'):
        print(f'{k:24s} {sum(r[k] for r in rows) / n:.3f}')
    print(f'\npanels + {csv_path}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
