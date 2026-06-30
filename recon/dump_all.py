import numpy as np
import cv2
import os

os.makedirs('recon/blocks', exist_ok=True)
with open('recon/mag_cali.bin', 'rb') as f:
    data = f.read()

offset = 24
for i in range(48):
    if offset + 38400 > len(data): break
    raw_block = data[offset:offset+38400]
    arr = np.frombuffer(raw_block, dtype=np.uint16).reshape((120, 160))
    # scale for human viewing
    vis = cv2.normalize(arr, None, 0, 255, cv2.NORM_MINMAX, dtype=cv2.CV_8U)
    cv2.imwrite(f'recon/blocks/block_{i:02d}.png', vis)
    offset += 38400
