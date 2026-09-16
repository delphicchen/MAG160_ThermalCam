#!/usr/bin/env python3
"""
Export a trained generator to TorchScript (for pnnx) and ONNX (for onnx2ncnn).

    python scripts/export_onnx.py \
        --ckpt experiments/thermal_srvgg_x4_gan/models/net_g_100000.pth \
        --arch srvgg --out export/thermal_x4

Writes export/thermal_x4.pt (traced, 1x3x120x160) and export/thermal_x4.onnx.

Fixed input size on purpose: our frame is always 160x120 (or 120x160 rotated — export
both with --size if you upscale after rotation), and a fixed shape gives ncnn the best
chance of a clean, fully static graph. No custom ops are used: SRVGGNetCompact is
conv + prelu + pixelshuffle + a bilinear/nearest skip, all natively supported.
"""

import argparse
import pathlib

import torch
from basicsr.archs.rrdbnet_arch import RRDBNet
from realesrgan.archs.srvgg_arch import SRVGGNetCompact


def build(arch: str):
    if arch == 'srvgg':
        return SRVGGNetCompact(num_in_ch=3, num_out_ch=3, num_feat=64, num_conv=16,
                               upscale=4, act_type='prelu')
    if arch == 'rrdb':
        return RRDBNet(num_in_ch=3, num_out_ch=3, scale=4, num_feat=32, num_block=12,
                       num_grow_ch=16)
    raise SystemExit(f'unknown arch {arch}')


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument('--ckpt', required=True)
    ap.add_argument('--arch', default='srvgg', choices=['srvgg', 'rrdb'])
    ap.add_argument('--out', default='export/thermal_x4')
    ap.add_argument('--size', default='160x120', help='WxH of the network input')
    ap.add_argument('--key', default='params_ema')
    args = ap.parse_args()

    w, h = (int(v) for v in args.size.lower().split('x'))
    net = build(args.arch)
    sd = torch.load(args.ckpt, map_location='cpu')
    net.load_state_dict(sd.get(args.key, sd.get('params', sd)), strict=True)
    net.eval()

    out = pathlib.Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    x = torch.rand(1, 3, h, w)

    with torch.no_grad():
        traced = torch.jit.trace(net, x)
    traced.save(f'{out}.pt')

    torch.onnx.export(
        net, x, f'{out}.onnx',
        input_names=['data'], output_names=['output'],
        opset_version=11,          # ncnn's onnx2ncnn is happiest at 11
        dynamic_axes=None,         # static shapes
        do_constant_folding=True,
    )

    # reference output for the ncnn comparison
    with torch.no_grad():
        y = net(x)
    torch.save({'input': x, 'output': y}, f'{out}_ref.pt')
    print(f'wrote {out}.pt, {out}.onnx, {out}_ref.pt   in {tuple(x.shape)} -> out {tuple(y.shape)}')


if __name__ == '__main__':
    main()
