import numpy as np

f_raw = np.load('/tmp/mag_20260626_001831_raw.npy').astype(np.float32)
factory_grid = np.load('/home/delphic/win_share/Delphic/thermal_cam/linux_app/factory_grid_high_gain.npy')
offset_map = factory_grid[1, 0] 

def line_variance(img):
    return np.var(np.diff(img, axis=1))

print("Var raw:", line_variance(f_raw))
print("Var (+1.0):", line_variance(f_raw + offset_map))
print("Var (+0.976):", line_variance(f_raw + 0.976 * offset_map))
