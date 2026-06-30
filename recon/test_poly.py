import numpy as np
import cv2

# Load a raw frame
raw_frame = np.load('/home/delphic/win_share/Delphic/thermal_cam/linux_app/raw_frame.npy').astype(np.float32)

# Load calibration data
with open('/home/delphic/win_share/Delphic/thermal_cam/linux_app/recon/mag_cali.bin', 'rb') as f:
    data = f.read()

offset = 1024
blocks = []
for _ in range(48):
    blocks.append(np.frombuffer(data[offset:offset+38400], dtype=np.uint16).reshape(120, 160))
    offset += 38400

# Sensor temp was ~25C. Let's interpolate between Temp 1 (19.3C) and Temp 2 (29.1C)
t_sensor = 25.0
t0, t1 = 19.330, 29.108
wt = (t_sensor - t0) / (t1 - t0)

# Get A, B, C for High Gain (Blocks 2, 4, 6)
A0, B0, C0 = blocks[1*8+2], blocks[1*8+4], blocks[1*8+6]
A1, B1, C1 = blocks[2*8+2], blocks[2*8+4], blocks[2*8+6]

A = A0 * (1 - wt) + A1 * wt
B = B0 * (1 - wt) + B1 * wt
C = C0 * (1 - wt) + C1 * wt

# How are they scaled? Let's just try simple shift or raw
# Assuming they are int16 shifted by 8192? Or uint16 scaled?
# Let's just look at the center pixel of A, B, C
y, x = 60, 80
print(f"Center A: {A[y,x]}, B: {B[y,x]}, C: {C[y,x]}, Raw: {raw_frame[y,x]}")

# If it's a polynomial: T = a * R^2 + b * R + c
# The raw count is ~7000-8000. T should be ~25-35.
# If A ~ 7000, B ~ 7000, C ~ 7000... this doesn't directly compute 35.
# Let's just dump these arrays and see if we can fit a linear map.
