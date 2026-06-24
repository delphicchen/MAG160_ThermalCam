#!/usr/bin/env python3
"""Image streaming with an EP-0x82 ack read after EVERY command (the cmd-transaction
in the .so always reads EP0x82 after writing EP0x03 — skipping it wedges the device)."""
import struct, time, threading
import numpy as np
import usb.core, usb.util

VID, PID = 0x833C, 0x0001
EP_CMD_OUT, EP_CMD_IN, EP_IMG_IN = 0x03, 0x82, 0x81
P1,P2,CALIINFO = 0x6BB6B66B,0x6BB6B66C,0x6BB6B66F
START,STOP = 0x6BB6B673,0x6BB6B674
MAGIC = 0x1BB1B11B

dev = usb.core.find(idVendor=VID, idProduct=PID)
try:
    if dev.is_kernel_driver_active(0): dev.detach_kernel_driver(0)
except Exception: pass
dev.set_configuration(1); usb.util.claim_interface(dev, 0)

def cmd_ack(c, n=8, param=0, t=2000):
    pkt = struct.pack('<I', c) if n == 4 else struct.pack('<II', c, param)
    dev.write(EP_CMD_OUT, pkt, timeout=500)
    try: return bytes(dev.read(EP_CMD_IN, 4096, timeout=t))
    except usb.core.USBError: return b''

# stop + drain
try: cmd_ack(STOP, 4)
except Exception: pass
for _ in range(6):
    try: dev.read(EP_CMD_IN, 4096, timeout=100)
    except usb.core.USBError: break

r=b''
for _ in range(6):
    r=cmd_ack(P1,8)
    if len(r)>=28: break
    time.sleep(0.15)
W,H=struct.unpack_from('<I',r,20)[0],struct.unpack_from('<I',r,24)[0]
FP=W*H*2
print(f"W={W} H={H} framePix={FP}")
print("  P2 ack len", len(cmd_ack(P2,8)))
print("  caliInfo ack len", len(cmd_ack(CALIINFO,8)))

# clear any stall on the image endpoint
try: dev.clear_halt(EP_IMG_IN); print("cleared halt EP0x81")
except Exception as e: print("clear_halt:", e)

buf=bytearray(); run=[True]; errs=[0]
def reader():
    while run[0]:
        try: buf.extend(bytes(dev.read(EP_IMG_IN, 0x8000, timeout=300)))
        except usb.core.USBError as e:
            if 'timeout' in str(e).lower(): continue
            errs[0]+=1
            if errs[0]>80: break
th=threading.Thread(target=reader,daemon=True); th.start()
time.sleep(0.05)

ack=cmd_ack(START,4)            # send 4-byte start AND read its EP0x82 ack
print(f"START ack len={len(ack)}: {' '.join(f'{x:02x}' for x in ack[:16])}")
time.sleep(2.5)
run[0]=False; th.join(timeout=1)
try: cmd_ack(STOP,4)
except Exception as e: print("stop err:",e)
try: usb.util.release_interface(dev,0)
except Exception: pass

print(f"EP0x81 got {len(buf)} bytes, errs={errs[0]}")
b=bytes(buf); open('/tmp/mag_stream.bin','wb').write(b)
mb=struct.pack('<I',MAGIC); offs=[]; i=b.find(mb)
while i>=0: offs.append(i); i=b.find(mb,i+1)
print(f"frame magic count={len(offs)} first={offs[:5]}")
if buf and not offs:
    print("first 64 bytes:", ' '.join(f'{x:02x}' for x in b[:64]))
if len(offs)>=2:
    per=[offs[k+1]-offs[k] for k in range(len(offs)-1)]
    period=max(set(per),key=per.count)
    print("periods",per[:6],"dominant",period,"hdr+trailer=",period-FP)
    print("hdr:", ' '.join(f'{x:02x}' for x in b[offs[0]:offs[0]+56]))
    hdr=max(0,period-FP); s=offs[0]+hdr
    fr=np.frombuffer(b[s:s+FP],dtype=np.uint16).reshape(H,W)
    print(f"raw16 min={fr.min()} max={fr.max()} mean={fr.mean():.0f} std={fr.std():.0f}")
    from PIL import Image
    lo,hi=np.percentile(fr,1),np.percentile(fr,99)
    Image.fromarray(np.clip((fr.astype(np.float32)-lo)/max(1,hi-lo)*255,0,255).astype(np.uint8)).save('/tmp/mag_frame.png')
    print("SAVED /tmp/mag_frame.png")
