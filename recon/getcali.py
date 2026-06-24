#!/usr/bin/env python3
"""Download the camera's internal calibration file (GetCaliFile, EP 0x84).
Flow from libcoresdk.so 0x4a828: send 0x6BB6B670 (4-byte) -> EP-0x82 ack carries the
file size -> read that many bytes from the cali endpoint in <=0x80000 chunks."""
import struct, time, sys
import usb.core, usb.util

VID, PID = 0x833C, 0x0001
EP_CMD_OUT, EP_CMD_IN, EP_CALI_IN = 0x03, 0x82, 0x84
P1,P2,CALIINFO,CALIFILE,STOP = 0x6BB6B66B,0x6BB6B66C,0x6BB6B66F,0x6BB6B670,0x6BB6B674

dev = usb.core.find(idVendor=VID, idProduct=PID)
if dev is None: sys.exit("device not found")
try:
    if dev.is_kernel_driver_active(0): dev.detach_kernel_driver(0)
except Exception: pass
dev.set_configuration(1); usb.util.claim_interface(dev,0)

def cmd_ack(c,n=8,p=0,t=2000):
    pkt=struct.pack('<I',c) if n==4 else struct.pack('<II',c,p)
    dev.write(EP_CMD_OUT,pkt,timeout=500)
    try: return bytes(dev.read(EP_CMD_IN,4096,timeout=t))
    except usb.core.USBError: return b''

try: cmd_ack(STOP,4)
except Exception: pass
for _ in range(6):
    try: dev.read(EP_CMD_IN,4096,timeout=100)
    except usb.core.USBError: break

r=b''
for _ in range(6):
    r=cmd_ack(P1)
    if len(r)>=28: break
    time.sleep(0.15)
W,H=struct.unpack_from('<I',r,20)[0],struct.unpack_from('<I',r,24)[0]
print(f"W={W} H={H}")
print("P2 ack",len(cmd_ack(P2)))
ci=cmd_ack(CALIINFO); print("caliInfo ack",len(ci),"->",' '.join(f'{x:02x}' for x in ci))

# GetCaliFile: send 4-byte cmd, ack carries size
ack=cmd_ack(CALIFILE,n=4,t=3000)
print(f"CALIFILE ack {len(ack)} bytes: {' '.join(f'{x:02x}' for x in ack[:24])}")
# size candidates (after 4-byte ack header)
size=None
for off in (4,8):
    if len(ack)>=off+4:
        v=struct.unpack_from('<I',ack,off)[0]
        print(f"  size@+{off} = {v} (0x{v:x})")
        if 1000 < v < 0x6400000 and size is None: size=v; size_off=off
if size is None:
    print("no plausible size in ack; aborting"); usb.util.release_interface(dev,0); sys.exit(1)
print(f"=> downloading {size} bytes from EP 0x{EP_CALI_IN:02x} ...")

cali=bytearray(); t0=time.time()
while len(cali)<size and time.time()-t0<60:
    want=min(0x80000, size-len(cali))
    try:
        chunk=dev.read(EP_CALI_IN, want, timeout=60000)
        cali+=bytes(chunk)
    except usb.core.USBError as e:
        print("  read err after",len(cali),"bytes:",e); break
print(f"downloaded {len(cali)}/{size} bytes in {time.time()-t0:.1f}s")
open('/tmp/mag_cali.bin','wb').write(bytes(cali))
print("saved /tmp/mag_cali.bin")
# quick structure peek
import numpy as np
b=bytes(cali)
print("head:", ' '.join(f'{x:02x}' for x in b[:48]))
if len(b)>=16:
    print("first u32s:", struct.unpack_from('<8I', b, 0))
try: cmd_ack(STOP,4)
except Exception: pass
usb.util.release_interface(dev,0)
