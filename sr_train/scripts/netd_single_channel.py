#!/usr/bin/env python3
"""
Single-channel warm start for the stage-2 discriminator.

Stage 2 initialises its UNetDiscriminatorSN from Real-ESRGAN's RealESRGAN_x4plus_netD.pth,
which takes RGB. With a 1-channel generator the discriminator sees 1-channel images; a
non-strict load would drop the mismatched first conv and leave it random. Its input is
grey, so summing that conv over the three input channels gives exactly the response the
RGB discriminator had to R=G=B — the same trick as scripts/truncate_pretrained.py.

    python scripts/netd_single_channel.py \
        --src experiments/pretrained_models/RealESRGAN_x4plus_netD.pth \
        --out experiments/pretrained_models/RealESRGAN_x4plus_netD-grey.pth
"""

import argparse

import torch


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument('--src', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--key', default='params')
    ap.add_argument('--first', default='conv0', help='name of the input conv')
    args = ap.parse_args()

    ck = torch.load(args.src, map_location='cpu')
    sd = dict(ck.get(args.key, ck))
    k = f'{args.first}.weight'
    w = sd[k]
    if w.shape[1] != 3:
        raise SystemExit(f'{k} has {w.shape[1]} input channels, expected 3')
    sd[k] = w.sum(dim=1, keepdim=True)
    print(f'{k}: {tuple(w.shape)} -> {tuple(sd[k].shape)}')
    torch.save({'params': sd}, args.out)
    print('wrote', args.out)


if __name__ == '__main__':
    main()
