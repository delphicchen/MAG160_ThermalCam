import numpy as np

with open('/home/delphic/win_share/Delphic/thermal_cam/linux_app/recon/mag_cali.bin', 'rb') as f:
    data = f.read()

offset = 1024 # header size
blocks = []
for _ in range(48):
    blocks.append(np.frombuffer(data[offset:offset+38400], dtype=np.uint16))
    offset += 38400

# Let's see if shifting by 8192 makes sense
print("High Gain Coefficients (Shifted by 8192?):")
for r in range(6):
    A = np.mean(blocks[r*8 + 2]) - 8192
    B = np.mean(blocks[r*8 + 3]) - 8192
    C = np.mean(blocks[r*8 + 4]) - 8192
    print(f"Temp {r}: A={A:.1f}, B={B:.1f}, C={C:.1f}")

print("\nLow Gain Coefficients (Shifted by 8192?):")
for r in range(6):
    A = np.mean(blocks[r*8 + 5]) - 8192
    B = np.mean(blocks[r*8 + 6]) - 8192
    C = np.mean(blocks[r*8 + 7]) - 8192
    print(f"Temp {r}: A={A:.1f}, B={B:.1f}, C={C:.1f}")
