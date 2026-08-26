#!/usr/bin/env python3
"""User-proposed experiment: feed a synthetic seamed frame through the REAL factory
apply2 (0x3c8ce) under emulation and watch which stage removes the seam.

Variants:
  A) offset_ref = live shutter average (has the readout pattern)   [our current approach]
  B) offset_ref = mean-only (pattern-free)
  C) real raw without synthetic seam (control)
Measures column-seam strength of the output in each case.
"""
import sys, struct
sys.path.insert(0, '/home/delphic/win_share/Delphic/mag160_thermalcam/recon')
import numpy as np
from emu_common import build_parsed_ctx
from emu_build_nuc import seed_gates, BUILD
import emu_harness
from emu_harness import BASE

H, W = 120, 160
NPIX = H * W

e, ctx, _ = build_parsed_ctx(verbose=False)
seed_gates(e.mu, ctx)
e.w32(ctx + 0x38, 20000)
e.call(BUILD, args=(ctx, 0, 0), count=400_000_000)
e.call(BUILD, args=(ctx, 1, 2), count=400_000_000)

def r32(a): return struct.unpack('<I', bytes(e.mu.mem_read(a, 4)))[0]

# --- real data: raw frame + shutter average from the live capture ---
cap = np.load('/tmp/opencode/phaseE_capture.npz')
raw = cap['frames'][40].astype(np.uint16)               # settled real frame
shutter_avg = cap['ffc_refs'][1].astype(np.uint16)      # real shutter average

def seam(img):
    prof = img.mean(axis=0).astype(float)
    sm = np.convolve(prof, np.ones(9)/9, mode='same')
    return float(np.std(prof - sm))

# synthetic seam injection: +600 counts at col 128..158, ramp +1500 at col 159
synth = raw.copy().astype(np.int64)
synth[:, 128:159] += 600
synth[:, 159] += 1500
synth = np.clip(synth, 0, 65535).astype(np.uint16)
print(f"input seam: real={seam(raw.astype(float)):.1f}  synthetic={seam(synth.astype(float)):.1f}")

# --- allocate emulator buffers ---
frame_in = e.malloc(NPIX * 2)
off_ref = e.malloc(NPIX * 2)
frame_out = e.malloc(NPIX * 2)
u = e.mu

def run_apply2(frame_u16, offref_u16):
    u.mem_write(frame_in, frame_u16.tobytes())
    u.mem_write(off_ref, offref_u16.tobytes())
    e.w32(ctx + 0x1d58, off_ref)
    e.w32(ctx + 0x1d70, 1)          # offset_ref valid
    e.w32(ctx + 0x22c, frame_out)
    e.call(BASE + 0x3c8ce, args=(ctx, frame_in), count=400_000_000)
    out = np.frombuffer(bytes(u.mem_read(frame_out, NPIX * 2)), np.uint16).reshape(H, W)
    return out.astype(np.int64)

# variant A: real shutter average as offset_ref, synthetic seam in raw
outA = run_apply2(synth, shutter_avg)
print(f"A) real-shutter-avg offset_ref : out seam={seam(outA.astype(float)):.1f}  range {outA.min()}..{outA.max()}")

# variant B: pattern-free offset_ref (mean only)
mean_only = np.full((H, W), int(shutter_avg.mean()), np.uint16)
outB = run_apply2(synth, mean_only)
print(f"B) mean-only offset_ref        : out seam={seam(outB.astype(float)):.1f}  range {outB.min()}..{outB.max()}")

# control: real raw, real shutter avg
outC = run_apply2(raw, shutter_avg)
print(f"C) control (real raw)          : out seam={seam(outC.astype(float)):.1f}  range {outC.min()}..{outC.max()}")

# column profiles for A: where did the synthetic step go?
profA = outA.mean(axis=0)
print("\nA) out col profile 124..132:", [int(profA[x]) for x in range(124, 133)])
print("   out col profile 156..160:", [int(profA[x]) for x in range(156, 160)])
# how many pixels did the 0x45d60 bad-pixel stage change? rerun apply1-only for comparison
out1 = np.frombuffer(bytes(u.mem_read(frame_out, NPIX*2)), np.uint16)  # last run = apply2
EOF_MARKER = True
