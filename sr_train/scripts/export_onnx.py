#!/usr/bin/env python3
"""
Export a trained generator to TorchScript (for pnnx) and ONNX (for onnx2ncnn).

    python scripts/export_onnx.py \
        --ckpt experiments/thermal_srvgg_x4_gan/models/net_g_100000.pth \
        --arch srvgg --out export/thermal_x4_160x120

Writes export/thermal_x4.pt (traced, 1xCx120x160), export/thermal_x4.onnx, the reference
pair export/thermal_x4_ref.pt, and export/thermal_x4_inputshape.txt for convert_ncnn.sh.

The SRVGGNetCompact shape (num_conv, 1- or 3-channel) is read from the checkpoint, so the
same command exports the first release's 3-channel 64/16 and the 1-channel 64/8.

Fixed input size on purpose: our frame is always 160x120 (or 120x160 rotated — export
both with --size if you upscale after rotation), and a fixed shape gives ncnn the best
chance of a clean, fully static graph. No custom ops are used: SRVGGNetCompact is
conv + prelu + pixelshuffle + a bilinear/nearest skip, all natively supported. The
exported graph ends in clamp(0, 1): the app clamps anyway, and a bounded output keeps an
INT8 quantisation's range fixed.
"""

import argparse
import inspect
import pathlib

import torch
from basicsr.archs.rrdbnet_arch import RRDBNet
from realesrgan.archs.srvgg_arch import SRVGGNetCompact

UPSCALE = 4


def srvgg_shape(sd: dict) -> dict:
    """SRVGGNetCompact constructor args from its weights: body.0 is the first conv
    (num_feat x in_ch), body.2n+2 the output conv (out_ch·16 filters)."""
    last = max(int(k.split('.')[1]) for k in sd if k.startswith('body.'))
    w0 = sd['body.0.weight']
    return dict(num_in_ch=w0.shape[1], num_feat=w0.shape[0], num_conv=(last - 2) // 2,
                num_out_ch=sd[f'body.{last}.weight'].shape[0] // (UPSCALE * UPSCALE))


def build(arch: str, sd: dict | None = None):
    if arch == 'srvgg':
        shape = srvgg_shape(sd) if sd is not None else \
            dict(num_in_ch=3, num_out_ch=3, num_feat=64, num_conv=16)
        return SRVGGNetCompact(**shape, upscale=UPSCALE, act_type='prelu')
    if arch == 'rrdb':
        return RRDBNet(num_in_ch=3, num_out_ch=3, scale=4, num_feat=32, num_block=12,
                       num_grow_ch=16)
    raise SystemExit(f'unknown arch {arch}')


def load_generator(ckpt: str, arch: str, key: str = 'params_ema'):
    """(net in eval mode, its input channel count) from a BasicSR checkpoint."""
    raw = torch.load(ckpt, map_location='cpu')
    sd = raw.get(key, raw.get('params', raw))
    net = build(arch, sd)
    net.load_state_dict(sd, strict=True)
    in_ch = sd['body.0.weight'].shape[1] if arch == 'srvgg' else 3
    return net.eval(), in_ch


class Clamped(torch.nn.Module):
    """The generator with its output bounded to the display range."""

    def __init__(self, net: torch.nn.Module):
        super().__init__()
        self.net = net

    def forward(self, x):
        return self.net(x).clamp(0, 1)


def thermal_like(h: int, w: int, seed: int = 0, ch: int = 3) -> torch.Tensor:
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
    return f.expand(1, ch, h, w).contiguous()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument('--ckpt', required=True)
    ap.add_argument('--arch', default='srvgg', choices=['srvgg', 'rrdb'])
    ap.add_argument('--out', default='export/thermal_x4')
    ap.add_argument('--size', default='160x120', help='WxH of the network input')
    ap.add_argument('--key', default='params_ema')
    args = ap.parse_args()

    w, h = (int(v) for v in args.size.lower().split('x'))
    gen, in_ch = load_generator(args.ckpt, args.arch, args.key)
    net = Clamped(gen).eval()

    out = pathlib.Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    x = thermal_like(h, w, ch=in_ch)
    # convert_ncnn.sh reads this, so pnnx gets the right channel count
    pathlib.Path(f'{out}_inputshape.txt').write_text(f'[1,{in_ch},{h},{w}]\n')

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
