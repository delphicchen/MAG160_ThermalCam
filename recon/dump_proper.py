import numpy as np
import cv2
import os

os.makedirs('recon/proper_blocks', exist_ok=True)
with open('/home/delphic/win_share/Delphic/thermal_cam/linux_app/recon/mag_cali.bin', 'rb') as f:
    data = f.read()

offset = 1024
for i in range(48):
    if offset + 38400 > len(data): break
    arr = np.frombuffer(data[offset:offset+38400], dtype=np.uint16).reshape((120, 160))
    # Normalize to 0-255 for viewing
    if np.max(arr) == np.min(arr):
        norm = np.zeros_like(arr, dtype=np.uint8)
    else:
        norm = cv2.normalize(arr, None, 0, 255, cv2.NORM_MINMAX, dtype=cv2.CV_8U)
    cv2.imwrite(f'recon/proper_blocks/block_{i:02d}.png', norm)
    offset += 38400
