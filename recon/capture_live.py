import cv2
import numpy as np
import time
import os
import sys

sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from magcam import MagCamera

cam = MagCamera()
if not cam.open():
    print("Failed to open camera. Exiting.")
    sys.exit(1)

time.sleep(1) # wait for camera to warm up
raw, frame = cam.read()
if raw is not None:
    np.save('recon/live_raw.npy', raw)
    print("Captured live_raw.npy")
else:
    print("Read failed.")
cam.close()
