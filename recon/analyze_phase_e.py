#!/usr/bin/env python3
"""Phase E analysis: temporal stability of
  Chain A (current app): NUC offset_ref = live shutter ffcRef + levelBaseline
  Chain B (faithful):    NUC offset_ref = cali grid ref (per-FPA), no baseline
across natural warmup + forced FFCs. Metric: center-ROI median counts + whole-frame
robust stats per sample; a drift-free chain holds its level across FFC events."""
import numpy as np

g = np.load('/home/delphic/win_share/Delphic/mag160_thermalcam/factory_nuc_grid.npz')
fpa_g = g['fpa'].astype(np.int64)
ref_g = g['ref'].astype(np.int64)          # (N,H,W)
bp_g = g['breakpoints'].astype(np.int64)   # (N,H,W,nseg-1)
gain_g = g['gain'].astype(np.int64)
off_g = g['offset'].astype(np.int64)
NSEG = int(g['nseg']); SHIFT = int(g['shift'])
H, W = ref_g.shape[1], ref_g.shape[2]

d = np.load('/tmp/opencode/phaseE_capture.npz')
frames = d['frames'].astype(np.int64)      # (N,120,160) raw
fpa = d['fpa'].astype(np.int64)
t = d['t']
ffc_t = d['ffc_times']

def tables(f):
    """nearest-ref + blended bp/gain/off, exactly like FactoryNuc.kt"""
    j = np.searchsorted(fpa_g, f)
    if j <= 0: k = 0
    elif j >= len(fpa_g): k = len(fpa_g)-1
    else:
        lo, hi2 = j-1, j
        w = 0.0 if fpa_g[hi2] == fpa_g[lo] else (f - fpa_g[lo])/(fpa_g[hi2]-fpa_g[lo])
        k = lo if w < 0.5 else hi2
    return k

def nuc_apply(raw, f, offset_ref, baseline):
    k = tables(f)
    tref = ref_g[k]
    oref = tref if offset_ref is None else offset_ref.astype(np.int64)
    v = (raw - oref) >> 1
    bp = bp_g[k]; gn = gain_g[k]; of = off_g[k]
    out = np.empty(v.shape, np.int64)
    seg0 = np.zeros(v.shape, bool); acc_gain = None
    # vectorized segment select (nseg=3)
    s = (v > bp[..., 0]).astype(np.int64) + (v > bp[..., 1]).astype(np.int64)
    gsel = np.take_along_axis(gn, s[..., None], 2)[..., 0]
    osel = np.take_along_axis(of, s[..., None], 2)[..., 0]
    out = ((v * gsel) >> SHIFT) + osel
    if baseline:
        out = out - of[..., 0] + int(off_g[k][..., 0].mean())
    return np.clip(out, 0, 65535)

# interpolate ffcRef held by the app between events (piecewise constant)
def app_ffcref(i):
    tt = t[i]; idx = np.searchsorted(ffc_t, tt, side='right') - 1
    return d['ffc_refs'][max(idx, 0)]

cy, cx = H//2, W//2
roi = np.s_[cy-10:cy+10, cx-10:cx+10]

print(f"{'t':>5} {'fpa':>6} | {'A_roi':>8} {'A_std':>7} {'A_p1p99':>8} | "
      f"{'B_roi':>8} {'B_std':>7} {'B_p1p99':>8}  (event=F)")
prevA = prevB = None
for i in range(len(t)):
    ev = "F" if any(abs(t[i]-x) < 2.5 for x in ffc_t) else " "
    A = nuc_apply(frames[i], fpa[i], app_ffcref(i), True)
    B = nuc_apply(frames[i], fpa[i], None, False)
    aroi = np.median(A[roi]); broi = np.median(B[roi])
    astd = np.median(np.abs(A - np.median(A))); bstd = np.median(np.abs(B - np.median(B)))
    ap = np.percentile(A, [1,99]); bp_ = np.percentile(B, [1,99])
    dA = f"{aroi-prevA:+6.0f}" if prevA is not None else "      "
    dB = f"{broi-prevB:+6.0f}" if prevB is not None else "      "
    if i % 4 == 0 or ev == "F":
        print(f"{t[i]:5.0f} {fpa[i]:6d} {ev} | {aroi:8.0f} {astd:7.1f} {ap[1]-ap[0]:8.0f} | "
              f"{broi:8.0f} {bstd:7.1f} {bp_[1]-bp_[0]:8.0f}   dA{dA} dB{dB}")
    prevA, prevB = aroi, broi

# summary: max jump around each FFC event (within +-3 samples)
print("\n== jumps around FFC events ==")
for x in ffc_t:
    m = (t > x-6) & (t < x+6)
    idx = np.nonzero(m)[0]
    As = []; Bs = []
    for i in idx:
        A = nuc_apply(frames[i], fpa[i], app_ffcref(i), True)
        B = nuc_apply(frames[i], fpa[i], None, False)
        As.append(np.median(A[roi])); Bs.append(np.median(B[roi]))
    print(f"  t={x:5.0f}s  A: {min(As):7.0f}..{max(As):7.0f} (jump {max(As)-min(As):+5.0f})   "
          f"B: {min(Bs):7.0f}..{max(Bs):7.0f} (jump {max(Bs)-min(Bs):+5.0f})")
EOF_MARKER_NOT_NEEDED = True
