# Magnity MAG-Mx 833c Thermal Camera — Reverse-Engineering & Pipeline Reference

Single-entry reference for agents working on this project. The camera ships only a
closed Android APK (`MAG-Mx.apk`); its protocol and radiometry were reverse-engineered
from the APK's native libs + live USB probing. This file maps **where the knowledge
lives** and **how the pipeline fits together**, pointing to the authoritative sources
for depth.

> Reading order for a new agent:
> `README.md` → `PROTOCOL.md` → `recon/EMULATION_NUC.md` + `docs/algorithm_spec.html §4.1`
> → `factory_nuc_grid.py` + `radiometry.py` → `android2/.../pipeline/FactoryNuc.kt`
> + `Radiometry.kt` → `android2/.../ui/ViewerViewModel.kt`.

---

## 1. File map

| Path | Role |
|---|---|
| `PROTOCOL.md` | Full USB protocol: command words, endpoints, the mandatory EP-0x82 ack rule, FFC, frame format, known gaps. |
| `recon/EMULATION_NUC.md` | **How the factory NUC was reversed** — emulating the SDK's ARM build chain (Unicorn), bit-exact validation vs firmware. |
| `docs/algorithm_spec.html` | Algorithm spec; §4.1 is the factory NUC formula. |
| `recon/PLAN_FACTORY_TEMP.md` | Plan for drift-free, factory-level absolute temperature (level-lock trim, per-frame `(k,b)`). |
| `magcam.py` | Proven Linux driver (open / stream / FFC / get_frame). |
| `radiometry.py` | Planck-LUT radiometry `radiance = a·raw + b → °C`; inverse bit-verified vs Kotlin. |
| `factory_nuc_grid.py` | Build + `apply()` of the factory per-pixel piecewise NUC; bit-exact reference. |
| `enhance.py` / `viewer.py` | Linux reference pipeline + live viewer. |
| `ddt.py` | Radiometric `.ddt` snapshot format (interchangeable Linux ↔ Android). |
| `android/.../pipeline/Enhancer.kt` | Older Android port: flat-field + **two-point per-pixel NUC** `a·f + b`, BPC, temporal, CLAHE. |
| `android2/.../usb/MagCamera.kt` | **Current** Android USB driver (clearHalt, stale-ack drain, usbLock serialization). |
| `android2/.../pipeline/FactoryNuc.kt` | **Current** factory NUC Kotlin port: `apply`, `seamResidual`, `offsetPattern`. |
| `android2/.../pipeline/Radiometry.kt` | **Current** Planck-LUT radiometry Kotlin port. |
| `android2/.../ui/ViewerViewModel.kt` | **Current** runtime: `process()`, `afterFfc()`, FFC watchdog, stream-health/recover. |
| `android2/.../pipeline/ImageOps.kt` | median / bilateral / bicubic / CLAHE primitives. |
| `android2/.../pipeline/Palettes.kt` | 256-entry palette LUTs. |

Raw disassembly evidence lives in `recon/`: `disasm.py`, `decomp_c_*.c` / `decomp_jni_*.c`
(decompiled C from `libcoresdk.so`), `emu_*.py` (Unicorn ARM emulation harness).

---

## 2. USB protocol essentials (detail in `PROTOCOL.md`)

- **VID/PID** `0x833C` / `{0x0001, 0x0002}`. Bulk endpoints: CMD_OUT `0x03`,
  CMD_IN `0x82`, IMG_IN `0x81`, CALI_IN `0x84`.
- **Transport**: every command written to EP-0x03 must be followed by a read of its
  EP-0x82 ack, or the firmware **wedges and re-enumerates** (new USB address). This is
  the single most important protocol gotcha — handled in `MagCamera.cmd()` by
  serializing all transfers (`usbLock`) and draining stale EP-0x82 data.
- **Key commands**: `GET_PARAM1/2`, `GET_CALIINFO`, `GET_CALIFILE` (downloads
  `mag_cali.bin` from EP-0x84), `SET_SHUTTER` (0=close/1=open for FFC),
  `START_XFER`/`STOP_XFER` (4-byte, ack required).
- **Frame format**: little-endian magic `0x1BB1B11B` → 28-byte header → `W·H·2` bytes
  (uint16) → 28-byte tail. Tail offset 8 (u32) is the **live FPA/sensor temperature**
  (`sensorTempRaw()`), used to select/interpolate the NUC grid.
- **Re-enumeration**: the camera re-enumerates on protocol errors. The udev rule
  (root) matches by VID so access survives; but Android key-permission by *device
  instance*, so a new address → a fresh USB-permission prompt (unavoidable on-device).

---

## 3. Temperature calculation flow

```
raw (uint16 counts)
   │  ffcRef = averaged shutter-dark frame (FFC)  →  offset_ref
   ▼
FactoryNUC.apply(raw, fpa, ffcRef)            # per-pixel piecewise correction
   │  v = (raw - ffcRef) >> 1
   │  seg = first s with v > breakpoint[i,s]  (else last)
   │  out = clamp( (v · gain[i,seg]) >> shift + offset[i,seg], 0, 65535 )
   ▼
counts (radiometrically corrected, FPN removed)
   │
   ▼
Radiometry.outToCelsius(counts)              # Planck LUT: radiance = a·counts + b → °C
   ▼
temperature (°C)
```

- **Factory NUC tables** (`factory_nuc_grid.npz`, built by `recon/build_nuc_grid.py`
  from `mag_cali.bin`): `fpa (N,)`, `ref (N,H,W)`, `breakpoints (N,H,W,nseg-1)`,
  `gain (N,H,W,nseg)` u16, `offset (N,H,W,nseg)` u16, `nseg`, `shift`. Selected by
  **nearest FPA grid point** and interpolated (`FactoryNuc.nearestIndex`).
- **`ffcRef`** is the live shutter-dark average; it is the `offset_ref` so the NUC
  output is self-consistent (using the grid `ref` instead saturates ~35% of pixels).
- **Radiometry** (`radiometry.py`): Planck curves extracted from the SDK; 8 LUTs.
  `(a, b)` are recovered from **user 2-point calibration** (tap a known-temp object →
  *+ Add cal point* / `refineSpot`). Slope `a` preserved; `b` (offset) shifted.
- **Level-lock trim** (`ViewerViewModel`): on FPA-grid switch or FFC refresh,
  `process()` recomputes the previous configuration on the same frame and folds the
  median difference into `trim` so the reading never jumps.
- **Drift root cause** (`recon/PLAN_FACTORY_TEMP.md`): the shipped port computes
  `(a,b)` once; every auto-FFC swaps `ffcRef` → NUC output level shifts → drift. The
  factory chain re-derives `(k,b)` per frame; this is the remaining ~30% to port.

---

## 4. Image processing pipeline (current = `android2`)

`ViewerViewModel.process(raw, fpa)` runs every frame:

1. **`FactoryNuc.apply(raw, fpa, ffcRef)`** — per-pixel piecewise NUC (above).
2. **`seamResidual(raw, fpa, ffcRef)`** — removes `offset[seg] - offset[0]` per pixel
   (the residual the level-baseline misses; visible only on warm pixels → "two-detector"
   left/right / block seams). Net with step 4, the **full per-pixel offset is cancelled
   at every temperature**.
3. **Level-lock `trim`** — median-anchored so grid switches / FFC don't jump levels.
4. **2-D shutter offset map (`seam2d`)** — `afterFfc()` computes `fn.apply(ffcRef,
   fpa, ffcRef)` (the dark frame's NUC output = pure fixed 2-D offset pattern, shutter
   is a uniform source), mean-removed, stored as `seam2d`; subtracted **additively**
   (NOT as a per-column "gain" — that mis-modeling distorted warm pixels and *caused*
   the block seam).
5. **Learned flat-field (`flatMap`)** — optional, from `learnFlatField()` on a uniform
   scene (per-pixel offset residual).
6. **Bad-pixel correction** — 3×3 median; outlier `|clean - out| > 60` → replaced.
7. **Orient** (rotate 0/90/180/270 + mirror, applied at input; sets `lastCounts`).
8. **Spatial denoise** — bilateral (display-only, hides residual readout FPN).
9. **Auto-range** — percentile (1st/99th) → palette LUT → ARGB. Hot/cold markers +
   spot stats on the oriented grid.

**FFC**: `triggerFfc()` closes the shutter, averages `navg` frames → `ffcRef`; the
factory NUC uses it as `offset_ref`. **Auto-FFC watchdog**: every 5 s, if
`|FPA - lastFpa| ≥ 120` → `doFfc()` (mirrors the firmware's real shutter trigger).

### Stream health / recovery (added 2026-08-26)
- `startLoop()` watchdog: `frames == 0` for >8 s → *"no data on EP0x81"*; frames frozen
  >6 s → *"stream stalled"*. Either → **`recover()`**: `camera.stop()` + `close()` +
  `clearFfc()`, `connected = false`; the 2 s poll then reconnects the **same devId**
  (USB permission retained → no re-prompt).
- `MainActivity` registers `ACTION_USB_DEVICE_DETACHED` → `vm.disconnect()` for clean
  unplug.

---

## 5. Assets & build

`android2/app/build.gradle.kts` (`copyThermalAssets`, runs before `preBuild`) copies at
build time **from the repo root**:
- `factory_nuc_grid.npz` (NUC tables) → asset.
- `recon/planck_luts.npy` (Planck LUTs) → asset.

These are the only runtime assets; the app and Linux viewer use **identical** models
(`recon/planck_luts.npy` is force-kept by `.gitignore`'s `!recon/planck_luts.npy`). No
ONNX/SR models are bundled in `android2` (it has no neural super-res; that lives in the
older `android/` port).

---

## 6. Recent fixes (commit `7bbc072`, 2026-08-26)

- **Seam (block / warm-area / "two detectors")**: replaced the mis-modeled
  multiplicative 1-D col/row "gain" (`ci/ri`, which treated the shutter *offset* profile
  as *gain* and distorted warm pixels) with a pure additive 2-D shutter offset map;
  combined with `seamResidual` (`offset[seg] - offset[0]`) the full per-pixel offset is
  removed at all temperatures.
- **USB permission spam**: the 2 s poll no longer requests permission — it only
  auto-connects already-permitted devices. Requests happen via `ATTACHED` broadcast /
  manual button / launch; `MainActivity` throttles them to 1 / 10 s. (A genuine
  re-enumeration still prompts once — that is device behavior, not a bug.)
- **Stall recovery** + **DETACHED** handler as in §4.

### If seams persist after this
- Uniform scene still shows left/right or block level difference → residual **gain**
  block pattern in the factory `gain` table (next to port from `recon/PLAN_FACTORY_TEMP.md`).
- Frantic permission prompts continue (even throttled) → the device is **actually
  re-enumerating** (protocol wedge); capture `adb logcat` to find which command wedges.
