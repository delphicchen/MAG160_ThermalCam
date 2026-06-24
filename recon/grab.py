#!/usr/bin/env python3
"""Stream from the Magnity 833c camera, drain EP 0x81 fast, align on the frame
magic 0x1BB1B11B, and dump one frame as PNG.

Lessons from libcoresdk.so:
  - StartTransferImg is a 4-byte command (just the cmd word, no param).
  - The host must drain EP 0x81 continuously or the device watchdog-resets.
  - Image stream is magic-delimited: each frame starts with 0x1BB1B11B.
"""
import struct, time, sys
import numpy as np
import usb.core, usb.util

VID, PID = 0x833C, 0x0001
EP_CMD_OUT, EP_CMD_IN, EP_IMG_IN = 0x03, 0x82, 0x81
GET_PARAM1, START_TRANSFER, STOP_TRANSFER = 0x6BB6B66B, 0x6BB6B673, 0x6BB6B674
MAGIC = 0x1BB1B11B

def open_dev():
    dev = usb.core.find(idVendor=VID, idProduct=PID)
    if dev is None:
        sys.exit("device not found")
    try:
        if dev.is_kernel_driver_active(0): dev.detach_kernel_driver(0)
    except Exception: pass
    dev.set_configuration(1)
    usb.util.claim_interface(dev, 0)
    return dev

dev = open_dev()
def cmd(c, payload_len=4, param=0):
    pkt = struct.pack('<I', c) if payload_len == 4 else struct.pack('<II', c, param)
    dev.write(EP_CMD_OUT, pkt, timeout=500)
def resp(n=4096, t=2000):
    try: return bytes(dev.read(EP_CMD_IN, n, timeout=t))
    except usb.core.USBError: return b''
def drain_cmd():
    for _ in range(8):
        try: dev.read(EP_CMD_IN, 4096, timeout=100)
        except usb.core.USBError: break

try: cmd(STOP_TRANSFER, 4)
except Exception: pass
drain_cmd()

r = b''
for _ in range(6):
    cmd(GET_PARAM1, 8); r = resp()
    if len(r) >= 28: break
    time.sleep(0.15); drain_cmd()
W = struct.unpack_from('<I', r, 20)[0]; H = struct.unpack_from('<I', r, 24)[0]
FRAME_PIX = W*H*2
print(f"W={W} H={H} frame_pixels={FRAME_PIX} bytes")

# start streaming: send 4-byte StartTransferImg, then drain hard
cmd(START_TRANSFER, 4)
buf = bytearray()
t0 = time.time(); reads = 0; errs = 0
while time.time() - t0 < 3.0 and len(buf) < 600000:
    try:
        chunk = dev.read(EP_IMG_IN, 0x8000, timeout=500)
        buf += bytes(chunk); reads += 1
    except usb.core.USBError as e:
        errs += 1
        if 'timeout' in str(e).lower(): continue
        print("read err:", e); break
try: cmd(STOP_TRANSFER, 4)
except Exception as e: print("stop err (device may have reset):", e)
try: usb.util.release_interface(dev, 0)
except Exception: pass

print(f"got {len(buf)} bytes, reads={reads}, errs={errs}, rate={len(buf)/(time.time()-t0)/1024:.0f} KB/s")
open('/tmp/mag_stream.bin','wb').write(buf)

# find frame magics
data = np.frombuffer(bytes(buf), dtype=np.uint8)
mb = struct.pack('<I', MAGIC)
offs = []
i = bytes(buf).find(mb)
while i >= 0:
    offs.append(i); i = bytes(buf).find(mb, i+1)
print(f"magic 0x{MAGIC:08X} found at {len(offs)} offsets; first few: {offs[:6]}")
if len(offs) >= 2:
    periods = [offs[k+1]-offs[k] for k in range(len(offs)-1)]
    print("frame periods (bytes):", periods[:8])
    # dump header of first frame
    h0 = offs[0]
    print("frame header bytes:", ' '.join(f'{b:02x}' for b in buf[h0:h0+56]))
    period = max(set(periods), key=periods.count)
    print("dominant period:", period, " expected pixels:", FRAME_PIX, " => header+trailer:", period-FRAME_PIX)
    # try to locate pixel block: assume header of size hdr then FRAME_PIX pixels
    for hdr in (period-FRAME_PIX, 0x1c, 0x38, 0x40):
        start = h0 + hdr
        if start+FRAME_PIX <= len(buf):
            fr = np.frombuffer(bytes(buf[start:start+FRAME_PIX]), dtype=np.uint16).reshape(H, W)
            print(f"  hdr={hdr}: raw16 min={fr.min()} max={fr.max()} mean={fr.mean():.0f} std={fr.std():.0f}")
    # save with best-guess header = period-FRAME_PIX (clamped >=0)
    hdr = max(0, period-FRAME_PIX)
    start = h0 + hdr
    fr = np.frombuffer(bytes(buf[start:start+FRAME_PIX]), dtype=np.uint16).reshape(H, W)
    from PIL import Image
    lo, hi = np.percentile(fr, 1), np.percentile(fr, 99)
    norm = np.clip((fr.astype(np.float32)-lo)/max(1,(hi-lo))*255, 0, 255).astype(np.uint8)
    Image.fromarray(norm).save('/tmp/mag_frame.png')
    print("saved /tmp/mag_frame.png (hdr=%d)" % hdr)
else:
    print("not enough frames; first 64 bytes:", ' '.join(f'{b:02x}' for b in buf[:64]))
