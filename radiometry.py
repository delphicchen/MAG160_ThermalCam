#!/usr/bin/env python3
"""Temperature conversion for the Magnity camera — a faithful reimplementation of the
camera's built-in radiometric chain, reverse-engineered from libcoresdk.so.

DECODED CHAIN (the firmware's `sub_3f970` radiometric core; see PROTOCOL.md):

    v      = raw*(k+1) + b                       # k = ctx[0x50], b = ctx[0x54]  (floats)
    target = v << (7 - shift)                    # shift = ctx[0x775c] (small int)
    idx    = binary_search(radLUT, target)       # radLUT = 646-entry Planck radiance LUT
    U      = idx*4096 - 150000                    # temperature grid, in milli-Kelvin
             + ( slopeLUT[idx] * (target-radLUT[idx]) ) >> 12      # linear interp
    °C     = U/1000 - 273.15

What is FIXED in the hardware vs. what is per-camera:
  * `radLUT` (the Planck radiance curve) and `slopeLUT` are the FIXED part. The firmware
    keeps them in .bss (radLUT @0x29d700, slopeLUT @0x29e11c) and fills them at runtime
    from one of 8 static Planck curves baked into .rodata -> extracted to
    `recon/planck_luts.npy`. We reconstruct slopeLUT exactly as the firmware does:
    `slopeLUT[i] = 2**24 // (radLUT[i+1]-radLUT[i])`  (so (slope*Δ)>>12 == one idx step).
  * `k`, `b` (and the `shift` scaling) are the PER-CAMERA part. The firmware does NOT
    store them as constants — `sub_3d280` recomputes them every frame from the factory
    NUC blocks (per sensor-temperature, stride 0x21c), the live sensor temperature
    (ctx[0x80]) and the live shutter/FFC reference (`sub_3d9f4`). The NUC blocks are
    expanded from `mag_cali.bin` by a parser that lives in an external library behind
    C++ stream layers (`sub_34832` -> PLT import), so they can't be extracted offline.

CONSEQUENCE: we reproduce the FIXED radiometric curve exactly, and recover the per-camera
linear term (a, b) — which folds k, b and the `<<(7-shift)` scaling into
`radiance = a*raw + b` — from a few (raw, known_°C) reference points via `calibrate()`.
Because the curve is the camera's true Planck LUT (not a guessed shape), 2 references are
enough for accurate absolute temperature over a wide range; the firmware itself only
differs by re-deriving (a,b) live as the sensor drifts (which is what re-FFC + re-cal do).
"""
import os, numpy as np

_HERE = os.path.dirname(os.path.abspath(__file__))
LUT_PATH = os.path.join(_HERE, "recon", "planck_luts.npy")

U_OFFSET = -150000          # U = idx*4096 + U_OFFSET
U_STEP = 4096
KELVIN = 273.15
U_PER_KELVIN = 1000.0       # U is in milli-Kelvin (verified: idx109 -> 23.31 °C)


def celsius_to_U(tc):
    return (tc + KELVIN) * U_PER_KELVIN

def U_to_celsius(u):
    return u / U_PER_KELVIN - KELVIN


class Radiometry:
    def __init__(self, lut_path=LUT_PATH):
        self.luts = np.load(lut_path).astype(np.int64)     # (8, 646) int radiance curves
        self.n_lut, self.n_idx = self.luts.shape
        # firmware-exact reciprocal-slope tables: slopeLUT[i] = 2**24 // (L[i+1]-L[i])
        diffs = np.diff(self.luts, axis=1)                 # (8, 645)
        self.slopes = (1 << 24) // np.maximum(diffs, 1)
        self.a = 1.0
        self.b = 0.0
        self.lut_idx = self.n_lut // 2
        self.calibrated = False

    # radiance at a given U (temperature-grid value), via forward interpolation
    def _radiance_at_U(self, lut, u):
        pos = (np.asarray(u, float) - U_OFFSET) / U_STEP
        pos = np.clip(pos, 0, self.n_idx - 1.0001)
        i = pos.astype(int)
        frac = pos - i
        return lut[i] * (1 - frac) + lut[i + 1] * frac

    def _U_from_radiance(self, li, rad):
        """Inverse mapping radiance(target) -> U, BIT-FAITHFUL to sub_3f970's simple path:
        binary-search for idx in radLUT `li`, then U = idx*4096 - 150000
        + (slopeLUT[idx]*Δ)>>12.  (Matches the float interp to <0.01 °C.)"""
        lut = self.luts[li]; slope = self.slopes[li]
        ti = np.clip(np.floor(np.asarray(rad, float)).astype(np.int64), lut[0], lut[-1])
        idx = np.clip(np.searchsorted(lut, ti, side='right') - 1, 0, self.n_idx - 2)
        delta = ti - lut[idx]                               # target - radLUT[idx]  (>=0)
        u = idx.astype(np.int64) * U_STEP + U_OFFSET + ((slope[idx] * delta) >> 12)
        return u.astype(float)

    def raw_to_celsius(self, raw):
        """raw counts -> °C, using the calibrated linear term and the camera Planck LUT.
        `a*raw+b` folds the firmware's k,b and the <<(7-shift) scaling into one line."""
        rad = self.a * np.asarray(raw, float) + self.b
        u = self._U_from_radiance(self.lut_idx, rad)
        return U_to_celsius(u)

    def frame_to_celsius(self, raw_frame):
        """Vectorised whole-frame conversion (HxW raw -> HxW °C) for ROI/point tools."""
        f = np.asarray(raw_frame, float)
        return self.raw_to_celsius(f.ravel()).reshape(f.shape)

    def calibrate(self, points):
        """points: list of (raw_value, known_celsius). Tries every built-in LUT, fits a
        linear raw->radiance (a,b) by least squares so that LUT_inverse reproduces the
        known temps, and keeps the LUT with the smallest °C residual.
        Returns (rms_celsius, n_points)."""
        pts = [(float(r), float(t)) for r, t in points]
        if len(pts) < 2:
            raise ValueError("need >= 2 reference points")
        raws = np.array([p[0] for p in pts])
        temps = np.array([p[1] for p in pts])
        best = None
        for li in range(self.n_lut):
            lut = self.luts[li]
            # target radiance for each known temp (forward curve)
            target_rad = self._radiance_at_U(lut, celsius_to_U(temps))
            # fit radiance = a*raw + b  (least squares)
            A = np.vstack([raws, np.ones_like(raws)]).T
            (a, b), *_ = np.linalg.lstsq(A, target_rad, rcond=None)
            # residual measured back in °C
            self.a, self.b, self.lut_idx = a, b, li
            pred = self.raw_to_celsius(raws)
            rms = float(np.sqrt(np.mean((pred - temps) ** 2)))
            if best is None or rms < best[0]:
                best = (rms, li, a, b)
        rms, li, a, b = best
        self.a, self.b, self.lut_idx, self.calibrated = a, b, li, True
        return rms, len(pts)

    def state(self):
        return dict(a=self.a, b=self.b, lut_idx=self.lut_idx, calibrated=self.calibrated)


if __name__ == "__main__":
    r = Radiometry()
    print(f"loaded {r.n_lut} LUTs x {r.n_idx} entries")
    # self-consistency test: invent a ground-truth (lut, a, b), synthesize raw for known
    # temps, then check calibrate() recovers temperatures.
    gt = Radiometry(); gt.lut_idx = 3; gt.a = 35.0; gt.b = -250000.0
    true_temps = np.array([0.0, 20.0, 37.0, 60.0, 100.0])
    # invert model to get the raw that would yield each temp:
    target_rad = gt._radiance_at_U(gt.luts[gt.lut_idx], celsius_to_U(true_temps))
    raws = (target_rad - gt.b) / gt.a
    pts = list(zip(raws, true_temps))
    print("synthetic (raw, °C):", [(round(x, 1), t) for x, t in pts])
    rms, n = r.calibrate(pts)
    print(f"calibrated: lut={r.lut_idx} a={r.a:.4g} b={r.b:.4g} rms={rms:.4f}°C over {n} pts")
    print("recovered temps:", np.round(r.raw_to_celsius(raws), 3))
    # cross-check at an unseen raw
    test_raw = (gt._radiance_at_U(gt.luts[gt.lut_idx], celsius_to_U(np.array([50.0]))) - gt.b) / gt.a
    print("unseen 50°C -> predicted:", round(float(np.ravel(r.raw_to_celsius(test_raw))[0]), 3))
