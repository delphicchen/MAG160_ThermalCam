import numpy as np

# Load factory grid with proper 128 offset
factory_grid = np.load('/home/delphic/win_share/Delphic/thermal_cam/linux_app/factory_grid_high_gain.npy')

M_cold = factory_grid[0, 0] # Target 0 (Cold) at T=0
M_hot = factory_grid[2, 0]  # Target 2 (Hot) at T=0

diff = M_hot - M_cold
print("Diff max:", np.max(diff))
print("Diff min:", np.min(diff))
print("Diff mean:", np.mean(diff))
print("Pixels with diff >= 0:", np.sum(diff >= 0))
