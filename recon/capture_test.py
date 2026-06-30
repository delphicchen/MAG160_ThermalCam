import cv2
import numpy as np
import time

cap = cv2.VideoCapture(1)
cap.set(cv2.CAP_PROP_CONVERT_RGB, 0)
cap.set(cv2.CAP_PROP_FORMAT, -1)

frames = []
for _ in range(5):
    ret, frame = cap.read()
    if ret:
        frames.append(frame)
    time.sleep(0.1)

cap.release()

if frames:
    print("Shape:", frames[-1].shape, "Dtype:", frames[-1].dtype)
    np.save('recon/raw_test.npy', frames[-1])
else:
    print("Could not capture frame.")
