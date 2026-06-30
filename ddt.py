#!/usr/bin/env python3
"""DDT (radiometric snapshot) save/load for the Magnity Linux viewer.

A DDT is the camera world's "pure temperature stream" frozen to a file: it stores the
per-pixel measurement frame (radiometric counts) plus everything needed to turn any pixel
into °C later — so you can re-measure or (re)calibrate temperature offline, without the
camera. (The official Magnity .ddt is an 884-byte header + raw frame; we keep the same
"fixed header + frame" shape but use our own documented header, so these files are
self-contained for this app rather than byte-compatible with the Android SDK.)

Layout (little-endian):
    0   4   magic  b"MDDT"
    4   2   version (=1)
    6   2   W
    8   2   H
   10   2   dtype  (0 = float32 counts)
   12   4   fpa_raw  (live sensor/FPA temperature counts; 0xFFFFFFFF if unknown)
   16   8   timestamp (float64, unix seconds)
   24   1   calibrated (0/1) — whether a,b,lut below are valid
   25   1   lut_idx
   26   6   (reserved)
   32   8   cal a (float64)
   40   8   cal b (float64)
   48  208  (reserved, zero)
  256   W*H*4  frame, float32 measurement counts (row-major HxW)
"""
import struct, time, os
import numpy as np

MAGIC = b"MDDT"
VERSION = 1
HEADER = 256


def save(path, frame, fpa_raw=None, a=1.0, b=0.0, lut_idx=0, calibrated=False, ts=None):
    """Write a DDT. `frame` is HxW measurement counts (the viewer's last_frame)."""
    f = np.ascontiguousarray(np.asarray(frame, np.float32))
    H, W = f.shape
    ts = time.time() if ts is None else float(ts)
    fpa = 0xFFFFFFFF if fpa_raw is None else int(fpa_raw) & 0xFFFFFFFF
    hdr = bytearray(HEADER)
    struct.pack_into("<4sHHHH", hdr, 0, MAGIC, VERSION, W, H, 0)
    struct.pack_into("<I", hdr, 12, fpa)
    struct.pack_into("<d", hdr, 16, ts)
    struct.pack_into("<BB", hdr, 24, 1 if calibrated else 0, int(lut_idx) & 0xFF)
    struct.pack_into("<dd", hdr, 32, float(a), float(b))
    with open(path, "wb") as fh:
        fh.write(hdr)
        fh.write(f.tobytes())


def load(path):
    """Read a DDT -> dict(frame, W, H, fpa_raw, ts, calibrated, a, b, lut_idx)."""
    with open(path, "rb") as fh:
        hdr = fh.read(HEADER)
        if len(hdr) < HEADER or hdr[:4] != MAGIC:
            raise ValueError("not a DDT file (bad magic)")
        _, ver, W, H, dtype = struct.unpack_from("<4sHHHH", hdr, 0)
        fpa, = struct.unpack_from("<I", hdr, 12)
        ts, = struct.unpack_from("<d", hdr, 16)
        calibrated, lut_idx = struct.unpack_from("<BB", hdr, 24)
        a, b = struct.unpack_from("<dd", hdr, 32)
        data = fh.read(W * H * 4)
        frame = np.frombuffer(data, np.float32).reshape(H, W).copy()
    return dict(frame=frame, W=W, H=H,
                fpa_raw=(None if fpa == 0xFFFFFFFF else fpa),
                ts=ts, calibrated=bool(calibrated), a=a, b=b, lut_idx=lut_idx,
                name=os.path.basename(path))


if __name__ == "__main__":
    # round-trip self-test
    fr = (np.random.rand(120, 160).astype(np.float32) * 1000 + 28000)
    p = "/tmp/_ddt_test.ddt"
    save(p, fr, fpa_raw=19633, a=8.6, b=-28730.0, lut_idx=2, calibrated=True)
    d = load(p)
    assert d["W"] == 160 and d["H"] == 120
    assert d["fpa_raw"] == 19633 and d["lut_idx"] == 2 and d["calibrated"]
    assert np.allclose(d["frame"], fr)
    print("DDT round-trip OK:", {k: v for k, v in d.items() if k != "frame"})
