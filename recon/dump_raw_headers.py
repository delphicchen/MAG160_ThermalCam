import sys, usb.core, struct
import time

dev = usb.core.find(idVendor=0x833c, idProduct=0x0001)
if not dev:
    dev = usb.core.find(idVendor=0x833c, idProduct=0x0002)
if not dev:
    print("Camera not found")
    sys.exit(1)

dev.set_configuration()
time.sleep(0.1)
try:
    dev.clear_halt(0x81)
except:
    pass

def cmd(c, p=0, n=8):
    pkt = struct.pack('<II', c, p)[:n]
    dev.write(0x03, pkt, timeout=500)
    try:
        r = bytes(dev.read(0x82, 4096, timeout=1000))
        return r
    except:
        return b''

cmd(0x6BB6B66B) # GET_PARAM1
cmd(0x6BB6B66C) # GET_PARAM2
cmd(0x6BB6B66F) # GET_CALIINFO

cmd(0x6BB6B673, 0, 4) # START_XFER

for _ in range(3):
    try:
        buf = bytes(dev.read(0x81, 0x10000, timeout=1000))
        magic = struct.pack('<I', 0x1BB1B11B)
        i = buf.find(magic)
        if i >= 0 and i + 28 <= len(buf):
            hdr = buf[i:i+28]
            print("HDR:", ' '.join(f"{b:02x}" for b in hdr))
            # try to unpack as ints or shorts
            u16s = struct.unpack('<14H', hdr)
            u32s = struct.unpack('<7I', hdr)
            print("  u16:", u16s)
            print("  u32:", u32s)
    except Exception as e:
        print("Read err", e)

cmd(0x6BB6B674) # STOP
