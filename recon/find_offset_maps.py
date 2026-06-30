import numpy as np

with open('/home/delphic/win_share/Delphic/thermal_cam/linux_app/recon/mag_cali.bin', 'rb') as f:
    data = f.read()

offset = 24
for i in range(48):
    if offset + 38400 > len(data): break
    raw_block = data[offset:offset+38400]
    arr = np.frombuffer(raw_block, dtype=np.uint16).reshape((120, 160)).astype(float)
    
    diff_x = np.mean(np.abs(arr[:, 1:] - arr[:, :-1]))
    diff_y = np.mean(np.abs(arr[1:, :] - arr[:-1, :]))
    smoothness = diff_x + diff_y
    
    mean_val = np.mean(arr)
    std_val = np.std(arr)
    
    print(f"Block {i:2d}: Mean={mean_val:7.1f}, Std={std_val:7.1f}, Rough={smoothness:7.1f}")
    
    offset += 38400
