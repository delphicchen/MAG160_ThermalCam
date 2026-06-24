#!/usr/bin/env python3
"""Driver for the Magnity 833c USB thermal camera on Linux.

Pure-Python (pyusb) reimplementation of the parts of the Android coresdk needed for a
live viewer. See PROTOCOL.md for the reverse-engineering details.

Usage:
    cam = MagCamera(); cam.open(); cam.start()
    cam.trigger_ffc()                  # flat-field (shutter) correction
    frame = cam.get_frame()            # HxW uint16, FFC-corrected if reference set
    cam.stop(); cam.close()
"""
import struct, time, threading
import numpy as np
import usb.core, usb.util

VID, PID = 0x833C, 0x0001
EP_CMD_OUT, EP_CMD_IN, EP_IMG_IN, EP_CALI_IN = 0x03, 0x82, 0x81, 0x84

# command words (see PROTOCOL.md)
GET_PARAM1   = 0x6BB6B66B
GET_PARAM2   = 0x6BB6B66C
GET_CALIINFO = 0x6BB6B66F
GET_CALIFILE = 0x6BB6B670
SET_SHUTTER  = 0x6BB6B672
START_XFER   = 0x6BB6B673
STOP_XFER    = 0x6BB6B674

FRAME_MAGIC  = 0x1BB1B11B
HDR_LEN      = 28          # bytes before pixel payload
TAIL_LEN     = 28          # bytes after pixel payload


class MagCamera:
    def __init__(self):
        self.dev = None
        self.W = self.H = 0
        self.frame_bytes = 0
        self.period = 0
        self._reader = None
        self._run = False
        self._lock = threading.Lock()
        self._latest = None         # raw uint16 HxW
        self._ffc_ref = None        # float32 HxW reference (shutter-closed)
        self.frame_count = 0

    # ---- low level ----
    def _cmd(self, cmd, param=0, n=8, ack=True, ack_timeout=2000):
        pkt = struct.pack('<I', cmd) if n == 4 else struct.pack('<II', cmd, param)
        self.dev.write(EP_CMD_OUT, pkt, timeout=500)
        if not ack:
            return b''
        try:
            return bytes(self.dev.read(EP_CMD_IN, 4096, timeout=ack_timeout))
        except usb.core.USBError:
            return b''

    def _drain_cmd(self):
        for _ in range(8):
            try:
                self.dev.read(EP_CMD_IN, 4096, timeout=100)
            except usb.core.USBError:
                break

    # ---- lifecycle ----
    def open(self):
        self.dev = usb.core.find(idVendor=VID, idProduct=PID)
        if self.dev is None:
            raise RuntimeError("Magnity camera (833c:0001) not found")
        try:
            if self.dev.is_kernel_driver_active(0):
                self.dev.detach_kernel_driver(0)
        except Exception:
            pass
        self.dev.set_configuration(1)
        usb.util.claim_interface(self.dev, 0)
        # make sure no stale stream is running
        try:
            self._cmd(STOP_XFER, n=4)
        except Exception:
            pass
        self._drain_cmd()
        self._read_params()

    def _read_params(self):
        r = b''
        for _ in range(6):
            r = self._cmd(GET_PARAM1)
            if len(r) >= 28:
                break
            time.sleep(0.15)
        if len(r) < 28:
            raise RuntimeError("GetParameter1 failed")
        self.W = struct.unpack_from('<I', r, 20)[0]
        self.H = struct.unpack_from('<I', r, 24)[0]
        self.fps = struct.unpack_from('<I', r, 28)[0]
        self.frame_bytes = self.W * self.H * 2
        self.period = HDR_LEN + self.frame_bytes + TAIL_LEN
        # the app also issues these during init; read their acks
        self._cmd(GET_PARAM2)
        self._cmd(GET_CALIINFO)
        return self.W, self.H, self.fps

    def start(self):
        try:
            self.dev.clear_halt(EP_IMG_IN)
        except Exception:
            pass
        self._run = True
        self._reader = threading.Thread(target=self._reader_loop, daemon=True)
        self._reader.start()
        time.sleep(0.05)
        self._cmd(START_XFER, n=4)      # 4-byte start + ack

    def _reader_loop(self):
        buf = bytearray()
        magic = struct.pack('<I', FRAME_MAGIC)
        while self._run:
            try:
                buf.extend(bytes(self.dev.read(EP_IMG_IN, 0x8000, timeout=300)))
            except usb.core.USBError as e:
                if 'timeout' in str(e).lower():
                    continue
                break
            # parse complete frames out of buf
            while True:
                i = buf.find(magic)
                if i < 0:
                    if len(buf) > 4:
                        del buf[:-4]      # keep tail that may hold a partial magic
                    break
                if len(buf) - i < self.period:
                    if i > 0:
                        del buf[:i]
                    break
                payload = bytes(buf[i + HDR_LEN: i + HDR_LEN + self.frame_bytes])
                del buf[:i + self.period]
                fr = np.frombuffer(payload, dtype=np.uint16).reshape(self.H, self.W)
                with self._lock:
                    self._latest = fr
                    self.frame_count += 1

    def get_raw(self, timeout=2.0):
        """Latest raw uint16 frame (HxW), or None. Checks at least once even if
        timeout==0 (so a GUI poll returns the current frame immediately)."""
        t0 = time.time()
        while True:
            with self._lock:
                if self._latest is not None:
                    return self._latest.copy()
            if time.time() - t0 >= timeout:
                return None
            time.sleep(0.01)

    def get_frame(self, timeout=2.0):
        """Latest frame, FFC-corrected to int32 if a reference is set, else raw uint16."""
        raw = self.get_raw(timeout)
        if raw is None:
            return None
        if self._ffc_ref is not None:
            corr = raw.astype(np.int32) - self._ffc_ref.astype(np.int32)
            return corr + int(self._ffc_ref.mean())
        return raw

    # ---- flat-field correction ----
    def trigger_ffc(self, settle=0.4, navg=4):
        """Close shutter, average a few frames as the flat-field reference, reopen."""
        self._cmd(SET_SHUTTER, 0)        # close shutter
        time.sleep(settle)
        with self._lock:
            self._latest = None
        frames = []
        t0 = time.time()
        while len(frames) < navg and time.time() - t0 < 2.0:
            f = self.get_raw(timeout=0.5)
            if f is not None:
                frames.append(f.astype(np.float32))
                with self._lock:
                    self._latest = None
                time.sleep(1.0 / max(1, self.fps))
        if frames:
            self._ffc_ref = np.mean(frames, axis=0)
        self._cmd(SET_SHUTTER, 1)        # open shutter
        time.sleep(settle)
        return self._ffc_ref is not None

    def clear_ffc(self):
        self._ffc_ref = None

    def stop(self):
        self._run = False
        if self._reader:
            self._reader.join(timeout=1.0)
        try:
            self._cmd(STOP_XFER, n=4)
        except Exception:
            pass

    def close(self):
        try:
            usb.util.release_interface(self.dev, 0)
        except Exception:
            pass
        self.dev = None


if __name__ == "__main__":
    cam = MagCamera(); cam.open()
    print(f"camera {cam.W}x{cam.H} @ {cam.fps}fps")
    cam.start()
    print("warming up..."); time.sleep(1.0)
    print("frames so far:", cam.frame_count)
    print("triggering FFC...")
    ok = cam.trigger_ffc()
    print("FFC ref set:", ok)
    time.sleep(0.5)
    raw = cam.get_raw(); fr = cam.get_frame()
    cam.stop(); cam.close()
    if fr is not None:
        from PIL import Image
        def norm(x):
            x = x.astype(np.float32)
            lo, hi = np.percentile(x, 2), np.percentile(x, 98)
            return np.clip((x - lo) / max(1, hi - lo) * 255, 0, 255).astype(np.uint8)
        Image.fromarray(norm(raw)).resize((640, 480), Image.NEAREST).save('/tmp/mag_raw.png')
        Image.fromarray(norm(fr)).resize((640, 480), Image.NEAREST).save('/tmp/mag_ffc.png')
        print(f"raw: min={raw.min()} max={raw.max()}  ffc-corrected: min={fr.min()} max={fr.max()}")
        print("saved /tmp/mag_raw.png and /tmp/mag_ffc.png")
