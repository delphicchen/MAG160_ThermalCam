import numpy as np
import cv2

# Load the 6 matrices for Column 3 (T_sensor = 19.3°C)
with open('/home/delphic/win_share/Delphic/thermal_cam/linux_app/recon/mag_cali.bin', 'rb') as f:
    data = f.read()

# Column 3 is index 3, 11, 19, 27, 35, 43
indices = [3, 11, 19, 27, 35, 43]
M = []
for idx in indices:
    offset = 24 + idx * 38400
    raw_block = data[offset:offset+38400]
    arr = np.frombuffer(raw_block, dtype=np.uint16).reshape((120, 160)).astype(np.float32)
    M.append(arr)

M = np.array(M) # Shape: (6, 120, 160)

# Load a raw frame from the user (we don't have a live raw frame, but we can simulate one or use M[2] + noise)
# Wait, let's just create a mock raw frame which is M[2] + some gradient
raw = M[2] + np.linspace(-100, 100, 160).reshape(1, 160)

# Map raw to interpolated index
# For each pixel, find k such that M[k] <= raw < M[k+1]
clean = np.zeros((120, 160), dtype=np.float32)

for y in range(120):
    for x in range(160):
        r = raw[y, x]
        pixel_curve = M[:, y, x]
        # Find k
        k = 0
        while k < 4 and r > pixel_curve[k+1]:
            k += 1
        
        m0 = pixel_curve[k]
        m1 = pixel_curve[k+1]
        
        if m1 != m0:
            w = (r - m0) / (m1 - m0)
        else:
            w = 0
        clean[y, x] = k + w

cv2.imwrite('/tmp/clean_test.png', cv2.normalize(clean, None, 0, 255, cv2.NORM_MINMAX, dtype=cv2.CV_8U))
print("Test successful")
