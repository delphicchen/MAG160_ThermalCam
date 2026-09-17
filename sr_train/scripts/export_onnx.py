#!/usr/bin/env python3
"""
Export a trained generator to TorchScript (for pnnx) and ONNX (for onnx2ncnn).

    python scripts/export_onnx.py \
        --ckpt experiments/thermal_srvgg_x4_gan/models/net_g_100000.pth \
        --arch srvgg --out export/thermal_x4_160x120

Writes export/thermal_x4.pt (traced, 1x3x120x160) and export/thermal_x4.onnx.

Fixed input size on purpose: our frame is always 160x120 (or 120x160 rotated — export
both with --size if you upscale after rotation), and a fixed shape gives ncnn the best
chance of a clean, fully static graph. No custom ops are used: SRVGGNetCompact is
conv + prelu + pixelshuffle + a bilinear/nearest skip, all natively supported.
"""

import argparse
import inspect
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


def thermal_like(h: int, w: int, seed: int = 0) -> torch.Tensor:
    """A plausible display frame for tracing and the ncnn reference: gradient, warm
    objects with hard edges, soft hot spots, NETD-level noise, then the viewer's 1-99 %
    stretch. Uniform noise is a poor reference — it drives the trained net to outputs
    of -1.5..2.8, where fp16 weight rounding shows up as a spurious verify FAIL."""
    g = torch.Generator().manual_seed(seed)
    u = lambda a, b: a + (b - a) * torch.rand((), generator=g).item()
    yy, xx = torch.meshgrid(torch.arange(h, dtype=torch.float32),
                            torch.arange(w, dtype=torch.float32), indexing='ij')
    f = 20 + 3 * xx / w + 2 * yy / h
    for _ in range(4):
        x0, y0 = int(u(0, w - 30)), int(u(0, h - 30))
        f[y0:y0 + int(u(10, 40)), x0:x0 + int(u(10, 50))] += u(2, 12)
    for _ in range(3):
        cx, cy, sg = u(0, w), u(0, h), u(3, 12)
        f += u(5, 25) * torch.exp(-((xx - cx) ** 2 + (yy - cy) ** 2) / (2 * sg * sg))
    f += 0.05 * torch.randn(f.shape, generator=g)
    lo, hi = torch.quantile(f.flatten(), torch.tensor([0.01, 0.99]))
    f = ((f - lo) / (hi - lo)).clamp(0, 1)
    return f.expand(1, 3, h, w).contiguous()


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
    x = thermal_like(h, w)

    with torch.no_grad():
        traced = torch.jit.trace(net, x)
    traced.save(f'{out}.pt')

    # reference output for the ncnn comparison — written before the ONNX export, which
    # is optional (only the onnx2ncnn fallback reads it) and can fail on its own
    with torch.no_grad():
        y = net(x)
    torch.save({'input': x, 'output': y}, f'{out}_ref.pt')

    # torch >= 2.9 defaults to the dynamo exporter, which needs the onnxscript package and
    # ignores opset 11; the TorchScript exporter needs neither.
    kw = {'dynamo': False} if 'dynamo' in inspect.signature(torch.onnx.export).parameters else {}
    try:
        torch.onnx.export(
            net, x, f'{out}.onnx',
            input_names=['data'], output_names=['output'],
            opset_version=11,          # ncnn's onnx2ncnn is happiest at 11
            dynamic_axes=None,         # static shapes
            do_constant_folding=True,
            **kw,
        )
    except Exception as e:             # pnnx converts from the .pt; ONNX is only a fallback
        print(f'WARNING: ONNX export failed ({type(e).__name__}: {e}); '
              f'the pnnx route does not need it')

    print(f'wrote {out}.pt, {out}.onnx, {out}_ref.pt   in {tuple(x.shape)} -> out {tuple(y.shape)}')


if __name__ == '__main__':
    main()
