#!/usr/bin/env python3
"""
Make a genuine warm start for a shorter (and optionally single-channel) SRVGGNetCompact
out of the 64/32 pretrained model.

`realesr-general-x4v3.pth` is num_conv=32. SRVGGNetCompact's body is

    body.0              first conv (in_ch -> 64)
    body.1              its PReLU
    body.2 … body.2n+1  n x (conv 64->64, PReLU)
    body.2n+2           output conv (64 -> out_ch·16), then PixelShuffle(4)

so the published model's output conv is body.66, and a num_conv=n network's is body.2n+2.
A plain load into a shorter network keeps the leading layers and drops the rest —
including the output conv, the one layer you least want random. This remaps it:

    body.0 … body.2n+1   copied as-is          (first conv + n body convs, with PReLUs)
    body.2n+2            <- their body.66      (the real output conv)

(Before 2026-09-24 the target index was computed as 2n instead of 2n+2: the last body
pair was dropped and the output conv landed on a body conv's slot, where its shape did
not fit — BasicSR's non-strict load then ignored it and the output conv started random.)

Single channel (--in-ch 1 --out-ch 1): the thermal field is grey, and the 3-channel model
only ever saw it replicated into R=G=B, with the app averaging the three outputs. So:

    first conv   sum its weights over the 3 input channels   — conv(x,x,x) == conv'(x)
    output conv  average each R/G/B group of 16 sub-pixel filters (and biases)
                 — PixelShuffle is a per-channel permutation, the skip is the same
                   nearest-upsampled grey in every channel, and all of it is linear

The 1-channel network then computes exactly the mean of the 3-channel one's outputs on a
grey input: a lossless starting point, not a re-init.

    python scripts/truncate_pretrained.py \
        --src experiments/pretrained_models/realesr-general-x4v3.pth \
        --num-conv 8 --in-ch 1 --out-ch 1 \
        --out experiments/pretrained_models/realesr-general-x4v3-conv8-grey.pth

Use --num-conv 32 to keep the full depth (no truncation, ~4x the 64/8 inference cost).

Narrower (--num-feat 32): the lite model for the Tiny2C. Body compute goes with num_feat²,
so 32 channels cost ~¼ of 64. No 32-channel weights are published, so the source is pruned:
layer by layer, keep the num_feat filters with the largest L1 norm (Li et al. 2017, "Pruning
Filters for Efficient ConvNets"), slice the matching PReLU slopes, and cut the next conv's
input channels to the kept set. Unlike the depth cut this is not lossless — it is a warm
start that fine-tuning recovers from, far closer than a random init. Prune the trained
thermal model (v4 64/10, already 1-channel) rather than the published photo model:

    python scripts/truncate_pretrained.py \
        --src experiments/pretrained_models/thermal_v4_net_g_80000.pth \
        --num-conv 10 --num-feat 32 --in-ch 1 --out-ch 1 \
        --out experiments/pretrained_models/thermal-v4-lite32-grey.pth

The key defaults to params_ema when the checkpoint has one (BasicSR's EMA weights — what
export_onnx.py ships), else params.
"""

import argparse

import torch

UPSCALE = 4          # realesr-general-x4v3; the output conv holds out_ch · 4² filters


def to_single_channel(sd: dict, first: str, last: str) -> None:
    """In place: 3-in/3-out SRVGGNetCompact weights → 1-in/1-out (see module doc)."""
    w0 = sd[f'{first}.weight']                                   # (64, 3, 3, 3)
    sd[f'{first}.weight'] = w0.sum(dim=1, keepdim=True)
    r2 = UPSCALE * UPSCALE
    wl = sd[f'{last}.weight']                                    # (3·16, 64, 3, 3)
    sd[f'{last}.weight'] = wl.reshape(3, r2, *wl.shape[1:]).mean(dim=0)
    if f'{last}.bias' in sd:
        sd[f'{last}.bias'] = sd[f'{last}.bias'].reshape(3, r2).mean(dim=0)


def prune_width(sd: dict, num_feat: int, last: str) -> None:
    """In place: keep the num_feat strongest filters of every conv before [last] (see
    module doc). [last] is the output conv: only its input channels are cut."""
    convs = sorted(int(k.split('.')[1]) for k, v in sd.items()
                   if k.startswith('body.') and k.endswith('.weight') and v.dim() == 4)
    keep = None
    for i in convs:
        w = sd[f'body.{i}.weight']
        if keep is not None:
            w = w[:, keep]                       # inputs = the previous layer's kept filters
        if f'body.{i}' == last:
            sd[f'body.{i}.weight'] = w
            break
        keep = w.abs().sum(dim=(1, 2, 3)).topk(num_feat).indices.sort().values
        sd[f'body.{i}.weight'] = w[keep]
        sd[f'body.{i}.bias'] = sd[f'body.{i}.bias'][keep]
        slope = f'body.{i + 1}.weight'           # PReLU, one slope per channel
        if slope in sd and sd[slope].numel() > 1:
            sd[slope] = sd[slope][keep]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument('--src', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--num-conv', type=int, default=16)
    ap.add_argument('--num-feat', type=int, default=None,
                    help='narrower body (prune by filter L1 norm); default = the source width')
    ap.add_argument('--in-ch', type=int, default=3, choices=[1, 3])
    ap.add_argument('--out-ch', type=int, default=3, choices=[1, 3])
    ap.add_argument('--key', default=None, help='default: params_ema if present, else params')
    args = ap.parse_args()
    if args.in_ch != args.out_ch:
        raise SystemExit('--in-ch and --out-ch must match (both 1 or both 3)')

    ck = torch.load(args.src, map_location='cpu')
    key = args.key or next((k for k in ('params_ema', 'params') if k in ck), None)
    sd = ck[key] if key else ck
    print('source key:', key or '(top level)')

    src_idx = max(int(k.split('.')[1]) for k in sd if k.startswith('body.'))   # 66
    dst_idx = 2 * args.num_conv + 2
    if dst_idx > src_idx:
        raise SystemExit(f'source has num_conv={(src_idx - 2) // 2}, '
                         f'cannot grow to {args.num_conv}')

    out = {k: v for k, v in sd.items()
           if k.startswith('body.') and int(k.split('.')[1]) < dst_idx}
    for suffix in ('weight', 'bias'):
        s, d = f'body.{src_idx}.{suffix}', f'body.{dst_idx}.{suffix}'
        if s in sd:
            out[d] = sd[s]
    for k, v in sd.items():                      # anything outside body.*
        if not k.startswith('body.'):
            out[k] = v

    if args.in_ch == 1 and out['body.0.weight'].shape[1] == 3:   # already grey: nothing to fold
        to_single_channel(out, 'body.0', f'body.{dst_idx}')

    src_feat = out['body.0.weight'].shape[0]
    if args.num_feat is not None and args.num_feat != src_feat:
        if args.num_feat > src_feat:
            raise SystemExit(f'source has num_feat={src_feat}, cannot grow to {args.num_feat}')
        prune_width(out, args.num_feat, f'body.{dst_idx}')
        print(f'pruned num_feat {src_feat} -> {args.num_feat} (largest-L1 filters per layer)')

    kept = len([k for k in out if k.endswith('.weight')])
    print(f'source output conv: body.{src_idx} -> target body.{dst_idx}')
    print(f'kept {len(out)} tensors ({kept} weights) for '
          f'{out["body.0.weight"].shape[0]}/{args.num_conv}, {args.in_ch}-channel in/out')
    print(f'first conv {tuple(out["body.0.weight"].shape)}, '
          f'output conv {tuple(out[f"body.{dst_idx}.weight"].shape)}')
    torch.save({'params': out}, args.out)
    print('wrote', args.out)


if __name__ == '__main__':
    main()
