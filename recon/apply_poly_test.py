import numpy as np
import cv2

# Parse raw frame from one of the scripts
# Since I don't have raw_frame.npy, let's grab a frame from raw_video.bin if it exists.
import os
if not os.path.exists('raw_video.bin'):
    print("No raw video found.")
else:
    with open('raw_video.bin', 'rb') as f:
        f.seek(0)
        raw_bytes = f.read(160*120*2)
        raw_frame = np.frombuffer(raw_bytes, dtype=np.uint16).reshape((120, 160)).astype(np.float32)
        
    with open('recon/mag_cali.bin', 'rb') as f:
        data = f.read()
    
    offset = 1024
    blocks = []
    for _ in range(48):
        blocks.append(np.frombuffer(data[offset:offset+38400], dtype=np.uint16).reshape((120, 160)))
        offset += 38400
        
    # Let's try T=2 (Group 2)
    A = blocks[2*8+2].astype(np.float32) - 8192
    B = blocks[2*8+4].astype(np.float32) - 8192
    C = blocks[2*8+6].astype(np.float32) - 8192
    
    # Try the mapping!
    # T = a * Raw^2 + b * Raw + c ?
    # Usually it's Raw_clean = Raw_raw + Offset + Gain * ...
    # Let's try: T = (A * raw_frame**2 + B * raw_frame + C) / some_scale
    
    # What if it's not quadratic? What if A, B, C are for a completely different formula?
    
    # Let's just output the original raw and the A, B, C maps as images so we can see the lines.
    cv2.imwrite('recon/proper_blocks/raw_frame.png', cv2.normalize(raw_frame, None, 0, 255, cv2.NORM_MINMAX, dtype=cv2.CV_8U))
    cv2.imwrite('recon/proper_blocks/A_map.png', cv2.normalize(A, None, 0, 255, cv2.NORM_MINMAX, dtype=cv2.CV_8U))
    cv2.imwrite('recon/proper_blocks/B_map.png', cv2.normalize(B, None, 0, 255, cv2.NORM_MINMAX, dtype=cv2.CV_8U))
    cv2.imwrite('recon/proper_blocks/C_map.png', cv2.normalize(C, None, 0, 255, cv2.NORM_MINMAX, dtype=cv2.CV_8U))
    
    print("Done generating test maps.")
