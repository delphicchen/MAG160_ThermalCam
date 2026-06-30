import numpy as np

with open('/home/delphic/win_share/Delphic/thermal_cam/linux_app/recon/mag_cali.bin', 'rb') as f:
    data = f.read()

indices = [18, 19, 20, 21, 22, 23]
for i, idx in enumerate(indices):
    offset = 24 + idx * 38400
    raw_block = data[offset:offset+38400]
    arr = np.frombuffer(raw_block, dtype=np.uint16).reshape((120, 160)).astype(np.float32)
    
    fmap = arr - np.mean(arr)
    np.save(f'/home/delphic/win_share/Delphic/thermal_cam/linux_app/factory_offset_{i}.npy', fmap)
    print(f"Saved factory_offset_{i}.npy from block {idx}")
