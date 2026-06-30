import numpy as np

f_raw = np.load('/tmp/mag_20260626_001831_raw.npy').astype(np.float32)
factory_grid = np.load('/home/delphic/win_share/Delphic/thermal_cam/linux_app/factory_grid_high_gain.npy')

def line_variance(img):
    return np.var(np.diff(img, axis=1))

for idx in range(6):
    offset_map = factory_grid[1, idx] 
    print(f"idx={idx} (T={[-20, 0, 20, 40, 60, 80][idx]}): Var(+1.0)={line_variance(f_raw + offset_map):.1f}")
