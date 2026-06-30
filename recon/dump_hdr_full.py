import struct
import numpy as np

with open('/home/delphic/win_share/Delphic/thermal_cam/linux_app/recon/mag_cali.bin', 'rb') as f:
    data = f.read(1024)

print("Header bytes (hex):")
print(" ".join(f"{b:02x}" for b in data[:64]))

# Let's decode as 16-bit integers
ints = np.frombuffer(data[:64], dtype=np.int16)
print("Header as int16:")
print(ints)

# Let's decode as 32-bit integers
ints32 = np.frombuffer(data[:64], dtype=np.int32)
print("Header as int32:")
print(ints32)
