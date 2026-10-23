#!/usr/bin/env python3
"""
Turn an exported SRVGGNetCompact ncnn model (.param + .bin) back into a BasicSR checkpoint.

The lite 32/10 model is warm-started from the trained v4 64/10, and the v4 weights already
ship inside the repo as the app's bundled ncnn model — so Colab can rebuild them from the
clone instead of needing the .pth uploaded:

    python scripts/ncnn_to_pth.py \\
        --param ../MAG160_ThermalCam/android2/app/src/main/assets/model/thermal_160x120_fp16.param \\
        --bin   ../MAG160_ThermalCam/android2/app/src/main/assets/model/thermal_160x120_fp16.bin \\
        --out   experiments/pretrained_models/thermal_v4_from_ncnn.pth

The weights come back fp16-rounded (~1e-3 relative) — irrelevant for a warm start that is
pruned and fine-tuned anyway.

.bin layout (ncnn ModelBin), in .param layer order:
    Convolution  weights: u32 tag + data — tag 0x01306B47 = fp16 (padded to 4 bytes),
                          tag 0 = fp32; then bias: num_output raw fp32 (when 5=1)
    PReLU        slopes:  num_slope raw fp32
Keys follow SRVGGNetCompact: the k-th conv is body.{2k}, the k-th PReLU body.{2k+1}.
"""

import argparse

import numpy as np
import torch

FP16_TAG = 0x01306B47


def layers(param_path: str):
    """(type, {key: value}) per layer line of an ncnn .param."""
    lines = open(param_path).read().split('\n')
    if lines[0].strip() != '7767517':
        raise SystemExit(f'{param_path}: not an ncnn .param (magic {lines[0]!r})')
    for line in lines[2:]:
        t = line.split()
        if not t:
            continue
        n_in, n_out = int(t[2]), int(t[3])
        kv = dict(x.split('=', 1) for x in t[4 + n_in + n_out:])
        yield t[0], kv


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument('--param', required=True)
    ap.add_argument('--bin', required=True)
    ap.add_argument('--out', required=True)
    a = ap.parse_args()

    buf = open(a.bin, 'rb').read()
    pos = 0

    def raw_f32(n):
        nonlocal pos
        v = np.frombuffer(buf, '<f4', n, pos).copy()
        pos += 4 * n
        return v

    sd, n_conv, n_prelu = {}, 0, 0
    for kind, kv in layers(a.param):
        if kind == 'Convolution':
            out_ch, k, size = int(kv['0']), int(kv['1']), int(kv['6'])
            k_h = int(kv.get('11', k))
            tag = int.from_bytes(buf[pos:pos + 4], 'little')
            pos += 4
            if tag == FP16_TAG:
                w = np.frombuffer(buf, '<f2', size, pos).astype(np.float32)
                pos += (2 * size + 3) // 4 * 4
            elif tag == 0:
                w = raw_f32(size)
            else:
                raise SystemExit(f'conv {n_conv}: unsupported weight tag {tag:#x} (quantized?)')
            in_ch = size // (out_ch * k * k_h)
            sd[f'body.{2 * n_conv}.weight'] = torch.from_numpy(w.reshape(out_ch, in_ch, k_h, k))
            if kv.get('5', '0') == '1':
                sd[f'body.{2 * n_conv}.bias'] = torch.from_numpy(raw_f32(out_ch))
            n_conv += 1
        elif kind == 'PReLU':
            sd[f'body.{2 * n_prelu + 1}.weight'] = torch.from_numpy(raw_f32(int(kv.get('0', 1))))
            n_prelu += 1
    if pos != len(buf):
        raise SystemExit(f'read {pos} of {len(buf)} bytes — layout not understood, not writing')
    if n_prelu != n_conv - 1:
        raise SystemExit(f'{n_conv} convs / {n_prelu} PReLUs is not an SRVGGNetCompact chain')

    first, last = sd['body.0.weight'], sd[f'body.{2 * (n_conv - 1)}.weight']
    print(f'SRVGGNetCompact {first.shape[0]}/{n_conv - 2}, {first.shape[1]}-channel in, '
          f'output conv {tuple(last.shape)}')
    torch.save({'params': sd}, a.out)
    print('wrote', a.out)


if __name__ == '__main__':
    main()
