import struct
with open('/home/delphic/win_share/Delphic/thermal_cam/linux_app/recon/mag_cali.bin', 'rb') as f:
    hdr = f.read(1024)
    ints = struct.unpack('<256i', hdr)
    print("Header ints:", ints[:20])
