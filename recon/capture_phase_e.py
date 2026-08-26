#!/usr/bin/env python3
"""Phase E capture: log raw frames + FPA temp + FFC references over ~4 minutes.

Goal: measure temporal stability of the faithful NUC chain (grid-ref offset_ref,
no levelBaseline) vs the current app chain (ffcRef offset + levelBaseline),
across natural FPA warmup and forced FFC events.

Output: /tmp/opencode/phaseE_capture.npz with
  frames   (N,120,160) u16 raw
  fpa      (N,) u32 sensor-temp anchor units
  t        (N,) seconds since start
  ffc_events [(t, fpa_before, fpa_after)]
  ffc_refs [(t, (120,160) f32 shutter dark frame)]
"""
import sys, os, time
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))          # repo root for magcam
import numpy as np
from magcam import MagCamera

DURATION_S = 240          # 4 minutes
SAMPLE_EVERY = 2.0        # one frame per 2 s
FFC_AT = [60, 150]        # also force FFCs at these times (plus one at start)

def main():
    cam = MagCamera()
    cam.open()
    print(f"camera {cam.W}x{cam.H}")
    cam.start()
    time.sleep(1.0)

    frames, fpas, ts = [], [], []
    ffc_events, ffc_refs = [], []
    t0 = time.time()
    next_sample = 0.0
    ffc_queue = list(FFC_AT)
    last_ffc_t = -10.0

    # initial FFC to establish baseline
    ok = cam.trigger_ffc()
    ffc_refs.append((0.0, cam._ffc_ref.copy()))
    ffc_events.append((0.0, None, cam.sensor_temp_raw()))
    last_ffc_t = 0.0
    print(f"t=0 initial FFC ok={ok} fpa={cam.sensor_temp_raw()}")

    while True:
        now = time.time() - t0
        if now >= DURATION_S: break

        # scheduled forced FFC
        if ffc_queue and now >= ffc_queue[0]:
            ffc_queue.pop(0)
            fpa_b = cam.sensor_temp_raw()
            ok = cam.trigger_ffc()
            ffc_refs.append((now, cam._ffc_ref.copy()))
            ffc_events.append((now, fpa_b, cam.sensor_temp_raw()))
            last_ffc_t = now
            print(f"t={now:.0f}s FFC ok={ok} fpa {fpa_b} -> {cam.sensor_temp_raw()}")

        if now >= next_sample:
            raw = cam.get_raw(timeout=2.0)
            if raw is not None:
                frames.append(raw)
                fpas.append(cam.sensor_temp_raw())
                ts.append(now)
                next_sample += SAMPLE_EVERY
        time.sleep(0.05)

    cam.stop(); cam.close()
    F = np.stack(frames); P = np.array(fpas, np.uint32); T = np.array(ts)
    print(f"captured {len(F)} frames over {T[-1]:.0f}s; fpa {P.min()}..{P.max()}")
    np.savez_compressed("/tmp/opencode/phaseE_capture.npz",
                        frames=F.astype(np.uint16), fpa=P, t=T,
                        ffc_events=np.array([(a, -1 if b is None else b, c) for a,b,c in ffc_events]),
                        ffc_refs=np.stack([r for _, r in ffc_refs]),
                        ffc_times=np.array([t for t, _ in ffc_refs]))
    print("saved /tmp/opencode/phaseE_capture.npz")

if __name__ == "__main__":
    main()
