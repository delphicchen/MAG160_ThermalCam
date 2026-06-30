import numpy as np

with open('/home/delphic/win_share/Delphic/thermal_cam/linux_app/recon/mag_cali.bin', 'rb') as f:
    data = f.read()

offset = 24
for r in range(6):
    print(f"--- Temp Group {r} ---")
    for c in range(8):
        if offset + 38400 > len(data): break
        arr = np.frombuffer(data[offset:offset+38400], dtype=np.uint16)
        print(f"Block {r*8+c:02d}: Mean = {np.mean(arr):.1f}")
        offset += 38400
