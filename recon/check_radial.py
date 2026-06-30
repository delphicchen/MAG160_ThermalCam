import numpy as np

b = open('recon/mag_cali.bin', 'rb').read()
W, H = 160, 120
N = W * H

for offset in [1024, 39424, 77824]:
    u16 = np.frombuffer(b[offset:offset+N*2], dtype=np.uint16).reshape((H, W))
    
    # Check center vs corners
    center = np.mean(u16[H//2-10:H//2+10, W//2-10:W//2+10])
    corners = np.mean([
        u16[0:10, 0:10],
        u16[0:10, -10:],
        u16[-10:, 0:10],
        u16[-10:, -10:]
    ])
    
    print(f"Offset {offset}: Center mean = {center:.1f}, Corners mean = {corners:.1f}, Diff = {corners - center:.1f}")
