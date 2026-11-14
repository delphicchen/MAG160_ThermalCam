#!/usr/bin/env python3
"""RealESRNet_x4plus (RRDBNet 64/23, 3-channel) → a 1-channel teacher warm start
(sr_train/PLAN_V5.md).

RealESRNet_x4plus is Real-ESRGAN's L1-only stage — trained for fidelity, without the GAN
that invents texture — so it is the right start for a teacher whose output the student
will copy. Fed a grey frame as R=G=B, conv_first sees the same pixel three times: summing
its input-channel weights gives the identical response from one channel. conv_last's
three outputs are averaged into one. The grey net therefore starts as exactly the mean of
the RGB net's outputs, as truncate_pretrained.py does for the student.

    python scripts/grey_teacher_init.py \
        --src experiments/pretrained_models/RealESRNet_x4plus.pth \
        --out experiments/pretrained_models/RealESRNet_x4plus-grey.pth
"""
import argparse
import pathlib

import torch


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument('--src', required=True)
    ap.add_argument('--out', required=True)
    a = ap.parse_args()

    ck = torch.load(a.src, map_location='cpu')
    key = next(k for k in ('params_ema', 'params') if k in ck)
    sd = {k: v.clone() for k, v in ck[key].items()}
    w = sd['conv_first.weight']
    assert w.shape[1] == 3, f'conv_first already has {w.shape[1]} input channels'
    sd['conv_first.weight'] = w.sum(dim=1, keepdim=True)
    sd['conv_last.weight'] = sd['conv_last.weight'].mean(dim=0, keepdim=True)
    sd['conv_last.bias'] = sd['conv_last.bias'].mean(dim=0, keepdim=True)
    blocks = len({k.split('.')[1] for k in sd if k.startswith('body.')})
    print(f'{a.src} [{key}] -> 1-channel RRDBNet {sd["conv_first.weight"].shape[0]}/{blocks}')
    pathlib.Path(a.out).parent.mkdir(parents=True, exist_ok=True)
    torch.save({'params': sd, 'params_ema': sd}, a.out)
    print('wrote', a.out)


if __name__ == '__main__':
    main()
