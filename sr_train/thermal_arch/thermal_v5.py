"""
v5: a re-parameterised student and knowledge distillation (sr_train/PLAN_V5.md).

`RepSRVGG` is SRVGGNetCompact with every 3×3 conv trained as three parallel branches —
3×3, 1×1, and the identity where the channel counts allow it — the RepVGG / ECBSR idea.
The branches are linear and sum, so after training they fold into one 3×3 conv
(`scripts/rep_fold.py`) and the network the app runs is a plain SRVGGNetCompact of the
same shape: same ncnn graph, same milliseconds per frame. The extra branches only change
how the weights are reached: a 1×1 and an identity path give the optimiser easier routes
than a lone 3×3 (published gains for compact SR nets are a few tenths of a dB).

The state-dict layout mirrors SRVGGNetCompact's `body` list (conv, PReLU, conv, PReLU, …,
conv), with each conv as `body.{i}.conv3` / `body.{i}.conv1`, so folding is a key-by-key
map; `rep_fold.py --init` goes the other way and starts the branches at a trained
SRVGGNetCompact exactly (3×3 = W − identity, 1×1 = 0).

`ThermalDistillModel` is stage 1's model (`ThermalRealESRNetModel`: sensor view, single
channel, pixel loss against the GT) plus a frozen teacher — a large RRDBNet that runs on
Colab only — and a second loss pulling the student towards the teacher's output on the
same degraded input:

    loss = pixel_opt(student, GT) + distill_opt(student, teacher(lq))

The teacher has seen the same degradations and has ~40× the capacity, so its output is a
better-conditioned target than the GT alone for a small net: it says what is recoverable
from this input, not what was in the scene.

Register by importing this module once before `train_pipeline` runs (the v5 notebook
patches realesrgan/train.py to import it next to thermal_degradation).
"""

import torch
from torch import nn
from torch.nn import functional as F

try:   # the registries exist in the Colab training env; the archs also work without them
    from basicsr.utils.registry import ARCH_REGISTRY
except ImportError:   # pragma: no cover — local tests without basicsr
    ARCH_REGISTRY = None


def _register_arch(cls):
    return ARCH_REGISTRY.register()(cls) if ARCH_REGISTRY is not None else cls


class RepConv(nn.Module):
    """3×3 + 1×1 (+ identity when in == out channels), all with bias, summed."""

    def __init__(self, cin: int, cout: int):
        super().__init__()
        self.conv3 = nn.Conv2d(cin, cout, 3, 1, 1)
        self.conv1 = nn.Conv2d(cin, cout, 1, 1, 0)
        self.identity = cin == cout

    def forward(self, x):
        y = self.conv3(x) + self.conv1(x)
        return y + x if self.identity else y

    @torch.no_grad()
    def fused(self):
        """(weight, bias) of the single 3×3 conv this branch set equals."""
        w = self.conv3.weight.clone()
        w[:, :, 1:2, 1:2] += self.conv1.weight
        if self.identity:
            idx = torch.arange(w.shape[0])
            w[idx, idx, 1, 1] += 1.0
        return w, self.conv3.bias + self.conv1.bias


@_register_arch
class RepSRVGG(nn.Module):
    """SRVGGNetCompact, trained re-parameterised. Same constructor arguments; PReLU only
    (what the app's models use)."""

    def __init__(self, num_in_ch=1, num_out_ch=1, num_feat=64, num_conv=10, upscale=4,
                 act_type='prelu'):
        super().__init__()
        if act_type != 'prelu':
            raise ValueError('RepSRVGG supports act_type prelu only')
        self.upscale = upscale
        self.body = nn.ModuleList()
        self.body.append(RepConv(num_in_ch, num_feat))
        self.body.append(nn.PReLU(num_parameters=num_feat))
        for _ in range(num_conv):
            self.body.append(RepConv(num_feat, num_feat))
            self.body.append(nn.PReLU(num_parameters=num_feat))
        self.body.append(RepConv(num_feat, num_out_ch * upscale * upscale))
        self.upsampler = nn.PixelShuffle(upscale)

    def forward(self, x):
        out = x
        for layer in self.body:
            out = layer(out)
        out = self.upsampler(out)
        return out + F.interpolate(x, scale_factor=self.upscale, mode='nearest')


def fold_state_dict(rep_sd: dict) -> dict:
    """RepSRVGG weights → SRVGGNetCompact weights (same `body.{i}` indices)."""
    out = {}
    convs = sorted({int(k.split('.')[1]) for k in rep_sd if k.endswith('conv3.weight')})
    for i in convs:
        p = f'body.{i}.'
        w3, b3 = rep_sd[p + 'conv3.weight'], rep_sd[p + 'conv3.bias']
        w1, b1 = rep_sd[p + 'conv1.weight'], rep_sd[p + 'conv1.bias']
        w = w3.clone().float()
        w[:, :, 1:2, 1:2] += w1.float()
        if w.shape[0] == w.shape[1]:
            idx = torch.arange(w.shape[0])
            w[idx, idx, 1, 1] += 1.0
        out[p + 'weight'] = w
        out[p + 'bias'] = (b3 + b1).float()
    for k, v in rep_sd.items():   # PReLU slopes pass through
        if '.conv3.' not in k and '.conv1.' not in k:
            out[k] = v.float()
    return out


def unfold_state_dict(sd: dict) -> dict:
    """SRVGGNetCompact weights → RepSRVGG weights computing exactly the same function:
    3×3 = W − identity (where the layer has one), 1×1 = 0."""
    out = {}
    for k, v in sd.items():
        parts = k.split('.')
        if parts[0] == 'body' and v.dim() == 4:            # a conv weight
            p = f'body.{parts[1]}.'
            w = v.clone().float()
            if w.shape[0] == w.shape[1]:
                idx = torch.arange(w.shape[0])
                w[idx, idx, 1, 1] -= 1.0
            out[p + 'conv3.weight'] = w
            out[p + 'conv1.weight'] = torch.zeros(w.shape[0], w.shape[1], 1, 1)
        elif parts[0] == 'body' and parts[-1] == 'bias':
            p = f'body.{parts[1]}.'
            out[p + 'conv3.bias'] = v.clone().float()
            out[p + 'conv1.bias'] = torch.zeros_like(v).float()
        else:                                                # PReLU
            out[k] = v.clone().float()
    return out


# ---- distillation (needs the training env) -------------------------------------------

try:
    from collections import OrderedDict

    from basicsr.archs import build_network
    from basicsr.losses import build_loss
    from basicsr.utils import get_root_logger
    from basicsr.utils.registry import MODEL_REGISTRY
    from realesrgan.models.realesrnet_model import RealESRNetModel

    from thermal_arch.thermal_degradation import _ThermalMixin
except ImportError:   # pragma: no cover — local tests import the archs only
    MODEL_REGISTRY = None

if MODEL_REGISTRY is not None:

    @MODEL_REGISTRY.register()
    class ThermalDistillModel(_ThermalMixin, RealESRNetModel):
        """Stage 1 + a frozen teacher (`network_t`, `path.pretrain_network_t`) and
        `train.distill_opt`, a loss between student and teacher outputs."""

        def init_training_settings(self):
            super().init_training_settings()
            self.net_t = build_network(self.opt['network_t']).to(self.device)
            path = self.opt['path'].get('pretrain_network_t')
            if not path:
                raise ValueError('ThermalDistillModel needs path.pretrain_network_t')
            self.load_network(self.net_t, path, True,
                              self.opt['path'].get('param_key_t', 'params_ema'))
            self.net_t.eval()
            for p in self.net_t.parameters():
                p.requires_grad_(False)
            self.cri_kd = build_loss(self.opt['train']['distill_opt']).to(self.device)
            get_root_logger().info(f'Distillation teacher: {path}')

        def optimize_parameters(self, current_iter):
            self.optimizer_g.zero_grad()
            self.output = self.net_g(self.lq)
            with torch.no_grad():
                target_t = self.net_t(self.lq)
            loss_dict = OrderedDict()
            l_pix = self.cri_pix(self.output, self.gt)
            l_kd = self.cri_kd(self.output, target_t)
            loss_dict['l_pix'] = l_pix
            loss_dict['l_kd'] = l_kd
            (l_pix + l_kd).backward()
            self.optimizer_g.step()
            self.log_dict = self.reduce_loss_dict(loss_dict)
            if self.ema_decay > 0:
                self.model_ema(decay=self.ema_decay)
