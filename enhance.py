#!/usr/bin/env python3
"""Tier-1 image-quality pipeline for the Magnity thermal stream.

Two stages, deliberately separated so temperature stays accurate:

  enhance_data(raw)    -> value-preserving cleanup used for BOTH measurement and as the
                          base for display: bad-pixel correction + motion-adaptive
                          temporal denoising. Does not bias values (temporal blend is
                          disabled where the scene moves; BPC only swaps true outliers).

  enhance_display(data)-> edge-preserving spatial smoothing for nicer visuals only
                          (NOT used for the °C readout).

All operations work in raw sensor-count space (float32).
"""
import os
import numpy as np
import cv2


class NeuralSR:
    """Lightweight ONNX Runtime wrapper for the ESPCN thermal super-resolution model.
    Loads the model lazily on first call; inference is ~2-3 ms on CPU for 160×120."""

    def __init__(self, model_path):
        import onnxruntime as ort
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = 2
        opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        self._sess = ort.InferenceSession(model_path, opts,
                                          providers=['CPUExecutionProvider'])
        self._in_name = self._sess.get_inputs()[0].name

    def upscale(self, frame_f32):
        """Upscale a single HxW float32 frame. Values are preserved (not normalised)."""
        # normalise to [0,1] for the NN, then rescale back
        lo, hi = float(frame_f32.min()), float(frame_f32.max())
        span = max(hi - lo, 1e-6)
        normed = (frame_f32 - lo) / span
        inp = normed[np.newaxis, np.newaxis, :, :].astype(np.float32)  # (1,1,H,W)
        out = self._sess.run(None, {self._in_name: inp})[0]           # (1,1,2H,2W)
        return out[0, 0] * span + lo                                  # back to counts


class Enhancer:
    def __init__(self, W, H):
        self.W, self.H = W, H
        # toggles
        self.bpc = True
        self.flatfield = True
        self.temporal = True
        self.spatial = True
        # scene-based flat-field (shading/vignette + column-FPN) correction map
        self.flat_map = None
        # params
        self.bpc_k = 5.0           # per-frame impulse threshold (sigmas); the persistent
                                   # bad pixels are handled by the learned static_bad mask
        self.temporal_max = 0.85   # max history weight in fully-static areas
        self.temporal_k = 3.0      # motion sensitivity (in sigmas)
        self.spatial_d = 5         # bilateral window
        self.spatial_sigma_mult = 2.0
        # state
        self._avg = None           # temporal accumulator (float32)
        self._sigma = 30.0         # running noise estimate (counts)
        self._han = None           # Hanning window cache for phase correlation
        self._nn_sr = None         # lazy-loaded NeuralSR instance (or False if missing)
        # persistent learned bad-pixel mask (optional, OR'd with per-frame detection)
        self.static_bad = np.zeros((H, W), bool)

    # ---- noise estimate (robust, from frame-to-frame or spatial residual) ----
    def _update_sigma(self, resid):
        s = 1.4826 * np.median(np.abs(resid - np.median(resid))) + 1e-3
        self._sigma = 0.9 * self._sigma + 0.1 * float(s)
        return self._sigma

    # ---- bad-pixel correction: replace strong local outliers with the 3x3 median ----
    def _correct_bad(self, f):
        med = cv2.medianBlur(f, 3)
        dev = f - med
        sigma = 1.4826 * np.median(np.abs(dev)) + 1e-3
        mask = (np.abs(dev) > self.bpc_k * sigma) | self.static_bad
        out = f.copy()
        out[mask] = med[mask]
        return out, int(mask.sum())

    # ---- motion-adaptive temporal IIR ----
    def _temporal(self, f):
        if self._avg is None or self._avg.shape != f.shape:
            self._avg = f.copy()
            return f
        diff = f - self._avg
        sigma = self._update_sigma(diff)
        # history weight: ~temporal_max where |diff|<<sigma, ->0 where motion
        w = self.temporal_max * np.exp(-(diff / (self.temporal_k * sigma)) ** 2)
        out = w * self._avg + (1.0 - w) * f
        self._avg = out
        return out

    def reset_temporal(self):
        self._avg = None

    def learn_bad_pixels(self, frames, k=4.0):
        """Build a persistent bad-pixel mask from a stack of frames: pixels whose mean
        deviation from the local median is a consistent outlier."""
        F = np.stack([x.astype(np.float32) for x in frames])
        mean = F.mean(0)
        med = cv2.medianBlur(mean, 3)
        dev = mean - med
        sigma = 1.4826 * np.median(np.abs(dev - np.median(dev))) + 1e-3
        self.static_bad = np.abs(dev) > k * sigma
        return int(self.static_bad.sum())

    # ---- scene-based flat-field / shading correction (point at a uniform surface) ----
    def capture_flatfield(self, frames):
        """Build the per-pixel shading map from frames of a uniform-temperature target.
        Removes lens-shading vignette, residual column FPN and blotches that the shutter
        FFC can't (the shutter sits behind the lens). It's an additive (offset) NUC valid
        near the reference temperature; re-do it if the readout drifts a lot."""
        ref = np.mean([np.asarray(f, np.float32) for f in frames], axis=0)
        if self.static_bad.any():                  # don't bake bad pixels into the map
            ref = cv2.medianBlur(ref, 3) * self.static_bad + ref * (~self.static_bad)
        self.flat_map = (ref - float(ref.mean())).astype(np.float32)
        self.reset_temporal()
        return float(self.flat_map.std())

    def clear_flatfield(self):
        self.flat_map = None

    # ---- public ----
    def clean(self, raw):
        """Per-frame value-safe cleanup (bad-pixel + flat-field). No temporal blend, so
        each call is an independent frame — used as the input to super-resolution."""
        f = np.asarray(raw, dtype=np.float32)
        nbad = 0
        if self.bpc:
            f, nbad = self._correct_bad(f)
        if self.flatfield and self.flat_map is not None:
            f = f - self.flat_map
        self.last_nbad = nbad
        return f

    def temporal_step(self, f):
        return self._temporal(f) if self.temporal else f

    def enhance_data(self, raw):
        return self.temporal_step(self.clean(raw))

    # ---- multi-frame super-resolution (Tier 2) ----
    def superres(self, frames, scale=2, sharpen=0.45, max_shift=2.5):
        """Shift-and-add (drizzle-style) super-resolution: register each frame to the
        latest one with sub-pixel phase correlation, splat onto a `scale`× grid and
        coverage-normalise. Natural hand jitter supplies the sub-pixel diversity; falls
        back to cubic upscaling when the scene is static. Display-only.

        Optimised to use at most the last 4 frames and simplified weight accumulation
        (simple average instead of per-pixel coverage map) for ~4× speedup."""
        # NOTE: cv2.phaseCorrelate MUTATES its src args in place (multiplies them by the
        # window). Never hand it the originals or the frame buffer gets corrupted into a
        # radial Hanning halo that compounds every frame. Always pass throwaway copies.
        use = frames[-4:]                          # at most 4 recent frames for speed
        ref = np.array(use[-1], np.float32)        # independent copy (np.array copies)
        H, W = ref.shape
        Ws, Hs = W * scale, H * scale
        base = cv2.resize(ref, (Ws, Hs), interpolation=cv2.INTER_CUBIC)
        if len(use) >= 2:
            if self._han is None or self._han.shape != ref.shape:
                self._han = cv2.createHanningWindow((W, H), cv2.CV_32F)
            acc = base.copy()                      # start with the ref (already upscaled)
            n_good = 1
            for fr in use[:-1]:                    # skip last (= ref) to avoid redundancy
                f = np.asarray(fr, np.float32)
                try:
                    (dx, dy), _ = cv2.phaseCorrelate(ref.copy(), f.copy(), self._han)
                except cv2.error:
                    continue
                if abs(dx) > max_shift or abs(dy) > max_shift:
                    continue                       # reject big motion (not sub-pixel)
                M = np.float32([[scale, 0, -dx * scale], [0, scale, -dy * scale]])
                acc += cv2.warpAffine(f, M, (Ws, Hs), flags=cv2.INTER_LINEAR,
                                      borderMode=cv2.BORDER_REPLICATE)
                n_good += 1
            out = acc / n_good
        else:
            out = base
        if sharpen > 0:                            # mild unsharp for perceived detail
            out = out + sharpen * (out - cv2.GaussianBlur(out, (0, 0), 1.0))
        return out

    # ---- neural super-resolution (Tier 3, single-frame ONNX ESPCN) ----
    def neural_superres(self, frame, scale=2, sharpen=0.3):
        """Single-frame neural 2× super-resolution using a lightweight ESPCN model
        (ONNX Runtime). ~2-3 ms on CPU for 160×120 → 320×240. Falls back to bicubic
        + sharpen if the ONNX model is not available."""
        f = np.asarray(frame, np.float32)
        H, W = f.shape
        Ws, Hs = W * scale, H * scale

        if self._nn_sr is None:
            # lazy-load the ONNX model on first use
            model_dir = os.path.dirname(os.path.abspath(__file__))
            model_path = os.path.join(model_dir, "thermal_espcn_2x.onnx")
            self._nn_sr = NeuralSR(model_path) if os.path.exists(model_path) else False

        if self._nn_sr:
            out = self._nn_sr.upscale(f)
        else:
            # fallback: bicubic upscale
            out = cv2.resize(f, (Ws, Hs), interpolation=cv2.INTER_CUBIC)

        if sharpen > 0:
            out = out + sharpen * (out - cv2.GaussianBlur(out, (0, 0), 1.0))
        return out

    def enhance_display(self, data):
        if not self.spatial:
            return data
        f = np.asarray(data, dtype=np.float32)
        sc = self.spatial_sigma_mult * max(self._sigma, 1.0)
        return cv2.bilateralFilter(f, self.spatial_d, sc, self.spatial_d)


if __name__ == "__main__":
    # quick functional test on synthetic data with injected bad pixels + noise
    rng = np.random.default_rng(0)
    H, W = 120, 160
    base = np.tile(np.linspace(30000, 34000, W, dtype=np.float32), (H, 1))
    e = Enhancer(W, H)
    bad = [(40, 30), (100, 60), (10, 90), (150, 10), (80, 110)]
    out_nbad = 0
    for n in range(20):
        fr = base + rng.normal(0, 60, (H, W)).astype(np.float32)
        for (x, y) in bad:
            fr[y, x] += 5000          # stuck-hot bad pixels
        data = e.enhance_data(fr)
        out_nbad = e.last_nbad
        disp = e.enhance_display(data)
    # residual at bad-pixel locations should be small after correction
    resid = [abs(float(data[y, x] - base[y, x])) for (x, y) in bad]
    print(f"bad pixels detected/frame: {out_nbad}")
    print("residual at bad pixels after BPC+temporal:", [round(r, 1) for r in resid])
    print(f"noise std raw≈60 -> after pipeline std={float((data-base).std()):.1f}, "
          f"display std={float((disp-base).std()):.1f}")
