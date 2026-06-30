import numpy as np
b = open('recon/mag_cali.bin', 'rb').read()
W, H = 160, 120
N = W * H
for i in range(12):
    offset = 1024 + i * N * 2
    if offset + N*2 <= len(b):
        u16 = np.frombuffer(b[offset:offset+N*2], dtype=np.uint16).reshape((H, W))
        center = np.mean(u16[H//2-10:H//2+10, W//2-10:W//2+10])
        print(f"Block {i} (offset {offset}): min={u16.min():.0f}, max={u16.max():.0f}, center={center:.1f}")
