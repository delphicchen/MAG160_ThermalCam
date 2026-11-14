#!/usr/bin/env python3
"""PSNR of several checkpoints on held-out HR thermal frames, 4× down and back up
(sr_train/PLAN_V5.md, step 6). The go / no-go number next to hallucination_check.py.

Each HR frame (grey, e.g. CIDIS val 640×448) is area-downsampled 4× to the input; the
model's 4× output is compared with the HR frame. Bicubic is listed as the floor.

    python scripts/eval_psnr.py --hr /content/cidis/dataset/thermal/val \
        --model v4=srvgg:experiments/pretrained_models/thermal_v4_from_ncnn.pth \
        --model teacher=rrdb:experiments/thermal_v5_teacher/models/net_g_30000.pth \
        --model v5=srvgg:export/v5_student_folded.pth

Synthetic downsampling is not the sensor, so this ranks models; it does not predict the
picture on a real camera — that is what hallucination_check.py on real frames is for.
"""
import argparse
import csv
import pathlib
import sys

import cv2
import numpy as np
import torch


def build(arch: str, sd: dict) -> torch.nn.Module:
    if arch == 'srvgg':
        from realesrgan.archs.srvgg_arch import SRVGGNetCompact
        convs = sorted(int(k.split('.')[1]) for k in sd if k.endswith('.weight') and sd[k].dim() == 4)
        first, last = sd[f'body.{convs[0]}.weight'], sd[f'body.{convs[-1]}.weight']
        return SRVGGNetCompact(num_in_ch=first.shape[1], num_out_ch=last.shape[0] // 16,
                               num_feat=first.shape[0], num_conv=len(convs) - 2,
                               upscale=4, act_type='prelu')
    if arch == 'rrdb':
        from basicsr.archs.rrdbnet_arch import RRDBNet
        blocks = len({k.split('.')[1] for k in sd if k.startswith('body.')})
        return RRDBNet(num_in_ch=sd['conv_first.weight'].shape[1],
                       num_out_ch=sd['conv_last.weight'].shape[0],
                       num_feat=sd['conv_first.weight'].shape[0], num_block=blocks,
                       num_grow_ch=sd['body.0.rdb1.conv1.weight'].shape[0], scale=4)
    raise SystemExit(f'unknown arch {arch} (srvgg or rrdb)')


def load(spec: str, dev: torch.device):
    arch, path = spec.split(':', 1)
    ck = torch.load(path, map_location='cpu')
    sd = ck[next(k for k in ('params_ema', 'params') if k in ck)] if isinstance(ck, dict) and \
        any(k in ck for k in ('params_ema', 'params')) else ck
    net = build(arch, sd)
    net.load_state_dict(sd, strict=True)
    in_ch = next(v for k, v in sd.items() if v.dim() == 4).shape[1]
    return net.eval().to(dev), in_ch


def psnr(a: np.ndarray, b: np.ndarray) -> float:
    mse = float(np.mean((a - b) ** 2))
    return 99.0 if mse == 0 else 10 * np.log10(1.0 / mse)


@torch.no_grad()
def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--hr', required=True, help='folder of HR grey frames (bmp/png/jpg)')
    ap.add_argument('--model', action='append', default=[], help='name=arch:checkpoint')
    ap.add_argument('--limit', type=int, default=200)
    ap.add_argument('--csv', default=None)
    a = ap.parse_args()

    files = sorted(p for p in pathlib.Path(a.hr).iterdir()
                   if p.suffix.lower() in ('.bmp', '.png', '.jpg', '.jpeg'))[:a.limit]
    if not files:
        print('no frames in', a.hr)
        return 1
    dev = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
    models = {}
    for m in a.model:
        name, spec = m.split('=', 1)
        models[name] = load(spec, dev)

    scores = {k: [] for k in ['bicubic', *models]}
    for f in files:
        hr = cv2.imread(str(f), cv2.IMREAD_GRAYSCALE)
        h, w = (hr.shape[0] // 4) * 4, (hr.shape[1] // 4) * 4
        hr = hr[:h, :w].astype(np.float32) / 255.0
        lr = cv2.resize(hr, (w // 4, h // 4), interpolation=cv2.INTER_AREA)
        scores['bicubic'].append(psnr(np.clip(cv2.resize(lr, (w, h), interpolation=cv2.INTER_CUBIC), 0, 1), hr))
        for name, (net, in_ch) in models.items():
            x = torch.from_numpy(lr)[None, None].repeat(1, in_ch, 1, 1).to(dev)
            y = net(x).clamp(0, 1).mean(dim=1)[0].cpu().numpy()
            scores[name].append(psnr(y, hr))

    print(f'{len(files)} frames from {a.hr}')
    base = np.mean(scores['bicubic'])
    for name, s in scores.items():
        print(f'  {name:10s} {np.mean(s):6.2f} dB   ({np.mean(s) - base:+.2f} vs bicubic)')
    if a.csv:
        with open(a.csv, 'w', newline='') as fh:
            wr = csv.writer(fh)
            wr.writerow(['model', 'psnr_db', 'frames'])
            for name, s in scores.items():
                wr.writerow([name, f'{np.mean(s):.3f}', len(s)])
    return 0


if __name__ == '__main__':
    sys.exit(main())
