#!/usr/bin/env python3
"""
Make a genuine warm start for SRVGGNetCompact 64/16 out of the 64/32 pretrained model.

`realesr-general-x4v3.pth` is num_conv=32 (body.0 … body.66). Our recipe runs num_conv=16
for speed, so BasicSR keeps body.0…33 and throws the rest away — including the final conv,
whose shape differs (ours 64→48, theirs at that index 64→64). The output layer then starts
random, which is the one layer you least want random.

This remaps the pretrained weights onto the shorter network:

    body.0 … body.33   copied as-is          (the first 17 convs and their PReLUs)
    body.34            <- their body.66      (the real output conv, 64->48, same shape)

    python scripts/truncate_pretrained.py \
        --src experiments/pretrained_models/realesr-general-x4v3.pth \
        --num-conv 16 \
        --out experiments/pretrained_models/realesr-general-x4v3-conv16.pth

Use --num-conv 32 to keep the full model instead (no truncation, full init, ~2x the
inference cost — see the table in README.md).
"""

import argparse

import torch


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument('--src', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--num-conv', type=int, default=16)
    ap.add_argument('--key', default='params')
    args = ap.parse_args()

    ck = torch.load(args.src, map_location='cpu')
    sd = ck.get(args.key, ck)

    # body layout: conv, prelu, conv, prelu, … , final conv
    # index of the final conv in the source, and in the target
    src_idx = max(int(k.split('.')[1]) for k in sd if k.startswith('body.'))
    dst_idx = args.num_conv * 2
    if dst_idx > src_idx:
        raise SystemExit(f'source has num_conv={src_idx // 2}, cannot grow to {args.num_conv}')

    out = {k: v for k, v in sd.items()
           if k.startswith('body.') and int(k.split('.')[1]) < dst_idx}
    for suffix in ('weight', 'bias'):
        s, d = f'body.{src_idx}.{suffix}', f'body.{dst_idx}.{suffix}'
        if s in sd:
            out[d] = sd[s]
    for k, v in sd.items():                      # anything outside body.*
        if not k.startswith('body.'):
            out[k] = v

    kept = len([k for k in out if k.endswith('.weight')])
    print(f'source final conv: body.{src_idx} -> target body.{dst_idx}')
    print(f'kept {len(out)} tensors ({kept} weights) for num_conv={args.num_conv}')
    torch.save({'params': out}, args.out)
    print('wrote', args.out)


if __name__ == '__main__':
    main()
