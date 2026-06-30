import numpy as np
import os

b = open('recon/mag_cali.bin', 'rb').read()
W, H = 160, 120
N = W * H

for i in range(12):
    offset = 1024 + i * N * 2
    if offset + N*2 <= len(b):
        u16 = np.frombuffer(b[offset:offset+N*2], dtype=np.uint16).reshape((H, W))
        f32 = u16.astype(np.float32)
        # 移除全局平均值，轉換成我們的 _flat_map 格式 (以 0 為中心)
        vignette = f32 - np.mean(f32)
        
        # 儲存成 npy
        np.save(f'factory_flat_{i}.npy', vignette)
        print(f"Saved factory_flat_{i}.npy")
