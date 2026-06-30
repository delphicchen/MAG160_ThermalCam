"""Factory per-pixel radiometric NUC, applied from a pre-stored FPA-temp grid.

The camera's factory calibration (mag_cali.bin) encodes a per-pixel, multi-segment
piecewise-linear NUC whose tables are interpolated for the sensor (FPA) temperature.
We reversed the firmware build chain under emulation and pre-stored the resulting
tables over a grid of FPA temps (`factory_nuc_grid.npz`, built by
`recon/build_nuc_grid.py`). At runtime we read the live FPA temp (frame tail+8, the
same anchor-A units the grid is keyed on), pick/interpolate the grid tables, and run
the firmware's exact per-pixel apply — which is BIT-EXACT to the emulated SDK.

Apply per pixel i:
    v   = (raw[i] - ref[i]) >> 1
    s   = first segment with v <= breakpoint[i, s]   (else the last segment)
    out = clamp( ((v * gain[i, s]) >> shift) + offset[i, s], 0, 65535 )

IMPORTANT — offset_ref must be the camera's RAW-domain per-pixel dark reference (the
shutter-closed frame, `magcam._ffc_ref`, ~33000 counts), fed UNCORRECTED raw from
`get_raw()`. The grid's own `ref` (a radiance-domain ~800-7500 section reference, the
emulator's stand-in for the un-loaded firmware offset) is NOT in raw units and gives a
white/saturated image if used directly on live raw — always pass `offset_ref=` the live
shutter dark. We keep the factory gain/breakpoints/offset (the per-pixel responsivity +
piecewise curve), which is the valuable part FFC alone can't reproduce.
"""
import os
import numpy as np

GRID_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "factory_nuc_grid.npz")


class FactoryNUC:
    def __init__(self, path=GRID_PATH):
        self.ok = False
        if not os.path.exists(path):
            return
        d = np.load(path)
        self.fpa = d["fpa"].astype(np.int64)                 # (N,)
        self.ref = d["ref"].astype(np.int64)                 # (N,H,W)
        self.bp = d["breakpoints"].astype(np.int64)          # (N,H,W,nseg-1)
        self.gain = d["gain"].astype(np.int64)               # (N,H,W,nseg)
        self.offset = d["offset"].astype(np.int64)           # (N,H,W,nseg)
        self.nseg = int(d["nseg"])
        self.shift = int(d["shift"])
        self.fmin, self.fmax = int(self.fpa.min()), int(self.fpa.max())
        self.ok = True

    def _tables(self, fpa):
        """Return (ref, bp, gain, offset) for a given FPA temp (anchor units)."""
        f = int(np.clip(fpa, self.fmin, self.fmax))
        j = int(np.searchsorted(self.fpa, f))
        if j <= 0:
            return self.ref[0], self.bp[0], self.gain[0], self.offset[0]
        if j >= len(self.fpa):
            j = len(self.fpa) - 1
            return self.ref[j], self.bp[j], self.gain[j], self.offset[j]
        lo, hi = j - 1, j
        flo, fhi = self.fpa[lo], self.fpa[hi]
        w = 0.0 if fhi == flo else (f - flo) / (fhi - flo)
        nearest = lo if w < 0.5 else hi
        # ref steps discretely at section boundaries -> use nearest, never blend.
        ref = self.ref[nearest]
        same = np.array_equal(self.ref[lo], self.ref[hi])
        if not same:
            return ref, self.bp[nearest], self.gain[nearest], self.offset[nearest]
        blend = lambda a: np.rint(a[lo] * (1 - w) + a[hi] * w).astype(np.int64)
        return ref, blend(self.bp), blend(self.gain), blend(self.offset)

    def apply(self, raw, fpa, offset_ref=None, level_baseline=True):
        """Apply the factory NUC. `raw` = HxW UNCORRECTED raw frame (get_raw), `fpa` =
        live FPA raw. `offset_ref` = the camera's shutter dark frame (magcam._ffc_ref) in
        raw counts — STRONGLY recommended; falls back to the grid ref (radiance domain,
        only self-consistent inside the emulator) if None.

        `level_baseline` (default on): subtract the per-pixel ZERO-SIGNAL output baseline
        (`offset[:, :, 0]`, what the NUC outputs when v==0) and add back its scalar mean.
        That removes the factory offset table's fixed per-pixel pattern — including the
        readout-channel step at column 128 (a vertical seam otherwise) — while keeping the
        valuable per-pixel gain + the piecewise behaviour for hot pixels. With a live
        shutter dark as `offset_ref`, the offset table's region step would otherwise be
        double-counted against the dark frame, so leveling it is the correct hybrid.
        Returns a float32 frame."""
        ref, bp, gain, offset = self._tables(fpa)
        H, Wd = ref.shape
        r = np.asarray(raw, np.int64).reshape(H, Wd)
        oref = ref if offset_ref is None else np.asarray(offset_ref, np.int64).reshape(H, Wd)
        v = (r - oref) >> 1
        seg = np.zeros((H, Wd), np.int64)
        for s in range(self.nseg - 1):
            seg = np.where((seg == s) & (v > bp[..., s]), s + 1, seg)
        ii, jj = np.indices((H, Wd))
        out = ((v * gain[ii, jj, seg]) >> self.shift) + offset[ii, jj, seg]
        if level_baseline:
            base = offset[:, :, 0]
            out = out - base + int(base.mean())
        return np.clip(out, 0, 65535).astype(np.float32)
