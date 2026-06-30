import numpy as np

if __name__ == '__main__':
    # Let's generate a script to output the raw frame and find the coordinates of the min and max
    with open('raw_video.bin', 'rb') as f:
        f.seek(0)
        raw_bytes = f.read(160*120*2)
        raw_frame = np.frombuffer(raw_bytes, dtype=np.uint16).reshape((120, 160))
        
    min_val = np.min(raw_frame)
    max_val = np.max(raw_frame)
    
    # Let's see how many pixels are close to max and min
    print(f"Max value: {max_val}, Min value: {min_val}")
    print(f"Pixels > {max_val-100}: {np.sum(raw_frame > max_val-100)}")
    print(f"Pixels < {min_val+100}: {np.sum(raw_frame < min_val+100)}")

