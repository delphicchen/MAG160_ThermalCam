import numpy as np
import cv2

for i in range(12):
    m = np.load(f'factory_flat_{i}.npy')
    # normalize for visualization
    m_norm = cv2.normalize(m, None, 0, 255, cv2.NORM_MINMAX, dtype=cv2.CV_8U)
    cv2.imwrite(f'/tmp/map_{i}.png', m_norm)

