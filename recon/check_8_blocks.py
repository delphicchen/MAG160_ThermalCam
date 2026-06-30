import numpy as np

with open('/home/delphic/win_share/Delphic/thermal_cam/linux_app/recon/mag_cali.bin', 'rb') as f:
    data = f.read()

for i in range(8):
    offset = 128 + i * 38400
    arr = np.frombuffer(data[offset:offset+38400], dtype=np.uint16)
    print(f"Block {i}: mean={np.mean(arr):.2f}, min={np.min(arr)}, max={np.max(arr)}, std={np.std(arr):.2f}")
