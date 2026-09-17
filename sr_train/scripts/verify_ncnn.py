#!/usr/bin/env python3
"""
Check that the ncnn model computes what PyTorch computed.

    python scripts/verify_ncnn.py --ref export/thermal_x4_160x120_ref.pt \
        --param export/thermal_160x120_fp16.param --bin export/thermal_160x120_fp16.bin

Feeds the exact tensor that produced the reference output and reports max / mean
absolute difference in 0..1 units and in 8-bit levels.

What counts as passing, and why:

  fp32 ncnn vs PyTorch     max diff < 1e-4   (~0.03 of an 8-bit level)
      Same maths, different kernel order. Anything larger means a converted op behaves
      differently — a real bug, not precision.

  fp16 ncnn vs PyTorch     max diff < 6e-3   (~1.5 levels), mean < 1e-3
      fp16 carries ~10 bits of mantissa, so each accumulation step rounds at ~5e-4
      relative. Errors grow with depth (16 convs here) and with activation magnitude,
      which is why the max lands near 1-2 levels while the mean stays far below one.
      Vulkan may also use fp16 *storage* with fp32 accumulate depending on the device,
      so the same model can be slightly more accurate on one GPU than another.

  A difference you can see as banding in flat regions is not precision — check that the
  input normalisation matches (the same percentile stretch on both sides) before blaming
  fp16.
"""

import argparse
import sys

import numpy as np
import torch

try:
    import ncnn
except ImportError:
    print('pip install ncnn', file=sys.stderr)
    raise


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--ref', required=True, help='*_ref.pt from export_onnx.py')
    ap.add_argument('--param', required=True)
    ap.add_argument('--bin', required=True)
    ap.add_argument('--vulkan', action='store_true', help='run on the GPU backend')
    ap.add_argument('--tol-max', type=float, default=6e-3)
    ap.add_argument('--tol-mean', type=float, default=1e-3)
    args = ap.parse_args()

    ref = torch.load(args.ref, map_location='cpu')
    x = ref['input'][0].numpy()          # 3xHxW, 0..1
    y_torch = ref['output'][0].numpy()   # 3x4Hx4W

    net = ncnn.Net()
    net.opt.use_vulkan_compute = args.vulkan
    net.load_param(args.param)
    net.load_model(args.bin)

    # a 3-D numpy array maps to ncnn.Mat as (c, h, w) — pass CHW as-is; an HWC array
    # would be read as 120 channels of 160x3
    mat_in = ncnn.Mat(np.ascontiguousarray(x))
    ex = net.create_extractor()                      # not a context manager in all builds
    ex.input('data', mat_in)
    ret, mat_out = ex.extract('output')
    if ret != 0:
        print(f'ncnn extract failed: {ret}', file=sys.stderr)
        return 1

    y_ncnn = np.array(mat_out)           # CxHxW
    if y_ncnn.shape != y_torch.shape:
        print(f'shape mismatch: ncnn {y_ncnn.shape} vs torch {y_torch.shape}',
              file=sys.stderr)
        return 1

    d = np.abs(y_ncnn - y_torch)
    mx, mean = float(d.max()), float(d.mean())
    print(f'backend      : {"vulkan" if args.vulkan else "cpu"}')
    print(f'output shape : {y_ncnn.shape}')
    print(f'max  |diff|  : {mx:.3e}   ({mx * 255:.2f} of an 8-bit level)')
    print(f'mean |diff|  : {mean:.3e}   ({mean * 255:.3f} levels)')
    print(f'range torch  : {y_torch.min():.3f} .. {y_torch.max():.3f}')
    print(f'range ncnn   : {y_ncnn.min():.3f} .. {y_ncnn.max():.3f}')

    ok = mx < args.tol_max and mean < args.tol_mean
    print('\nPASS' if ok else '\nFAIL — see the tolerance notes at the top of this file')
    return 0 if ok else 2


if __name__ == '__main__':
    raise SystemExit(main())
