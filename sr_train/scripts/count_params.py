#!/usr/bin/env python3
"""Parameter and MAC count for the candidate architectures, at 160x120 input.

    python scripts/count_params.py

Use it before committing to a size: on a phone GPU the MAC count decides whether the
upscaler keeps up with 15 fps, and the parameter count alone is a poor proxy (RRDBNet
spends far more compute per parameter than SRVGGNetCompact).
"""

import torch
from basicsr.archs.rrdbnet_arch import RRDBNet
from realesrgan.archs.srvgg_arch import SRVGGNetCompact

W, H = 160, 120


def count(net) -> int:
    return sum(p.numel() for p in net.parameters())


def macs(net) -> float:
    """Rough MAC count: conv weights x output pixels, ignoring the upsample tail."""
    total = 0
    hooks = []

    def hook(m, i, o):
        nonlocal total
        if isinstance(m, torch.nn.Conv2d):
            total += m.weight.numel() * o.shape[-1] * o.shape[-2]

    for m in net.modules():
        hooks.append(m.register_forward_hook(hook))
    with torch.no_grad():
        net(torch.zeros(1, 3, H, W))
    for h in hooks:
        h.remove()
    return total


CANDIDATES = {
    'SRVGGNetCompact 64/16 (realesr-general-x4v3)':
        SRVGGNetCompact(3, 3, 64, 16, 4, 'prelu'),
    'SRVGGNetCompact 64/8  (half depth)':
        SRVGGNetCompact(3, 3, 64, 8, 4, 'prelu'),
    'RRDBNet 32/12 (alt_rrdb_compact_x4_gan.yml)':
        RRDBNet(3, 3, scale=4, num_feat=32, num_block=12, num_grow_ch=16),
    'RRDBNet 16/6  (asked-for nf=16)':
        RRDBNet(3, 3, scale=4, num_feat=16, num_block=6, num_grow_ch=8),
    'RRDBNet 64/23 (RealESRGAN_x4plus)':
        RRDBNet(3, 3, scale=4, num_feat=64, num_block=23, num_grow_ch=32),
}

print(f'{"model":46s} {"params":>10s} {"GMAC @160x120":>14s}')
for name, net in CANDIDATES.items():
    net.eval()
    print(f'{name:46s} {count(net) / 1e6:9.2f}M {macs(net) / 1e9:13.2f}')
