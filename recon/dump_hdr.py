import sys
sys.path.append('.')
from magcam import MagCamera
import time

cam = MagCamera()
cam.start()
time.sleep(1)

for _ in range(5):
    # we need to peek at the internal buffer logic
    with cam._lock:
        if hasattr(cam, 'last_hdr'):
            print("Header:", ' '.join(f"{b:02x}" for b in cam.last_hdr))
    time.sleep(0.5)

cam.stop()
