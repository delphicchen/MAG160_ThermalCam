import numpy as np
import os

with open('/home/delphic/win_share/Delphic/thermal_cam/linux_app/recon/mag_cali.bin', 'rb') as f:
    data = f.read()

factory_grid = np.zeros((3, 6, 120, 160), dtype=np.float32)

for r in range(6): # 6 temperatures
    for c in range(3): # 3 blocks we care about (Block 2, 4, 6 -> idx 2, 4, 6)
        # Wait, earlier I mapped idx = r * 8 + (c + 2).
        # Let's map target 0 -> block 2, target 1 -> block 4, target 2 -> block 6
        idx = r * 8 + (c*2 + 2)
        offset = 128 + idx * 38400
        
        arr = np.frombuffer(data[offset:offset+38400], dtype=np.uint16).reshape((120, 160))
        factory_grid[c, r] = arr.astype(np.float32)

np.save('/home/delphic/win_share/Delphic/thermal_cam/linux_app/factory_grid_high_gain.npy', factory_grid)
print("Saved factory_grid_high_gain.npy with offset 128")
