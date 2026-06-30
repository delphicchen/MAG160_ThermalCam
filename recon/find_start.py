import numpy as np

with open('/home/delphic/win_share/Delphic/thermal_cam/linux_app/recon/mag_cali.bin', 'rb') as f:
    data = f.read()

# Bad pixel mask usually has a lot of 0s or 1s, or 65535s.
# Let's look for a block of 38400 bytes that looks like a mask.
# Or just print the data starting from byte 0 in chunks of 4.
ints = np.frombuffer(data[:512], dtype=np.int32)
print("Ints:", ints[:64])
