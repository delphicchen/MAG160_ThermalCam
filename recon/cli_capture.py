import sys
import os
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from magcam import MagCamera
import time
import numpy as np

cam = MagCamera()
if not cam.open():
    print("Failed to open camera")
    sys.exit(1)

time.sleep(1) # wait for camera to warm up
for _ in range(5):
    raw, frame = cam.read()
    if raw is not None:
        np.save('recon/raw_test.npy', raw)
        print("Captured raw frame.")
        break
cam.close()
