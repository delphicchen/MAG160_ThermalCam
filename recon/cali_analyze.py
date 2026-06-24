#!/usr/bin/env python3
"""Analyze the structure of the downloaded calibration file mag_cali.bin."""
import struct, numpy as np, sys
b = open(sys.argv[1] if len(sys.argv)>1 else 'mag_cali.bin','rb').read()
print("size", len(b))
hdr = struct.unpack_from('<16I', b, 0)
print("header u32[0:16]:", hdr)
print("header as hex:", ' '.join(f'{x:08x}' for x in hdr))
# interpret some as floats
print("header f32[0:16]:", [round(x,4) for x in struct.unpack_from('<16f', b, 0)])

W,H = hdr[1], hdr[2]
N = W*H
print(f"W={W} H={H} N={N}")

# Scan for regions that look like float32 tables (values in a sane physical range)
arr = np.frombuffer(b, dtype=np.float32)
i32 = np.frombuffer(b, dtype=np.int32)
u16 = np.frombuffer(b, dtype=np.uint16)

def floaty(x):
    return np.isfinite(x) and (abs(x) < 1e9) and (abs(x) > 1e-9 or x == 0)

# look at candidate per-pixel table offsets: header_len + k*N*itemsize
for hdrlen in (32, 40, 48, 64, 1024):
    print(f"\n-- assuming header {hdrlen} bytes --")
    off = hdrlen
    for itemsz, name, dt in [(2,'u16',np.uint16),(4,'f32',np.float32),(4,'i32',np.int32)]:
        if off + N*itemsz <= len(b):
            blk = np.frombuffer(b[off:off+N*itemsz], dtype=dt).astype(np.float64)
            print(f"   {name} block@{off} N={N}: min={blk.min():.4g} max={blk.max():.4g} mean={blk.mean():.4g}")

# histogram of how the file divides
print("\n-- coarse f32 stats over whole file (sampled) --")
s = arr[::97]
fin = s[np.isfinite(s)]
print("finite frac", len(fin)/len(s), "range", fin.min() if len(fin) else None, fin.max() if len(fin) else None)

# show distinct 'sections' by looking at running magnitude every 64KB
print("\n-- section magnitude every 0x10000 bytes (mean abs f32) --")
for o in range(0, len(b)-4, 0x10000):
    seg = np.frombuffer(b[o:o+0x10000], dtype=np.float32)
    seg = seg[np.isfinite(seg)]
    print(f"   @0x{o:06x}: meanabs={np.mean(np.abs(seg)):.4g} max={np.max(np.abs(seg)):.4g}")
