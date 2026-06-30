import numpy as np
import cv2
import os

with open('/home/delphic/win_share/Delphic/thermal_cam/linux_app/recon/mag_cali.bin', 'rb') as f:
    data = f.read()

# Let's save the first block at offset 128
os.makedirs('recon/test_offsets', exist_ok=True)
arr128 = np.frombuffer(data[128:128+38400], dtype=np.uint16).reshape((120, 160))
norm128 = cv2.normalize(arr128, None, 0, 255, cv2.NORM_MINMAX, dtype=cv2.CV_8U)
cv2.imwrite('recon/test_offsets/block_128.png', norm128)

# Let's also save the first block at offset 1024
arr1024 = np.frombuffer(data[1024:1024+38400], dtype=np.uint16).reshape((120, 160))
norm1024 = cv2.normalize(arr1024, None, 0, 255, cv2.NORM_MINMAX, dtype=cv2.CV_8U)
cv2.imwrite('recon/test_offsets/block_1024.png', norm1024)

# Print some stats to see if they are mostly 0s or structured
print("Offset 128 mean:", np.mean(arr128), "std:", np.std(arr128))
print("Offset 1024 mean:", np.mean(arr1024), "std:", np.std(arr1024))
