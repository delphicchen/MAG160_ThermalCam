#!/usr/bin/env python3
"""
Depth Anything V2 Metric Small -> ncnn (fp16), for the MagViewer "depth" distance source.

    conda activate depth
    python depth_export/export_depth_ncnn.py                 # indoor (Hypersim, max 20 m)
    python depth_export/export_depth_ncnn.py --outdoor       # outdoor (VKITTI, max 80 m)

Writes depth_export/out/depth_metric_<W>x<H>_fp16.{param,bin} for each input size plus
depth_metric_ncnn.zip (the package the app imports). Blobs are named data / output like
the SR models. Input: 1x3xHxW, RGB already ImageNet-normalized (the app does that);
output: 1x1xHxW depth in metres.

The graph has a static input size, so one model per orientation: 266x196 for a
landscape (unrotated) visible frame, 196x266 for portrait. Both are multiples of the
ViT patch (14).

Needs: torch (CPU is fine), pnnx, ncnn (python, for the parity check), numpy,
huggingface_hub, git. See README.md next to this script.
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
import time
import types
import zipfile

import numpy as np
import torch

HERE = os.path.dirname(os.path.abspath(__file__))
WORK = os.path.join(HERE, 'work')
OUT = os.path.join(HERE, 'out')
REPO = os.path.join(WORK, 'Depth-Anything-V2')
REPO_URL = 'https://github.com/DepthAnything/Depth-Anything-V2'

CKPT = {
    False: ('depth-anything/Depth-Anything-V2-Metric-Hypersim-Small',
            'depth_anything_v2_metric_hypersim_vits.pth', 20.0),
    True: ('depth-anything/Depth-Anything-V2-Metric-VKITTI-Small',
           'depth_anything_v2_metric_vkitti_vits.pth', 80.0),
}
VITS = {'encoder': 'vits', 'features': 64, 'out_channels': [48, 96, 192, 384]}


def load_model(outdoor: bool) -> torch.nn.Module:
    if not os.path.isdir(REPO):
        os.makedirs(WORK, exist_ok=True)
        subprocess.run(['git', 'clone', '--depth', '1', REPO_URL, REPO], check=True)
    sys.path.insert(0, os.path.join(REPO, 'metric_depth'))
    from depth_anything_v2.dpt import DepthAnythingV2  # noqa: E402
    from huggingface_hub import hf_hub_download

    repo_id, fname, max_depth = CKPT[outdoor]
    path = hf_hub_download(repo_id, fname, cache_dir=os.path.join(WORK, 'hf'))
    model = DepthAnythingV2(**VITS, max_depth=max_depth)
    model.load_state_dict(torch.load(path, map_location='cpu'))
    return model.eval()


def _attention_forward(self, x, attn_bias=None):
    """DINOv2 attention as one nn.MultiheadAttention, which pnnx turns into ncnn's
    MultiHeadAttention layer. The original 5-D qkv reshape/permute(2,0,3,1,4) moves the
    q/k/v axis across the batch dim (ncnn cannot express it), and a hand-written
    q@k / attn@v converts to MatMul, which has no Vulkan kernel in ncnn — every block
    then round-trips GPU→CPU→GPU (~2 s per frame on the phone)."""
    return self.mha(x, x, x, need_weights=False)[0]


def _to_mha(attn) -> torch.nn.MultiheadAttention:
    """Same weights: qkv rows are [q; k; v] (the original reshape splits them in that
    order), scale head_dim^-0.5 is what MultiheadAttention applies itself."""
    c = attn.qkv.in_features
    mha = torch.nn.MultiheadAttention(c, attn.num_heads, bias=True, batch_first=True)
    with torch.no_grad():
        mha.in_proj_weight.copy_(attn.qkv.weight)
        mha.in_proj_bias.copy_(attn.qkv.bias)
        mha.out_proj.weight.copy_(attn.proj.weight)
        mha.out_proj.bias.copy_(attn.proj.bias)
    return mha.eval()


class Wrapped(torch.nn.Module):
    """Fixed-size graph: the DINOv2 position embedding is pre-interpolated to this input
    size and baked in as a constant, so the bicubic resize of it never reaches pnnx."""

    def __init__(self, model, w, h):
        super().__init__()
        self.model = model
        vit = model.pretrained
        with torch.no_grad():
            dummy = torch.zeros(1, 1 + (w // 14) * (h // 14), vit.embed_dim)
            pos = vit.interpolate_pos_encoding(dummy, w, h).clone()
        vit.interpolate_pos_encoding = lambda x, ww, hh: pos
        # LayerScale (gamma * x over the channel axis) converts to a BinaryOp that ncnn
        # broadcasts wrongly; fold gamma into the linear layer that feeds it instead.
        with torch.no_grad():
            for blk in vit.blocks:
                for ls, lin in ((blk.ls1, blk.attn.proj), (blk.ls2, blk.mlp.fc2)):
                    if hasattr(ls, 'gamma'):
                        lin.weight.mul_(ls.gamma[:, None])
                        lin.bias.mul_(ls.gamma)
                blk.ls1 = torch.nn.Identity()
                blk.ls2 = torch.nn.Identity()
        # after the fold: proj now carries gamma, and the MHA copies it
        for blk in vit.blocks:
            blk.attn.mha = _to_mha(blk.attn)
            blk.attn.forward = types.MethodType(_attention_forward, blk.attn)

    def forward(self, x):
        return self.model(x).unsqueeze(1)         # B,H,W -> B,1,H,W


def convert(model, w, h, tag):
    base = os.path.join(OUT, f'{tag}_{w}x{h}')
    x = torch.randn(1, 3, h, w)
    with torch.no_grad():
        traced = torch.jit.trace(Wrapped(model, w, h), x)
    traced.save(base + '.pt')
    subprocess.run(['pnnx', base + '.pt', f'inputshape=[1,3,{h},{w}]', 'device=cpu', 'fp16=1'],
                   check=True, cwd=OUT, stdout=subprocess.DEVNULL)
    param = f'{base}_fp16.param'
    binf = f'{base}_fp16.bin'
    shutil.move(base + '.ncnn.bin', binf)
    with open(base + '.ncnn.param') as f:
        txt = f.read()
    txt = re.sub(r'\bin0\b', 'data', txt)
    txt = re.sub(r'\bout0\b', 'output', txt)
    with open(param, 'w') as f:
        f.write(txt)
    # pnnx side products (python/onnx re-exports, intermediate params) are not needed
    for ext in ('.ncnn.param', '.pnnx.param', '.pnnx.bin', '.pnnx.onnx',
                '_pnnx.py', '_ncnn.py', '.pt'):
        try:
            os.remove(base + ext)
        except FileNotFoundError:
            pass
    return param, binf


def parity(model, param, binf, w, h):
    """Same input through torch (fp32) and ncnn (CPU, fp16 weights): relative error."""
    import ncnn
    rng = np.random.default_rng(0)
    # smooth-ish image so the depth output is not pure noise
    img = rng.standard_normal((3, h // 14, w // 14)).astype(np.float32)
    x = torch.nn.functional.interpolate(torch.from_numpy(img)[None], size=(h, w),
                                        mode='bilinear', align_corners=False)
    with torch.no_grad():
        ref = model(x)[0].numpy()

    net = ncnn.Net()
    net.opt.use_vulkan_compute = False
    net.load_param(param)
    net.load_model(binf)
    t0 = time.time()
    ex = net.create_extractor()
    xin = x[0].numpy().copy()        # ncnn.Mat wraps this memory: keep it alive
    ex.input('data', ncnn.Mat(xin))
    _, out = ex.extract('output')
    dt = time.time() - t0
    got = np.array(out).reshape(h, w)
    rel = np.abs(got - ref) / np.maximum(np.abs(ref), 1e-3)
    print(f'  torch depth {ref.min():.2f}..{ref.max():.2f} m, ncnn {got.min():.2f}..{got.max():.2f} m')
    print(f'  rel err median {np.median(rel)*100:.2f} %, p99 {np.percentile(rel, 99)*100:.2f} %'
          f'  (ncnn CPU {dt*1000:.0f} ms on this PC)')
    return float(np.median(rel))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--outdoor', action='store_true', help='VKITTI model (max 80 m)')
    ap.add_argument('--sizes', default='266x196,196x266', help='WxH list')
    args = ap.parse_args()
    os.makedirs(OUT, exist_ok=True)
    tag = 'depth_metric'
    model = load_model(args.outdoor)
    files = []
    ok = True
    for s in args.sizes.split(','):
        w, h = (int(v) for v in s.split('x'))
        assert w % 14 == 0 and h % 14 == 0, f'{s}: not a multiple of 14'
        print(f'== {w}x{h} ==')
        param, binf = convert(model, w, h, tag)
        files += [param, binf]
        ok &= parity(model, param, binf, w, h) < 0.02
    zpath = os.path.join(OUT, f'{tag}_ncnn.zip')
    with zipfile.ZipFile(zpath, 'w', zipfile.ZIP_STORED) as z:
        for f in files:
            z.write(f, os.path.basename(f))
    print('\nwrote', zpath, f'({os.path.getsize(zpath) / 1e6:.1f} MB)')
    if not ok:
        print('WARNING: ncnn output differs from torch by > 2 % median', file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main()
