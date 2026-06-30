import numpy as np

with open('/home/delphic/win_share/Delphic/thermal_cam/linux_app/recon/mag_cali.bin', 'rb') as f:
    data = f.read()

# Try offset 13216
print("Ints at 13216-16:", np.frombuffer(data[13216-16:13216], dtype=np.uint16))
print("Ints at 13216:", np.frombuffer(data[13216:13216+16], dtype=np.uint16))

# Let's search for a massive block of 0s or 1s (mask block).
# A mask block usually has 65535 or 0.
for i in range(0, 20000, 4):
    arr = np.frombuffer(data[i:i+38400], dtype=np.uint16)
    if np.sum(arr == 0) > 10000 and np.sum(arr == 1) > 10000:
        print(f"Found mask starting at {i}")
        break

# Actually, the mask might just be small values. Let's find the first index where data looks like an image.
