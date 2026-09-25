"""
Thermal-specific degradation on top of Real-ESRGAN's two-stage pipeline.

Real-ESRGAN's synthetic degradation models *photographic* damage — blur, resize, shot and
read noise, JPEG — in display units, as if every scene filled the palette. A thermal
frame does not: the app stretches each frame's 1–99 % to the palette, a wall spans under
1 °C, and the sensor's noise and fixed-pattern stripes are a fixed size *in °C*. After the
stretch that noise is a large share of full scale exactly where the scene is flat — the
input the first two releases hallucinated streaks and corners from, and which training in
display units never showed them.

So after the standard degradation these model classes view each training pair the way
the app does ([`_sensor_view`]): the crop is a scene with a temperature span drawn
log-uniformly (0.5–40 °C by default), the sensor adds pixel noise, column and row
stripes, a 2-D residual and a gain pattern of a size drawn in °C, and the display range
is the 1–99 % of that noisy frame — applied to the input *and* the targets, as the app
applies it to the prediction. The defaults are generic microbolometer ranges, not one
unit's measurement; they cover what 38 real MAG160 frames showed (span 0.7–33 °C, pixel
noise ≈0.1–0.2 °C, stripes up to ≈0.1 °C) with margin.

This replaces the first releases' `_low_contrast`, which squeezed the input's contrast
but not the target's and so taught the network to *stretch* contrast (gain 1.05–1.10 on
held-out frames), and their FPN in fractions of full scale (≤1 % — a low-span MAG160
frame shows 2–5 %).

Single channel: with `num_in_ch: 1` in network_g the data path still runs in RGB — the
crops are grey replicated into three channels, and upstream's DiffJPEG needs three — and
the tensors are cut to their first channel at the model boundary. Channel 0 rather than
the mean, so noise keeps its per-pixel strength (averaging colour noise would shrink it
by √3). The VGG perceptual loss needs RGB, so it gets the grey repeated back to three.

`L1FFTLoss` adds an L1 on the image spectrum to the pixel L1: it holds high frequencies —
edges — that a plain L1 averages away, without the GAN's license to invent texture.

Register by adding to the yml:

    model_type: ThermalRealESRNetModel      # stage 1, pixel losses
    model_type: ThermalRealESRGANModel      # stage 2, + perceptual + GAN
    pixel_opt: {type: L1FFTLoss, ...}

and importing this module once before `train_pipeline` runs (see README step 3) — the
registry decorators below are what make those names resolvable.
"""

import math

import numpy as np
import torch
from torch import nn
from torch.nn import functional as F

from basicsr.utils.registry import LOSS_REGISTRY, MODEL_REGISTRY
from realesrgan.models.realesrgan_model import RealESRGANModel
from realesrgan.models.realesrnet_model import RealESRNetModel


def _sensor_view(lq: torch.Tensor, gts: list, opt: dict):
    """Add °C-sized sensor noise to `lq` for a scene of random span, then give `lq` and
    every target in `gts` the app's display stretch (1–99 % of the noisy `lq`).

    All tensors are NCHW in [0, 1]; the targets may be larger (the 4x GT). Patterns are
    (n, 1, …) and broadcast over channels, so a grey frame stays grey. Returns
    (lq, [targets]) in the same order.
    """
    n, _, h, w = lq.shape
    dev = lq.device

    def _u(rng):
        return torch.empty(n, 1, 1, 1, device=dev).uniform_(float(rng[0]), float(rng[1]))

    span_lo, span_hi = opt.get('sensor_span_c', [0.5, 40.0])
    span = torch.exp(_u([math.log(span_lo), math.log(span_hi)]))        # °C, log-uniform
    hit = (torch.rand(n, 1, 1, 1, device=dev) < opt.get('sensor_prob', 0.9)).float()
    to_display = hit / span                                              # °C -> [0, 1]

    stripe = opt.get('sensor_stripe_c', [0.0, 0.10])
    noise_c = (_u(opt.get('sensor_noise_c', [0.03, 0.20])) * torch.randn(n, 1, h, w, device=dev)
               # stripes along both axes, equally strong: one model serves every mounting
               # and the app rotates before upscaling, so sensor columns can arrive as rows
               + _u(stripe) * torch.randn(n, 1, 1, w, device=dev)
               + _u(stripe) * torch.randn(n, 1, h, 1, device=dev)
               # static 2-D residual left by an imperfect flat-field
               + _u(opt.get('sensor_map_c', [0.0, 0.05])) * torch.randn(n, 1, h, w, device=dev))
    out = lq + to_display * noise_c

    # multiplicative (gain) FPN scales with the signal, so it stays relative; a per-column
    # pattern on the sensor, on a random image axis for the same reason as the stripes
    g_col = torch.randn(n, 1, 1, w, device=dev).expand(n, 1, h, w)
    g_row = torch.randn(n, 1, h, 1, device=dev).expand(n, 1, h, w)
    along_cols = (torch.rand(n, 1, 1, 1, device=dev) < 0.5).float()
    gain = along_cols * g_col + (1.0 - along_cols) * g_row
    out = out * (1.0 + hit * _u(opt.get('sensor_gain', [0.0, 0.004])) * gain)

    # the app's display stretch, from the noisy frame, applied to input and targets alike
    q = torch.quantile(out.flatten(1).float(),
                       torch.tensor([0.01, 0.99], device=dev), dim=1)   # (2, n)
    lo = q[0].view(n, 1, 1, 1)
    inv = 1.0 / (q[1].view(n, 1, 1, 1) - lo).clamp_min(1e-3)

    def stretch(t):
        return ((t - lo) * inv).clamp(0, 1)

    return stretch(out), [stretch(t) for t in gts]


@LOSS_REGISTRY.register()
class L1FFTLoss(nn.Module):
    """Pixel L1 plus `fft_weight` x an L1 between the orthonormal 2-D spectra (real and
    imaginary parts). The spectral term keeps edges that a plain L1 averages away.

    With the orthonormal transform the spectral L1 of an over-smoothed prediction is about
    0.6x its pixel L1 (measured on CIDIS crops), so 0.5 makes it ~30 % of the loss: enough
    to pull edges back, while the pixel term still leads. (Recipes quoting 0.1 use an
    unnormalised FFT, whose values are larger by the square root of the pixel count.)"""

    def __init__(self, loss_weight=1.0, fft_weight=0.5, reduction='mean'):
        super().__init__()
        self.loss_weight = loss_weight
        self.fft_weight = fft_weight
        self.reduction = reduction

    def forward(self, pred, target, weight=None, **kwargs):
        l1 = F.l1_loss(pred, target, reduction=self.reduction)
        fp = torch.view_as_real(torch.fft.rfft2(pred.float(), norm='ortho'))
        ft = torch.view_as_real(torch.fft.rfft2(target.float(), norm='ortho'))
        return self.loss_weight * (l1 + self.fft_weight * F.l1_loss(fp, ft, reduction=self.reduction))


class _GreyPerceptual(nn.Module):
    """Feeds 1-channel images to an RGB perceptual loss as R=G=B."""

    def __init__(self, inner: nn.Module):
        super().__init__()
        self.inner = inner

    def forward(self, x, gt):
        return self.inner(x.repeat(1, 3, 1, 1), gt.repeat(1, 3, 1, 1))


class _ThermalMixin:
    def _single_channel(self) -> bool:
        return self.opt['network_g'].get('num_in_ch', 3) == 1

    def init_training_settings(self):
        super().init_training_settings()
        if self._single_channel() and getattr(self, 'cri_perceptual', None) is not None:
            self.cri_perceptual = _GreyPerceptual(self.cri_perceptual)

    @torch.no_grad()
    def feed_data(self, data):
        super().feed_data(data)
        names = [k for k in ('gt', 'gt_usm') if getattr(self, k, None) is not None]
        if self.is_train:
            self.lq, gts = _sensor_view(self.lq, [getattr(self, k) for k in names], self.opt)
            for k, t in zip(names, gts):
                setattr(self, k, t)
        if self._single_channel():
            # after upstream's degradation and pair queue, which both run in RGB
            for name in ['lq'] + names:
                t = getattr(self, name)
                if t.shape[1] == 3:
                    setattr(self, name, t[:, :1].contiguous())


@MODEL_REGISTRY.register()
class ThermalRealESRNetModel(_ThermalMixin, RealESRNetModel):
    """Stage 1: same degradation, pixel losses only."""


@MODEL_REGISTRY.register()
class ThermalRealESRGANModel(_ThermalMixin, RealESRGANModel):
    """Stage 2: + perceptual + adversarial."""


def fpn_from_frames(frames: np.ndarray) -> dict:
    """Estimate sensor noise from real captures, in the frames' own units (°C for the
    app's .mgt data) — the units of the `sensor_*` options.

    `frames` is (N, H, W) float, several hundred frames of a *static* uniform-ish scene.
    The temporal mean removes shot noise, leaving the fixed pattern; the column/row means
    of that residual are the stripes, what is left the 2-D residual; the per-pixel
    temporal spread is the pixel noise.
    """
    m = frames.mean(axis=0)
    resid = m - m.mean()
    col = resid.mean(axis=0)
    row = resid.mean(axis=1)
    rest = resid - col[None, :] - row[:, None]
    return {
        'noise': float(frames.std(axis=0).mean()),
        'stripe_col': float(col.std()),
        'stripe_row': float(row.std()),
        'map': float(rest.std()),
    }
