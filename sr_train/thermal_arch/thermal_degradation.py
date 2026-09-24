"""
Thermal-specific degradation on top of Real-ESRGAN's two-stage pipeline.

Real-ESRGAN's synthetic degradation models *photographic* damage: blur, resize, shot and
read noise, JPEG. An uncooled microbolometer adds one more thing that dominates the look
of a raw thermal frame and that the stock pipeline never produces: **fixed-pattern noise**
— per-column and per-row offsets left over from the NUC, plus a static 2-D residual. A
network trained without it learns to sharpen the stripes into hard vertical lines.

These two model classes subclass the stock ones and inject FPN into `self.lq` after the
standard degradation has run, so everything else (kernels, resize, noise, JPEG, the
USM-sharpened GT) behaves exactly as upstream.

Register by adding to the yml:

    model_type: ThermalRealESRNetModel      # stage 1, L1 only
    model_type: ThermalRealESRGANModel      # stage 2, + perceptual + GAN

and importing this module once before `train_pipeline` runs (see README step 3) — the
registry decorators below are what make those names resolvable.
"""

import numpy as np
import torch

from basicsr.utils.registry import MODEL_REGISTRY
from realesrgan.models.realesrgan_model import RealESRGANModel
from realesrgan.models.realesrnet_model import RealESRNetModel


def _add_fpn(lq: torch.Tensor, opt: dict) -> torch.Tensor:
    """Add per-column / per-row / 2-D fixed-pattern noise to a NCHW batch in [0, 1].

    Amplitudes are fractions of full scale, sampled per image so the network sees a
    range of NUC quality rather than one fixed pattern. Measure sensible values for your
    own camera with `scripts/estimate_fpn_stats.py`.
    """
    n, c, h, w = lq.shape
    dev = lq.device

    # rows as strong as columns: one model serves every mounting — the app rotates the
    # field 0/90/180/270 before upscaling, so the sensor's columns can arrive as rows
    col_rng = opt.get('fpn_col_sigma', [0.0, 0.010])
    row_rng = opt.get('fpn_row_sigma', [0.0, 0.010])
    map_rng = opt.get('fpn_map_sigma', [0.0, 0.006])
    gain_rng = opt.get('fpn_gain_sigma', [0.0, 0.004])
    prob = opt.get('fpn_prob', 0.9)

    def _u(rng):
        return torch.empty(n, 1, 1, 1, device=dev).uniform_(rng[0], rng[1])

    out = lq
    hit = (torch.rand(n, 1, 1, 1, device=dev) < prob).float()

    # per-column / per-row offset: the dominant artefact (column amplifiers, seam between
    # dies) — along whichever image axis the mounting puts the sensor's columns
    out = out + hit * _u(col_rng) * torch.randn(n, 1, 1, w, device=dev)
    out = out + hit * _u(row_rng) * torch.randn(n, 1, h, 1, device=dev)
    # static 2-D residual left by an imperfect flat-field
    out = out + hit * _u(map_rng) * torch.randn(n, 1, h, w, device=dev)
    # multiplicative (gain) FPN — scales with signal, unlike the offsets above. It is a
    # per-column pattern on the sensor, so its image axis is drawn per image (see above)
    g_col = torch.randn(n, 1, 1, w, device=dev).expand(n, 1, h, w)
    g_row = torch.randn(n, 1, h, 1, device=dev).expand(n, 1, h, w)
    along_cols = (torch.rand(n, 1, 1, 1, device=dev) < 0.5).float()
    gain = along_cols * g_col + (1.0 - along_cols) * g_row
    out = out * (1.0 + hit * _u(gain_rng) * gain)

    return out.clamp(0, 1)


def _low_contrast(lq: torch.Tensor, opt: dict) -> torch.Tensor:
    """Squeeze contrast toward the mid-grey.

    A real scene rarely fills the palette range: indoors the whole frame can sit inside
    3 °C, so after normalisation the *signal* is small compared to the noise. Training
    only on full-contrast crops teaches the network to trust edges it will never see at
    that strength.
    """
    rng = opt.get('contrast_range', [0.45, 1.0])
    if rng[1] >= 1.0 and rng[0] >= 1.0:
        return lq
    n = lq.shape[0]
    k = torch.empty(n, 1, 1, 1, device=lq.device).uniform_(rng[0], rng[1])
    mean = lq.mean(dim=(1, 2, 3), keepdim=True)
    return ((lq - mean) * k + mean).clamp(0, 1)


class _ThermalMixin:
    @torch.no_grad()
    def feed_data(self, data):
        super().feed_data(data)
        if self.is_train:
            self.lq = _low_contrast(self.lq, self.opt)
            self.lq = _add_fpn(self.lq, self.opt)


@MODEL_REGISTRY.register()
class ThermalRealESRNetModel(_ThermalMixin, RealESRNetModel):
    """Stage 1: same degradation, L1 only."""


@MODEL_REGISTRY.register()
class ThermalRealESRGANModel(_ThermalMixin, RealESRGANModel):
    """Stage 2: + perceptual + adversarial."""


def fpn_from_frames(frames: np.ndarray) -> dict:
    """Estimate FPN amplitudes (fraction of full scale) from real captures.

    `frames` is (N, H, W) float, several hundred frames of a *static* uniform-ish scene.
    The temporal mean removes shot noise, leaving the fixed pattern; the column/row means
    of that residual are the structured part.
    """
    m = frames.mean(axis=0)
    scale = float(np.percentile(frames, 99) - np.percentile(frames, 1)) or 1.0
    resid = m - m.mean()
    col = resid.mean(axis=0)
    row = resid.mean(axis=1)
    rest = resid - col[None, :] - row[:, None]
    return {
        'fpn_col_sigma': float(col.std()) / scale,
        'fpn_row_sigma': float(row.std()) / scale,
        'fpn_map_sigma': float(rest.std()) / scale,
    }
