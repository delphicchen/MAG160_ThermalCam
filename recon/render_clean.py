import numpy as np
import cv2

f_raw = np.load('/tmp/mag_20260626_001831_raw.npy').astype(np.float32)
factory_grid = np.load('/home/delphic/win_share/Delphic/thermal_cam/linux_app/factory_grid_high_gain.npy')

# Block 3 is index 1
offset_map = factory_grid[1, 0] 

# Try adding
f_clean = f_raw + offset_map

# Normalize to 8-bit to see the result
norm = cv2.normalize(f_clean, None, 0, 255, cv2.NORM_MINMAX, dtype=cv2.CV_8U)
cv2.imwrite('recon/clean_test.png', norm)
print("Saved clean_test.png")
