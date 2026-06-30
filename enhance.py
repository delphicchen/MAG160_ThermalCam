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
        # scene-based flat-field (shading/vignette + column-FPN) offset correction map
        self.flat_map = None
        # two-point per-pixel NUC: an affine map  corrected = gain_a * f + gain_b  measured on
        # the (FFC-corrected) clean() input. Corrects the RESIDUAL non-uniformity left after the
        # offset-only FFC/flat-field — i.e. per-pixel responsivity (gain), the genuinely
        # non-redundant factory bit (see recon/EMULATION_NUC.md: the factory NUC's effective
        # per-pixel gain near the operating range). Supersedes flat_map (offset+gain vs offset).
        self.gain_a = None         # per-pixel slope (≈ 1/responsivity)
        self.gain_b = None         # per-pixel intercept
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
        self._nn_sr = {}           # lazy-loaded NeuralSR instances by scale
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
        near the reference temperature; re-do it if the readout drifts a lot.
        
        To avoid baking temporal noise into a permanent spatial grid, we decompose the
        reference into 3 deterministic components: smooth vignette, column FPN, and row FPN.
        """
        ref = np.mean([np.asarray(f, np.float32) for f in frames], axis=0)
        
        # 1. Remove bad pixels before analysis
        ref_med = cv2.medianBlur(ref, 3)
        if self.static_bad.any():
            ref = ref_med * self.static_bad + ref * (~self.static_bad)
        else:
            ref = ref_med
            
        # 2. Extract smooth vignette (lens shading)
        vignette = cv2.GaussianBlur(ref, (0, 0), 15.0)
        
        # 3. Extract 1D Fixed Pattern Noise (FPN)
        # Residual contains FPN + random noise
        resid = ref - vignette
        col_fpn = np.median(resid, axis=0, keepdims=True)         # (1, W)
        row_fpn = np.median(resid - col_fpn, axis=1, keepdims=True) # (H, 1)
        
        # 4. Reconstruct clean map (noise-free)
        clean_flat = vignette + col_fpn + row_fpn
        self.flat_map = (clean_flat - float(clean_flat.mean())).astype(np.float32)
        
        self.reset_temporal()
        return float(self.flat_map.std())

    def clear_flatfield(self):
        self.flat_map = None
        self.gain_a = self.gain_b = None

    # ---- two-point per-pixel NUC (offset + responsivity gain) ----
    def capture_flatfield_2pt(self, cold_frames, warm_frames, clamp=(0.6, 1.7)):
        """Build the per-pixel affine NUC `corrected = a*f + b` from two uniform-target bursts
        at different levels (e.g. a room-temp wall and a warmer surface/hand), measured on the
        same FFC-corrected frames clean() sees. Two-point: maps cold->mean(cold) and
        warm->mean(warm) for every pixel, so a flat scene reads flat across the [cold,warm]
        range — correcting per-pixel responsivity (gain) that the offset-only FFC leaves.
        Supersedes the offset-only flat_map. Returns (gain_std, span_counts)."""
        c = np.mean([np.asarray(f, np.float32) for f in cold_frames], axis=0)
        w = np.mean([np.asarray(f, np.float32) for f in warm_frames], axis=0)
        if self.static_bad.any():                      # de-spike ONLY flagged bad pixels;
            med = lambda x: cv2.medianBlur(x, 3)        # do NOT blur the per-pixel gain away
            c = np.where(self.static_bad, med(c), c)
            w = np.where(self.static_bad, med(w), w)
        Lc, Lw = float(np.median(c)), float(np.median(w))
        span = abs(Lw - Lc)
        if span < 200:                                 # the two targets are too close in level
            return 0.0, span
        dpix = w - c
        dpix = np.where(np.abs(dpix) < 1.0, np.sign(dpix) * 1.0 + 1e-3, dpix)
        a = np.clip((Lw - Lc) / dpix, *clamp).astype(np.float32)   # per-pixel slope (~1/gain)
        # the bursts are frame-averaged (low estimate noise), so keep the per-pixel slope as-is;
        # only replace flagged bad pixels with the local median to avoid spikes.
        if self.static_bad.any():
            a = np.where(self.static_bad, cv2.medianBlur(a, 3), a)
        b = (Lc - a * c).astype(np.float32)            # per-pixel intercept
        self.gain_a, self.gain_b = a, b
        self.flat_map = None                           # affine supersedes the offset-only map
        self.reset_temporal()
        return float(a.std()), span

    # ---- public ----
    def clean(self, raw):
        """Per-frame value-safe cleanup (bad-pixel + flat-field). No temporal blend, so each
        call is an independent frame — used as the input to super-resolution."""
        f = np.asarray(raw, dtype=np.float32)
        nbad = 0
        if self.bpc:
            f, nbad = self._correct_bad(f)
        if self.flatfield and self.gain_a is not None:
            f = self.gain_a * f + self.gain_b          # two-point affine (offset + gain)
        elif self.flatfield and self.flat_map is not None:
            f = f - self.flat_map                      # one-point offset-only fallback
        self.last_nbad = nbad
        return f

    def temporal_step(self, f):
        return self._temporal(f) if self.temporal else f

    def enhance_data(self, raw):
        return self.temporal_step(self.clean(raw))

    # ---- neural super-resolution (ESPCN via OpenVINO / ONNX Runtime) ----
    def neural_superres(self, frame, scale=2, sharpen=0.15):
        """Single-frame neural super-resolution using a lightweight ESPCN model.
        Pre-smooths with bilateral filter to suppress sensor noise *before* the NN
        (avoids amplifying grain into the upscaled image), then applies mild unsharp
        masking for perceived detail.

        Backend: OpenVINO GPU → OpenVINO CPU → ONNX Runtime CPU (auto-detected).
        Falls back to bicubic + sharpen if no ONNX model found."""
        f = np.asarray(frame, np.float32)
        H, W = f.shape
        Ws, Hs = W * scale, H * scale

        if scale not in self._nn_sr:
            # lazy-load the ONNX model on first use for this scale
            model_dir = os.path.dirname(os.path.abspath(__file__))
            model_path = os.path.join(model_dir, f"thermal_espcn_{scale}x.onnx")
            if os.path.exists(model_path):
                self._nn_sr[scale] = NeuralSR(model_path)
            else:
                self._nn_sr[scale] = False

        # pre-smooth: light bilateral to remove sensor grain before upscaling
        # (the NN will amplify whatever noise is in its input)
        sc = self.spatial_sigma_mult * max(self._sigma, 1.0)
        f_smooth = cv2.bilateralFilter(f, self.spatial_d, sc, self.spatial_d)

        if self._nn_sr[scale]:
            out = self._nn_sr[scale].upscale(f_smooth)
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
