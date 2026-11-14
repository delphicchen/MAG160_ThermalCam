#!/usr/bin/env python3
"""Fold a trained RepSRVGG into the SRVGGNetCompact the app runs — or, with --init, the
other way: a trained SRVGGNetCompact into a RepSRVGG warm start that computes exactly the
same function (sr_train/PLAN_V5.md).

    # after training: RepSRVGG checkpoint -> SRVGGNetCompact checkpoint for export_onnx.py
    python scripts/rep_fold.py --src experiments/thermal_v5_student/models/net_g_60000.pth \
        --out export/v5_student_folded.pth

    # before training: v4 (rebuilt from the app's ncnn model) -> RepSRVGG warm start
    python scripts/rep_fold.py --init --src experiments/pretrained_models/thermal_v4_from_ncnn.pth \
        --out experiments/pretrained_models/v5_rep_init.pth

Both directions check themselves: the two networks are run on the same random frames and
must agree to float precision, or the script fails.
"""
import argparse
import pathlib
import sys

import torch
from torch import nn
from torch.nn import functional as F

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from thermal_arch.thermal_v5 import RepSRVGG, fold_state_dict, unfold_state_dict  # noqa: E402


class PlainSRVGG(nn.Module):
    """SRVGGNetCompact (PReLU), as realesrgan/archs/srvgg_arch.py defines it — inlined so
    the check runs without Real-ESRGAN installed."""

    def __init__(self, num_in_ch, num_out_ch, num_feat, num_conv, upscale=4):
        super().__init__()
        self.upscale = upscale
        self.body = nn.ModuleList([nn.Conv2d(num_in_ch, num_feat, 3, 1, 1),
                                   nn.PReLU(num_parameters=num_feat)])
        for _ in range(num_conv):
            self.body.append(nn.Conv2d(num_feat, num_feat, 3, 1, 1))
            self.body.append(nn.PReLU(num_parameters=num_feat))
        self.body.append(nn.Conv2d(num_feat, num_out_ch * upscale * upscale, 3, 1, 1))
        self.upsampler = nn.PixelShuffle(upscale)

    def forward(self, x):
        out = x
        for layer in self.body:
            out = layer(out)
        return self.upsampler(out) + F.interpolate(x, scale_factor=self.upscale, mode='nearest')


def shape_of(sd: dict, rep: bool) -> dict:
    """Constructor args from either layout's weights."""
    w = '.conv3.weight' if rep else '.weight'
    convs = sorted(int(k.split('.')[1]) for k in sd
                   if k.endswith(w) and sd[k].dim() == 4)
    first, last = sd[f'body.{convs[0]}{w}'], sd[f'body.{convs[-1]}{w}']
    return dict(num_in_ch=first.shape[1], num_feat=first.shape[0],
                num_out_ch=last.shape[0] // 16, num_conv=len(convs) - 2)


def load(path: str, key: str | None) -> dict:
    ck = torch.load(path, map_location='cpu')
    if isinstance(ck, dict) and any(k in ck for k in ('params_ema', 'params')):
        key = key or next(k for k in ('params_ema', 'params') if k in ck)
        print(f'{path}: using {key}')
        return ck[key]
    return ck


@torch.no_grad()
def check(a: nn.Module, b: nn.Module, in_ch: int) -> float:
    a.eval(); b.eval()
    x = torch.rand(2, in_ch, 24, 32)
    ya, yb = a(x), b(x)
    # relative to the output's size: float32 sums of the branches round at ~1e-6 of it
    return float((ya - yb).abs().max() / ya.abs().max().clamp_min(1.0))


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--src', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--init', action='store_true', help='SRVGGNetCompact -> RepSRVGG warm start')
    ap.add_argument('--key', default=None, help='default: params_ema if present, else params')
    a = ap.parse_args()

    sd = load(a.src, a.key)
    if a.init:
        shape = shape_of(sd, rep=False)
        plain = PlainSRVGG(**shape); plain.load_state_dict(sd, strict=True)
        rep_sd = unfold_state_dict(sd)
        rep = RepSRVGG(**shape); rep.load_state_dict(rep_sd, strict=True)
        out_sd = rep_sd
    else:
        shape = shape_of(sd, rep=True)
        rep = RepSRVGG(**shape); rep.load_state_dict(sd, strict=True)
        out_sd = fold_state_dict(sd)
        plain = PlainSRVGG(**shape); plain.load_state_dict(out_sd, strict=True)

    err = check(rep, plain, shape['num_in_ch'])
    print(f'shape {shape} | max |rep - plain| (relative) = {err:.2e}')
    if err > 1e-4:
        print('FAIL: the two networks disagree — not writing', a.out)
        return 1
    pathlib.Path(a.out).parent.mkdir(parents=True, exist_ok=True)
    # both keys: BasicSR's EMA copy and export_onnx.py read params_ema, training reads params
    torch.save({'params': out_sd, 'params_ema': out_sd}, a.out)
    print('wrote', a.out)
    return 0


if __name__ == '__main__':
    sys.exit(main())
