import numpy as np

with open('/home/delphic/win_share/Delphic/thermal_cam/linux_app/recon/mag_cali.bin', 'rb') as f:
    data = f.read()

# 6 temperatures, 3 High-Gain targets (Blocks 2, 4, 6)
factory_grid = np.zeros((3, 6, 120, 160), dtype=np.float32)

offset = 1024
for r in range(6):
    for c in range(8):
        if offset + 38400 > len(data): break
        arr = np.frombuffer(data[offset:offset+38400], dtype=np.uint16).reshape((120, 160))
        if c == 2:
            factory_grid[0, r] = arr
        elif c == 4:
            factory_grid[1, r] = arr
        elif c == 6:
            factory_grid[2, r] = arr
        offset += 38400

np.save('factory_grid_high_gain.npy', factory_grid)
print("Saved factory_grid_high_gain.npy")
