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
    """Lightweight inference wrapper for the ESPCN thermal super-resolution model.

    Backend priority:
      1. OpenVINO  (Intel iGPU → CPU fallback) — best for Intel platforms
      2. ONNX Runtime CPUExecutionProvider       — portable fallback

    Inference is ~2 ms (OpenVINO GPU) / ~2.7 ms (ONNX Runtime CPU) for 160×120.
    """

    def __init__(self, model_path):
        self._backend = None        # 'openvino' or 'onnxrt'
        self._infer = None          # callable: np(1,1,H,W) -> np(1,1,2H,2W)

        # --- try OpenVINO first ---
        try:
            import openvino as ov
            core = ov.Core()
            model = core.read_model(model_path)
            # prefer GPU (Intel iGPU) for lowest latency, fall back to CPU
            devices = core.available_devices
            if 'GPU' in devices:
                compiled = core.compile_model(model, 'GPU',
                                              config={'PERFORMANCE_HINT': 'LATENCY'})
                self._backend = 'openvino-GPU'
            else:
                compiled = core.compile_model(model, 'CPU',
                                              config={'PERFORMANCE_HINT': 'LATENCY',
                                                      'NUM_STREAMS': '1'})
                self._backend = 'openvino-CPU'
            req = compiled.create_infer_request()
            out_key = compiled.output(0)
            self._infer = lambda x, _r=req, _k=out_key: _r.infer({'input': x})[_k]
        except Exception:
            pass

        # --- fallback: ONNX Runtime ---
        if self._infer is None:
            try:
                import onnxruntime as ort
                opts = ort.SessionOptions()
                opts.intra_op_num_threads = 2
                opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
                sess = ort.InferenceSession(model_path, opts,
                                            providers=['CPUExecutionProvider'])
                in_name = sess.get_inputs()[0].name
                self._infer = lambda x, _s=sess, _n=in_name: _s.run(None, {_n: x})[0]
                self._backend = 'onnxrt-CPU'
            except Exception as e:
                raise RuntimeError(f"No inference backend available: {e}")

    @property
    def backend(self):
        return self._backend

    def upscale(self, frame_f32):
        """Upscale a single HxW float32 frame. Values are preserved (not normalised)."""
        # normalise to [0,1] for the NN, then rescale back
        lo, hi = float(frame_f32.min()), float(frame_f32.max())
        span = max(hi - lo, 1e-6)
        normed = (frame_f32 - lo) / span
        inp = normed[np.newaxis, np.newaxis, :, :].astype(np.float32)  # (1,1,H,W)
        out = self._infer(inp)                                         # (1,1,2H,2W)
        return np.asarray(out[0, 0]) * span + lo                       # back to counts


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

    # ---- neural super-resolution (ESPCN via OpenVINO / ONNX Runtime) ----
    def neural_superres(self, frame, scale=2, sharpen=0.15):
        """Single-frame neural 2× super-resolution using a lightweight ESPCN model.
        Pre-smooths with bilateral filter to suppress sensor noise *before* the NN
        (avoids amplifying grain into the upscaled image), then applies mild unsharp
        masking for perceived detail.

        Backend: OpenVINO GPU → OpenVINO CPU → ONNX Runtime CPU (auto-detected).
        ~2 ms (iGPU) / ~2.7 ms (CPU) for 160×120 → 320×240.
        Falls back to bicubic + sharpen if no ONNX model found."""
        f = np.asarray(frame, np.float32)
        H, W = f.shape
        Ws, Hs = W * scale, H * scale

        if self._nn_sr is None:
            # lazy-load the ONNX model on first use
            model_dir = os.path.dirname(os.path.abspath(__file__))
            model_path = os.path.join(model_dir, "thermal_espcn_2x.onnx")
            if os.path.exists(model_path):
                self._nn_sr = NeuralSR(model_path)
            else:
                self._nn_sr = False

        # pre-smooth: light bilateral to remove sensor grain before upscaling
        # (the NN will amplify whatever noise is in its input)
        sc = self.spatial_sigma_mult * max(self._sigma, 1.0)
        f_smooth = cv2.bilateralFilter(f, self.spatial_d, sc, self.spatial_d)

        if self._nn_sr:
            out = self._nn_sr.upscale(f_smooth)
        else:
            # fallback: bicubic upscale
            out = cv2.resize(f_smooth, (Ws, Hs), interpolation=cv2.INTER_CUBIC)

        if sharpen > 0:
            out = out + sharpen * (out - cv2.GaussianBlur(out, (0, 0), 1.2))
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
