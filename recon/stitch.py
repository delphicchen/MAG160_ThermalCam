import cv2
import numpy as np
import glob

files = sorted(glob.glob('recon/blocks/block_*.png'))
imgs = [cv2.imread(f, 0) for f in files]

# 48 images -> 8 columns, 6 rows
rows = []
for r in range(6):
    row_imgs = imgs[r*8:(r+1)*8]
    rows.append(np.hstack(row_imgs))
    
full = np.vstack(rows)
cv2.imwrite('recon/all_blocks.png', full)
