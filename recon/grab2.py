#!/usr/bin/env python3
"""Full prepare+stream attempt with a background EP0x81 drainer thread."""
import struct, time, sys, threading
import numpy as np
import usb.core, usb.util

VID, PID = 0x833C, 0x0001
EP_CMD_OUT, EP_CMD_IN, EP_IMG_IN, EP_CALI_IN = 0x03, 0x82, 0x81, 0x84
P1,P2,CALIINFO,CALIFILE = 0x6BB6B66B,0x6BB6B66C,0x6BB6B66F,0x6BB6B670
SHUTTER,START,STOP,C676,C677 = 0x6BB6B672,0x6BB6B673,0x6BB6B674,0x6BB6B676,0x6BB6B677
MAGIC = 0x1BB1B11B

dev = usb.core.find(idVendor=VID, idProduct=PID)
try:
    if dev.is_kernel_driver_active(0): dev.detach_kernel_driver(0)
except Exception: pass
dev.set_configuration(1); usb.util.claim_interface(dev, 0)

def cmd(c, n=8, param=0):
    pkt = struct.pack('<I', c) if n == 4 else struct.pack('<II', c, param)
    dev.write(EP_CMD_OUT, pkt, timeout=500)
def resp(n=4096, t=2000):
    try: return bytes(dev.read(EP_CMD_IN, n, timeout=t))
    except usb.core.USBError: return b''
def drain_cmd():
    for _ in range(8):
        try: dev.read(EP_CMD_IN, 4096, timeout=100)
        except usb.core.USBError: break

try: cmd(STOP, 4)
except Exception: pass
drain_cmd()

# --- params ---
r=b''
for _ in range(6):
    cmd(P1,8); r=resp()
    if len(r)>=28: break
    time.sleep(0.15); drain_cmd()
W,H = struct.unpack_from('<I',r,20)[0], struct.unpack_from('<I',r,24)[0]
print(f"params: W={W} H={H} framePix={W*H*2}")
cmd(P2,8);  r2=resp();  print("  P2 resp len", len(r2))
cmd(CALIINFO,8); ci=resp()
cali_size = struct.unpack_from('<I',ci,4)[0] if len(ci)>=8 else 0
print(f"  caliInfo resp len {len(ci)}, cali_size={cali_size}")

# --- calibration file download from EP 0x84 ---
if cali_size and cali_size < 8_000_000:
    cmd(CALIFILE,8)
    cali=bytearray(); t0=time.time()
    while len(cali)<cali_size and time.time()-t0<8:
        try: cali += bytes(dev.read(EP_CALI_IN, 0x10000, timeout=1000))
        except usb.core.USBError as e:
            if 'timeout' in str(e).lower(): break
            print("  cali read err",e); break
    print(f"  cali downloaded {len(cali)}/{cali_size} bytes")
    open('/tmp/mag_cali.bin','wb').write(bytes(cali))

# --- prepare cmds 676/677 ---
for nm,c in [("676",C676),("677",C677)]:
    try: cmd(c,8); rr=resp(t=800); print(f"  {nm} resp len {len(rr)}")
    except usb.core.USBError as e: print(f"  {nm} err {e}")

# --- background drainer on EP 0x81 ---
buf=bytearray(); run=[True]; errs=[0]
def reader():
    while run[0]:
        try: buf.extend(bytes(dev.read(EP_IMG_IN, 0x8000, timeout=300)))
        except usb.core.USBError as e:
            if 'timeout' in str(e).lower(): continue
            errs[0]+=1
            if errs[0]>50: break
th=threading.Thread(target=reader,daemon=True); th.start()
time.sleep(0.05)
cmd(START,4)            # 4-byte StartTransferImg
print("started; draining EP0x81 ...")
time.sleep(2.5)
run[0]=False; th.join(timeout=1)
try: cmd(STOP,4)
except Exception as e: print("stop err:",e)
try: usb.util.release_interface(dev,0)
except Exception: pass

print(f"EP0x81 got {len(buf)} bytes, errs={errs[0]}")
open('/tmp/mag_stream.bin','wb').write(bytes(buf))
b=bytes(buf); mb=struct.pack('<I',MAGIC); offs=[]; i=b.find(mb)
while i>=0: offs.append(i); i=b.find(mb,i+1)
print(f"frame magic count={len(offs)} first={offs[:5]}")
if len(offs)>=2:
    per=[offs[k+1]-offs[k] for k in range(len(offs)-1)]
    period=max(set(per),key=per.count)
    print("periods",per[:6],"dominant",period,"hdr+trailer=",period-W*H*2)
    print("hdr bytes:", ' '.join(f'{x:02x}' for x in b[offs[0]:offs[0]+56]))
    hdr=max(0,period-W*H*2); s=offs[0]+hdr
    fr=np.frombuffer(b[s:s+W*H*2],dtype=np.uint16).reshape(H,W)
    print(f"raw16 min={fr.min()} max={fr.max()} mean={fr.mean():.0f} std={fr.std():.0f}")
    from PIL import Image
    lo,hi=np.percentile(fr,1),np.percentile(fr,99)
    Image.fromarray(np.clip((fr.astype(np.float32)-lo)/max(1,hi-lo)*255,0,255).astype(np.uint8)).save('/tmp/mag_frame.png')
    print("saved /tmp/mag_frame.png")
elif buf:
    print("first 64:", ' '.join(f'{x:02x}' for x in b[:64]))
