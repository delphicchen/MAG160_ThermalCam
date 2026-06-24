#!/usr/bin/env python3
"""Live probe of the Magnity 833c thermal camera.

Protocol recovered from libcoresdk.so:
  - EP 0x03 OUT: command (8-byte packet = <cmd u32 LE><param u32 LE>)
  - EP 0x82 IN : command response (first 4 bytes echo/header, then payload)
  - EP 0x81 IN : image stream (16-bit pixels)
  - EP 0x84 IN : calibration file (large)
Commands: GetParameter1=0x6BB6B66B GetCaliFile=0x6BB6B670 SetShutterState=0x6BB6B672
          StartTransferImg=0x6BB6B673 StopTransferImg=0x6BB6B674
"""
import sys, time, struct
import usb.core, usb.util

VID, PID = 0x833C, 0x0001
EP_CMD_OUT = 0x03
EP_CMD_IN  = 0x82
EP_IMG_IN  = 0x81
EP_CALI_IN = 0x84

GET_PARAM1       = 0x6BB6B66B
GET_PARAM2       = 0x6BB6B66C
GET_CALI_INFO    = 0x6BB6B66F
GET_CALI_FILE    = 0x6BB6B670
SET_SHUTTER      = 0x6BB6B672
START_TRANSFER   = 0x6BB6B673
STOP_TRANSFER    = 0x6BB6B674
CMD_676          = 0x6BB6B676
CMD_677          = 0x6BB6B677

def hexdump(b, n=64):
    b = b[:n]
    return ' '.join(f'{x:02x}' for x in b)

def main():
    dev = usb.core.find(idVendor=VID, idProduct=PID)
    if dev is None:
        print("device not found"); return
    try:
        if dev.is_kernel_driver_active(0):
            dev.detach_kernel_driver(0)
    except Exception as e:
        print("detach:", e)
    dev.set_configuration(1)
    usb.util.claim_interface(dev, 0)
    print("claimed interface 0")

    def send_cmd(cmd, param=0):
        pkt = struct.pack('<II', cmd, param)
        n = dev.write(EP_CMD_OUT, pkt, timeout=500)
        return n

    def read_resp(maxlen=4096, timeout=2000):
        try:
            data = dev.read(EP_CMD_IN, maxlen, timeout=timeout)
            return bytes(data)
        except usb.core.USBError as e:
            return f"<resp err: {e}>"

    # 1) drain any stale data on cmd-in
    try:
        stale = dev.read(EP_CMD_IN, 4096, timeout=200)
        print("drained stale cmd-in:", len(stale))
    except usb.core.USBError:
        pass

    for name, cmd in [("GetParameter1", GET_PARAM1), ("GetParameter2", GET_PARAM2),
                      ("GetCaliInfo", GET_CALI_INFO)]:
        print(f"\n--- {name} (0x{cmd:08X}) ---")
        try:
            send_cmd(cmd, 0)
        except usb.core.USBError as e:
            print("  send err:", e); continue
        r = read_resp()
        if isinstance(r, bytes):
            print(f"  resp {len(r)} bytes: {hexdump(r, 96)}")
            # try to interpret as u16/u32 fields after a 4-byte header
            if len(r) >= 12:
                u16 = struct.unpack_from('<' + 'H'*((min(len(r),64)-4)//2), r, 4)
                print("  u16[after hdr]:", u16[:24])
        else:
            print("  ", r)

    usb.util.release_interface(dev, 0)

if __name__ == "__main__":
    main()
