import numpy as np

factory_grid = np.load('/home/delphic/win_share/Delphic/thermal_cam/linux_app/factory_grid_high_gain.npy')
# Shape is (3, 6, 120, 160)

for param in range(3):
    block = factory_grid[param, 0] # T=0
    odd_cols = block[:, 1::2]
    even_cols = block[:, 0::2]
    print(f"Param {param}:")
    print(f"  Odd columns mean: {np.mean(odd_cols):.2f}")
    print(f"  Even columns mean: {np.mean(even_cols):.2f}")
    print(f"  Difference: {np.mean(odd_cols) - np.mean(even_cols):.2f}")
