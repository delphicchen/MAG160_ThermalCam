import numpy as np
b = open('recon/mag_cali.bin', 'rb').read()
W, H = 160, 120
N = W * H
for i in range(12):
    offset = 1024 + i * N * 2
    if offset + N*2 <= len(b):
        u16 = np.frombuffer(b[offset:offset+N*2], dtype=np.uint16).reshape((H, W))
        center = np.mean(u16[H//2-10:H//2+10, W//2-10:W//2+10])
        corners = np.mean([u16[0:10, 0:10], u16[0:10, -10:], u16[-10:, 0:10], u16[-10:, -10:]])
        diff = center - corners
        print(f"Block {i}: mean={np.mean(u16):.1f}, center-corner={diff:.1f}")
