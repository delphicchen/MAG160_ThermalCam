import numpy as np

factory_grid = np.load('/home/delphic/win_share/Delphic/thermal_cam/linux_app/factory_grid_high_gain.npy')

A = factory_grid[0, 0]
B = factory_grid[1, 0]
C = factory_grid[2, 0]

print("A mean:", np.mean(A), "std:", np.std(A))
print("B mean:", np.mean(B), "std:", np.std(B))
print("C mean:", np.mean(C), "std:", np.std(C))

