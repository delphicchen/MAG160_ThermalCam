# Magnity MAG-Mx Thermal Camera — USB Protocol (reverse-engineered)

Reverse-engineered from `MAG-Mx.apk` (`lib/armeabi-v7a/libcoresdk.so`, ARM32 Thumb)
plus live probing of the device on Linux. Goal: native Linux viewer with
temperature readout (no ARM `.so` reuse — those are 32-bit ARM only).

## Device
- USB **VID `0x833C` (Shanghai Magnity Electronics), PID `0x0001`** (also `0x0002` in
  the APK's `device_filter.xml` = a second model/mode).
- Vendor-specific (bDeviceClass 0, **not UVC**). 1 interface (#0), Full-Speed 12 Mbps.
- Self-powered, 100 mA. Re-enumerates (new device address) after a firmware reset.
- **Resolution 160×120, 15 fps, 16-bit pixels** (read live from GetParameter1).
  - Per the EEVblog MAG160Core thread the family also has 384×288 variants; the
    frame reader in the `.so` branches on width 0xA0(160) vs 0x180(384).

## Endpoints (interface 0, all bulk, 64-byte max packet)
| EP | dir | use |
|----|-----|-----|
| `0x03` | OUT | command |
| `0x05` | OUT | (calibration out, unused so far) |
| `0x81` | IN  | **image stream** (magic-delimited frames) |
| `0x82` | IN  | command response |
| `0x84` | IN  | calibration file download |

## Command transport (function `sub_0x49aa0` in libcoresdk.so)
1. Write command packet to **EP 0x03**, timeout 500 ms.
2. Read response from **EP 0x82** (up to 4096), timeout 2000 ms.
3. Response: first 4 bytes = ACK header (= request word with top nibble 6→5,
   e.g. cmd `0x6BB6B66B` → resp header `0x5BB5B55B`); payload follows. The parser
   passes `response+4, len-4` to the handler.

Command packet = little-endian. Most are **8 bytes** `<cmd u32><param u32>`, but
**StartTransferImg is sent as 4 bytes** (cmd word only). GetParameter1 works with 8
bytes (param 0). Mutex-serialised (one transaction at a time).

## Command words (confirmed in our firmware via movw/movt pairs)
Built with `movw rX,#0xBxxx; movt rX,#0x6BB6` (NOT stored as literal bytes — byte
search fails; must scan movw/movt).

| name | value | notes |
|------|-------|-------|
| GetParameter1 | `0x6BB6B66B` | resp@offset20=W, @24=H, @28=fps (live: 160/120/15) |
| GetParameter2 | `0x6BB6B66C` | responds live though not found as movw/movt |
| GetCaliInfo   | `0x6BB6B66F` | resp@offset4 = cali file size (live ≈ 0x1C53A0 = 1,856,928) |
| GetCaliFile   | `0x6BB6B670` | then read cali bytes from **EP 0x84** (chunks ≤ 0x80000, timeout 60s) |
| SetShutterState (FFC) | `0x6BB6B672` | 8-byte, param 0=close/1=open |
| StartTransferImg | `0x6BB6B673` | **4-byte** packet |
| StopTransferImg  | `0x6BB6B674` | |
| cmd676 | `0x6BB6B676` | 8-byte, role TBD (part of prepare?) |
| cmd677 | `0x6BB6B677` | 8-byte, role TBD |

## Image stream on EP 0x81 (frame reader = thread2 @ `0x4a168/0x4a16c`)
- Stream is **magic-delimited**, not raw frames. Frame **start magic `0x1BB1B11B`**
  (`movw r8,#0xB11B; movt r8,#0x1BB1`); frame **end magic `0x1BB1B11C`** (magic+1)
  located at `[frame_start + payload + 0x1C]`.
- Payload size word at struct `[r4+0x1C4]`; header ~0x1C–0x38 bytes; pixels = W*H*2.
- Host MUST drain EP 0x81 continuously or the device watchdog-resets (we caused a
  disconnect by streaming with an 8-byte StartTransferImg + slow draining).

## Start-streaming sequence (`MAG_StartProcessImage` worker, wrapper @ `0x4a450/0x4a500`)
1. `pthread_create` ×2: thread1 `0x4a610` = frame-callback dispatch (non-USB),
   thread2 `0x4a168` = EP 0x81 reader loop (read-only, scans magic).
2. `usleep(50000)` (50 ms).
3. Send **StartTransferImg (4 bytes)** to EP 0x03.

## Color palette / temperature = HOST-SIDE
- `MAG_SetColorPalette` only stores a palette index and rebuilds a colormap on the
  host — **no USB command**. We implement palettes ourselves.
- Temperature: native API `MAG_GetTemperatureData`, `MAG_FixTemperature`,
  `MAG_GetParameter`… uses the **calibration file** (GetCaliFile) + params. Conversion
  formula still TODO (decode from `.so` MAG_RevisedTemp2Gray / FixTemperature, or fit
  empirically). For display-only, normalise raw 16-bit.

## STREAMING — SOLVED (live, 2026-06-24, `recon/grab3.py`)
**Root cause:** the cmd-transaction in the `.so` always reads EP 0x82 after writing
EP 0x03. **Every command must be followed by an EP-0x82 ack read** — including
`StartTransferImg`/`StopTransferImg`. Skipping the ack (grab/grab2 did) backs up the
EP-0x82 buffer and wedges the firmware (the "data endpoints never deliver / device
re-enumerates" symptom). With the ack read, **streaming works**: 39 frames /
1.46 MB in 2.5 s.

Working sequence:
1. (stop+drain) → `GetParameter1` (read ack → W,H) → `GetParameter2`, `GetCaliInfo`
   (read acks). Each `cmd_ack`: write 8-byte `<cmd><param>` to EP 0x03, read EP 0x82.
2. `clear_halt(0x81)` (insurance), start a background thread draining EP 0x81.
3. `StartTransferImg` as **4-byte** packet, **then read its EP-0x82 ack** (`5F B5 B5 5B`).
4. Frames stream on EP 0x81. Stop with `StopTransferImg` (+ack).

### Frame layout on EP 0x81 (160×120)
- Period **38456 bytes** = 28-byte header + 38400 payload (160×120×u16) + 28-byte tail.
- Header: `1B B1 B11B`(magic) `00000000` `00 96 00 00`(=38400 payload size) … then more.
- Pixels are little-endian `uint16`, row-major 160×120. Tail begins with magic+1
  `0x1BB1B11C`.
- **Raw values are uncorrected** (heavy column fixed-pattern noise; first frames near
  saturation 0xFFFC, settle after ~10 frames to ~16k–48k range). Need FFC.

## REMAINING WORK
1. **FFC / flat-field correction — DONE** (`magcam.py` `trigger_ffc()`):
   `SetShutterState`(0x6BB6B672) param 0 physically **closes the shutter**; average a
   few shutter-closed frames as a per-pixel reference, reopen (param 1), and subtract
   the reference from live frames. This removes the column fixed-pattern noise
   completely → clean thermal image. (Re-FFC periodically as the sensor drifts.)
2. **Temperature** — calibration file download DONE, radiometry partially mapped:
   - `GetCaliFile` (0x6BB6B670, **4-byte** cmd) → EP-0x82 ack carries the file size at
     offset +4 (1,856,416 bytes here) → read that many bytes from **EP 0x84** in
     ≤0x80000 chunks. Saved to `recon/mag_cali.bin`. Header: magic `5AA50003`, then
     u32 W=160,H=120,6,3,8, then -50000 (0xFFFF3CB0), 1024…
   - Conversion chain (from `.so`):
     `MAG_GetTemperatureProbe(ch,x,y)` → pixel_index = W*y+x →
     `sub_45014` (spatial-averages raw u16 over a mode-dependent window; raw frame ptr
     at `ctx[+0x234]`) → **`sub_3f970(ctx, raw_avg)`** = the radiometric core →
     optional `sub_404e8` emissivity/ambient correction. Returns temp as int
     (error sentinel `0x80000001`); units TBD (likely centi-°C or deci-K).
   - **`sub_3f970` math:** float pre-scale `v = raw*(ctx[+0x50]+1) + ctx[+0x54]`
     (per-pixel gain/offset from cali); selects a calibration block by sensor temp
     `sb = ctx[+0x80]` (clamped) at stride **0x21c**; reads per-block floats at
     +0x1900/+0x17a8/+0x17ac/+0x17b0/+0x17b8; calls `sub_455bc`; final mapping uses a
     ~646-entry piecewise-linear **Planck LUT** (function `0x4515c`, value range
     [-154095, 2495503] in 4096 steps).
   - **Cali-file parser = `sub_34832(obj, buf, size, 1)`** (called from the GetCaliFile
     success path 0x4a9d8, via `sub_346e0` ctor / `sub_34638` dtor; the raw buffer is
     freed afterwards so parsing populates persistent cali tables during sub_34832).
     This is the function that maps the 1.8MB file's bytes → the ctx cali fields. NOT
     yet decoded.
   - **Planck/temperature LUT = `sub_4515c`** (DECODED): linear interpolation over a
     646-entry int32 table. `idx = (input + 150000) >> 12` clamped [0,645];
     `out = LUT[idx] + ((LUT[idx+1]-LUT[idx]) * (input+150000 - idx*4096)) >> 12`,
     clamped ≥0. Input spans [-150000, ~2.49M]. The table is loaded PC-relative
     (`ldr r1,[pc,#0x24]; add r1,pc` at 0x45194; literal 0x451bc) — **address calc still
     wrong** (computed 0x29D700 is past EOF 0x29CD68); re-derive (maybe the table is in
     .data.rel.ro; or brute-force search for a ~646-entry monotonic int32 table).
   - **8 built-in Planck LUTs extracted** → `recon/planck_luts.npy` (shape 8×646, int32,
     monotonic radiance curves at fileoff 0x24e718 stride 0xA20). These are the camera's
     radiometric curve family (selected by sensor temp).
   - **Interpolation math decoded**: forward `sub_4515c` (T-value→radiance, linear interp,
     idx=(v+150000)>>12 over 646 entries); inverse `sub_451c0` (radiance→T-value via
     binary search + slope-table interp, output `U = idx*4096 - 150000`). Working
     hypothesis: **U ≈ milli-Kelvin** (idx 109 → ~296K ≈ 23°C, plausible) → °C = U/1000-273.15.
     NEEDS verification once raw→radiance is reproduced.
   - **SNAG**: tables referenced inside `sub_4515c`/`sub_451c0` resolve via GOT/load-time
     relocation (static PC math lands at 0x29D700, past EOF) — must resolve R_ARM_RELATIVE
     relocs to bind each function to its table; the brute-found 0x24e718 LUTs may or may
     not be the exact ones these two funcs use.
   - REMAINING for absolute °C (LARGE, multi-session): (a) resolve the table relocations,
     (b) get per-camera gain k=ctx[+0x50] & offset b=ctx[+0x54] — decode cali parser
     `sub_34832` OR locate them in the cali file, (c) reimplement `sub_3f970`+`sub_455bc`,
     (d) verify units against physical references. Header hint: cali hdr[3]=6 + 6 rising
     values hdr[9..14]={9564,19330,29108,34111,39244,49091} = likely 6 sensor-temp points.
   - **FULL FORMULA DECODED** (sub_3f970 + sub_455bc):
     ```
     raw_avg = mean of raw u16 over a small window (sub_45014)
     v       = raw_avg*(k+1) + b              # k=ctx[+0x50], b=ctx[+0x54] (cali floats)
     target  = v << (7 - shift)               # shift = ctx[+0x775c]
     idx     = binary_search(radLUT, target)  # radLUT = 646-entry cali radiance table
     U       = idx*4096 - 150000 + ((slopeLUT[idx]*(target-baseLUT[idx])) >> 12)
     temp    ≈ U   (hypothesis: U in milli-Kelvin → °C = U/1000 - 273.15)
     ```
     plus optional emissivity/ambient correction (sub_404e8).
   - **The ONLY unknowns are cali DATA** built at runtime by parser `sub_34832` into a
     bss region (0x29d700…): the per-camera gain `k`, offset `b`, `shift`, and the
     646-entry radiance/base/slope LUTs. These are NOT plain in `mag_cali.bin` (the file
     is a parametric structure the ARM parser expands; no monotonic LUT found by scan),
     and the parser is ARM-only so can't be run on x86. The 8 static LUTs in the `.so`
     (`planck_luts.npy`) are likely defaults/templates the parser selects/blends.
   - **Practical accurate path (uses the decoded formula + built-in curve):** treat the
     radiance↔temp curve as physics (use a built-in Planck LUT or fit the functional
     form) and solve the per-camera linear `k,b` (+ unit) from 2–3 known-temperature
     references. `radiometry.py` will implement the decoded formula with `calibrate()`.
     Alternative full path: statically reverse `sub_34832` to expand `mag_cali.bin` →
     radLUT/k/b (large, risky on units).
3. Build the PySide6 viewer around `grab3.py`'s working pipeline. — DONE (`viewer.py`).

## Environment / access
- Native Linux (NOT WSL). pyusb 1.3.1, androguard 4.1.4, capstone 5.0.6, java 25.
- USB access: udev rule `linux_app/99-magnity-thermal.rules` installed to
  `/etc/udev/rules.d/` → node `crw-rw---- root:plugdev`; user `delphic` is in plugdev.
- Recon tooling in `linux_app/recon/`: `disasm.py` (Thumb fn disasm w/ PLT+literal
  resolve), `trace.py`, `probe.py` (GetParameter — WORKS), `grab.py` (streaming WIP).
- APK extracted at `/tmp/apk_x` (re-extract: `unzip MAG-Mx.apk -d /tmp/apk_x`).
- Key `.so` addrs: cmd-transaction `0x49aa0`; bulk_transfer PLT `0x30270`;
  StartTransfer site `0x4a546`; frame reader `0x4a16c`; prepare worker `0x4b03c`.
