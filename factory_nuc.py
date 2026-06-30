#!/usr/bin/env python3
"""Factory NUC extraction from the calibration file — RESEARCH ARTIFACT, NOT wired into
the live pipeline (see the validation verdict below).

The cali file carries 48 plain 160×120 maps (`recon/mag_cali.bin`, off 1024, stride 38400),
organised as **6 sensor-temperature blocks × [2 gain maps (pos 0,1) + 6 reference frames
(pos 2..7)]**. The Android SDK turns these into a per-pixel gain/offset NUC via an external
C++ parser we can't run on x86 (PROTOCOL.md). This module extracts the maps and was used to
test whether the factory NUC can be reconstructed offline.

VERDICT (validated live against the shutter-FFC reference = the ground-truth per-pixel
offset at the current sensor temperature, 2026-06-29): **it can't, cleanly.**
  * Full per-pixel maps vs live FFC offset:        corr 0.02–0.26  (no match)
  * Smooth reference frames (pos 2..7), systematic: corr ~0.02      (no match)
  * Gain maps (pos 0,1): 0x8000-centred fixed-point; vignette extraction saturates.
  * The ONLY strong match was the column-FPN *profile* of the gain maps (corr 0.92) — but
    that is just the shared readout-channel structure, and it is exactly what the live
    shutter FFC already removes (and the live FFC is fresher than a stale factory map).
So integrating these maps does not improve on — and would risk regressing — our live
FFC + scene flat-field. The per-pixel gain (the one non-redundant piece) stays locked
behind the parser; the only reliable way to get it is to run that parser on an ARM device
(the Android JNI path in android/PLAN.md) and dump the expanded tables.

Kept for the record / future ARM-side work. `offset_for()` below is intentionally left as
the (failed) systematic-map experiment and is NOT imported by the app.
"""
import os
import numpy as np

_HERE = os.path.dirname(os.path.abspath(__file__))
CALI = os.path.join(_HERE, "recon", "mag_cali.bin")
W, H, MAPBYTES, START, NMAP = 160, 120, 160 * 120 * 2, 1024, 48


def _load_maps(path=CALI):
    data = open(path, "rb").read()
    maps = np.stack([
        np.frombuffer(data[START + k * MAPBYTES: START + (k + 1) * MAPBYTES],
                      dtype="<u2").reshape(H, W).astype(np.float64)
        for k in range(NMAP)])
    anchors = np.frombuffer(data[36:36 + 24], dtype="<u4").astype(np.float64)  # table A (6)
    return maps, anchors


def _systematic(img):
    """Decompose into the systematic correction: smooth vignette + column FPN + row FPN
    (drops the random per-pixel residual, which is sensor noise we must NOT bake in)."""
    import cv2
    vign = cv2.GaussianBlur(img.astype(np.float32), (0, 0), 15.0).astype(np.float64)
    resid = img - vign
    col = np.median(resid, axis=0, keepdims=True)            # (1,W)
    row = np.median(resid - col, axis=1, keepdims=True)      # (H,1)
    sysmap = vign + col + row
    return sysmap - sysmap.mean()


class FactoryNUC:
    def __init__(self, path=CALI):
        maps, self.anchors = _load_maps(path)
        # per sensor-temp group g (0..5): systematic map from the group's special maps (8g,8g+1)
        self.group_sys = np.stack([
            _systematic(0.5 * (maps[8 * g] + maps[8 * g + 1])) for g in range(6)])
        self.n_groups = 6

    def offset_for(self, sensor_temp_raw):
        """Zero-mean factory flat-field correction (HxW) interpolated to the live sensor
        temperature (raw counts, same units as the anchor table A / frame-tail+8)."""
        A = self.anchors
        t = float(sensor_temp_raw)
        if t <= A[0]:
            return self.group_sys[0].copy()
        if t >= A[-1]:
            return self.group_sys[-1].copy()
        g = int(np.searchsorted(A, t) - 1)
        f = (t - A[g]) / max(A[g + 1] - A[g], 1.0)
        return (1 - f) * self.group_sys[g] + f * self.group_sys[g + 1]


if __name__ == "__main__":
    fn = FactoryNUC()
    print("anchors A:", fn.anchors.astype(int).tolist())
    for t in (15000, 19633, 23123, 30000):
        m = fn.offset_for(t)
        print(f"  T={t}: factory flat-field std={m.std():7.1f}  range[{m.min():.0f},{m.max():.0f}]")
