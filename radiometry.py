#!/usr/bin/env python3
"""Temperature conversion for the Magnity camera, built from the reverse-engineered
radiometric formula + the camera's built-in Planck LUT family.

Decoded chain (see PROTOCOL.md):
    radiance = raw*(k+1) + b           -> here generalised to  radiance = a*raw + b
    U        = LUT_inverse(radiance)   -> U = idx*4096 - 150000 (+ slope interp)
    temp     = U  (hypothesis: milli-Kelvin)  ->  °C = U/1000 - 273.15

The per-camera coefficients (a,b) and the best LUT are recovered by `calibrate()` from
a few (raw_value, known_celsius) reference points. The LUT *shape* is the camera's real
Planck radiance curve (extracted from libcoresdk.so -> planck_luts.npy), so this is far
more accurate than a naive linear raw->°C fit.
"""
import os, numpy as np

_HERE = os.path.dirname(os.path.abspath(__file__))
LUT_PATH = os.path.join(_HERE, "recon", "planck_luts.npy")

U_OFFSET = -150000          # U = idx*4096 + U_OFFSET
U_STEP = 4096
KELVIN = 273.15
U_PER_KELVIN = 1000.0       # hypothesis: U in milli-Kelvin


def celsius_to_U(tc):
    return (tc + KELVIN) * U_PER_KELVIN

def U_to_celsius(u):
    return u / U_PER_KELVIN - KELVIN


class Radiometry:
    def __init__(self, lut_path=LUT_PATH):
        self.luts = np.load(lut_path).astype(np.float64)   # (8, 646)
        self.n_lut, self.n_idx = self.luts.shape
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

    # inverse: U from a radiance value (the binary-search + slope-interp of sub_455bc)
    def _U_from_radiance(self, lut, rad):
        rad = np.asarray(rad, float)
        idx = np.searchsorted(lut, rad) - 1
        idx = np.clip(idx, 0, self.n_idx - 2)
        lo = lut[idx]; hi = lut[idx + 1]
        frac = np.where(hi > lo, (rad - lo) / np.maximum(hi - lo, 1e-9), 0.0)
        frac = np.clip(frac, 0, 1)
        return (idx + frac) * U_STEP + U_OFFSET

    def raw_to_celsius(self, raw):
        rad = self.a * np.asarray(raw, float) + self.b
        u = self._U_from_radiance(self.luts[self.lut_idx], rad)
        return U_to_celsius(u)

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
    print("unseen 50°C -> predicted:", round(float(r.raw_to_celsius(test_raw)), 3))
