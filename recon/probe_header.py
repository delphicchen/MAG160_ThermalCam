#!/usr/bin/env python3
"""Live probe: locate the sensor/FPA temperature field in the frame telemetry.

The firmware keeps the sensor temperature at ctx[0x40] (returned by
MAG_GetSenorTemperature / MAG_GetCurrentCameraInnerTemperature) and updates it every
frame from the 28-byte frame header (and/or GetParameter2). We currently discard that
header. This script streams for a while and classifies every u16/u32 field of the header,
tail and GetParameter2 as:
    CONST    - never changes (geometry / format constants)
    COUNTER  - monotonically increments (frame counter / timestamp)
    DRIFT    - changes slowly & smoothly  <-- sensor temperature candidate
    NOISY    - jumps around

Run with the camera plugged in:  python3 recon/probe_header.py
Tip: breathe on / warm the lens housing during the run so the FPA temp drifts -> the
sensor-temp field will stand out as a smooth DRIFT.
"""
import sys, os, time, struct
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from magcam import MagCamera


def decode_fields(blobs, width):
    """blobs: list of equal-length byte strings. width: 2 or 4. Returns per-offset series."""
    n = len(blobs[0])
    fmt = '<H' if width == 2 else '<I'
    series = {}
    for off in range(0, n - width + 1, width):
        series[off] = np.array([struct.unpack_from(fmt, b, off)[0] for b in blobs], float)
    return series


def classify(s):
    if np.all(s == s[0]):
        return 'CONST', s[0]
    d = np.diff(s)
    rng = s.max() - s.min()
    if np.all(d >= 0) and np.std(d) < abs(np.mean(d)) * 0.5 + 1e-9:
        return 'COUNTER', f'+{np.mean(d):.1f}/frame'
    # smoothness: small frame-to-frame step relative to total range
    if rng > 0 and np.mean(np.abs(d)) < rng * 0.15:
        return 'DRIFT', f'range[{s.min():.0f},{s.max():.0f}] step~{np.mean(np.abs(d)):.1f}'
    return 'NOISY', f'range[{s.min():.0f},{s.max():.0f}]'


def report(name, blobs):
    print(f"\n===== {name}  ({len(blobs)} samples, {len(blobs[0])} bytes) =====")
    b0 = blobs[0]
    print("  raw[0] u32:", [f'0x{v:08x}' for v in struct.unpack_from('<%dI' % (len(b0)//4), b0, 0)])
    for width in (4, 2):
        print(f"  -- as u{width*8} --")
        for off, s in decode_fields(blobs, width).items():
            kind, info = classify(s)
            if kind in ('DRIFT', 'COUNTER') or (width == 4 and kind != 'CONST'):
                tag = '  <== sensor-temp?' if kind == 'DRIFT' else ''
                print(f"    off {off:2d}: {kind:8s} {info}{tag}")


def main():
    cam = MagCamera(); cam.open()
    print(f"camera {cam.W}x{cam.H} @ {cam.fps}fps")
    print("GetParameter1 u32:", [f'0x{v:08x}' for v in
          struct.unpack_from('<%dI' % (len(cam.param1)//4), cam.param1, 0)] if cam.param1 else None)
    cam.start()
    time.sleep(1.0)
    cam.trigger_ffc()

    hdrs, tails, p2s = [], [], []
    t0 = time.time()
    last = -1
    while time.time() - t0 < 20.0:
        h, t = cam.get_telemetry()
        if h is not None and cam.frame_count != last:
            last = cam.frame_count
            hdrs.append(h); tails.append(t)
            p2s.append(cam.get_param2(refresh=True))
        time.sleep(0.05)
    cam.stop(); cam.close()

    print(f"\ncollected {len(hdrs)} distinct frames over 20 s")
    if hdrs:
        report("FRAME HEADER", hdrs)
        report("FRAME TAIL", tails)
    if p2s and p2s[0]:
        L = min(len(x) for x in p2s)
        report("GetParameter2", [x[:L] for x in p2s])


if __name__ == "__main__":
    main()
