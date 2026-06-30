import numpy as np
import cv2

with open('/home/delphic/win_share/Delphic/thermal_cam/linux_app/recon/mag_cali.bin', 'rb') as f:
    data = f.read()

offset = 1024
arr = np.frombuffer(data[offset:offset+38400], dtype=np.uint16)

# Try reshaping as (160, 120) and transposing
col_major = arr.reshape((160, 120)).T
norm1 = cv2.normalize(col_major, None, 0, 255, cv2.NORM_MINMAX, dtype=cv2.CV_8U)
cv2.imwrite('recon/proper_blocks/test_col_major.png', norm1)

# Try reshaping normally
row_major = arr.reshape((120, 160))
norm2 = cv2.normalize(row_major, None, 0, 255, cv2.NORM_MINMAX, dtype=cv2.CV_8U)
cv2.imwrite('recon/proper_blocks/test_row_major.png', norm2)

print("Saved test images.")
