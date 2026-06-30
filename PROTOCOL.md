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
- **Per-frame telemetry in the 28-byte header/tail** (found live, `recon/probe_header.py`):
  - header `+4` (u32) = **frame counter** (+1/frame); rest of header = 0.
  - tail `+4` (u32) = frame counter; **tail `+8` (u32) = SENSOR / FPA TEMPERATURE (raw
    counts)** — the firmware's `ctx[0x40]` (`MAG_GetSenorTemperature`). Drifts smoothly as
    the sensor warms (e.g. ~19250 cold → ~21000 warm). It is in the **same units as the 6
    factory sensor-temp anchors** in the cali header (table A @off 36 = `[9564, 19330,
    29108, 34111, 39244, 49091]`), so it directly indexes the NUC blocks. Exposed via
    `magcam.py sensor_temp_raw()`; viewer shows it (`FPA=…`) and uses its drift to trigger
    auto-FFC (the firmware's real shutter trigger).

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
     (`ldr r1,[pc,#0x24]; add r1,pc`) → **0x29d700 in `.bss`** (runtime-populated; see the
     RESOLVED note below — this is correct, not "past EOF").
   - **8 built-in Planck LUTs extracted** → `recon/planck_luts.npy` (shape 8×646, int32,
     monotonic radiance curves at fileoff 0x24e718 stride 0xA20). These are the camera's
     radiometric curve family (selected by sensor temp).
   - **Interpolation math decoded**: forward `sub_4515c` (T-value→radiance, linear interp,
     idx=(v+150000)>>12 over 646 entries); inverse `sub_451c0` (radiance→T-value via
     binary search + slope-table interp, output `U = idx*4096 - 150000`). Working
     hypothesis: **U ≈ milli-Kelvin** (idx 109 → ~296K ≈ 23°C, plausible) → °C = U/1000-273.15.
     NEEDS verification once raw→radiance is reproduced.
   - **SNAG — NOW RESOLVED (2026-06-26).** The LUT tables are **not past EOF**; the static
     PC-math target `0x29d700` is correct — it lands in **`.bss`** (`.bss` = 0x29d6f8…
     0x2cdf00), i.e. the tables are **runtime-populated**, which is why they read as zero
     statically. Resolved from `sub_3f970`'s own simple path (binary search @0x3fb7e):
       * `radLUT`  @ **0x29d700** (647×int32) — also the interp base (`baseLUT==radLUT`).
       * `slopeLUT`@ **0x29e11c** (646×int32).
       * `threshold`@ **0x29eb34** (scalar; min Δ below which interp is skipped — sub-mK).
     The firmware fills `radLUT` at init from **one of the 8 static Planck curves in
     `.rodata`** (already extracted → `recon/planck_luts.npy`) and derives the fixed-point
     reciprocal slope **`slopeLUT[i] = 2**24 // (radLUT[i+1]-radLUT[i])`** so that
     `(slope*Δ)>>12` spans one full index step (4095–4096, floor-rounded ≈1 mK).
     Reconstructing both from `planck_luts.npy` and doing the integer lookup matches the
     ideal float interpolation to **<0.01 °C** (proof: `recon/resolve_radlut.py`). ✅ U is
     confirmed **milli-Kelvin** (idx 109 → 23.31 °C for every curve). Reimplemented
     bit-faithfully in `radiometry.py` (`_U_from_radiance`, reconstructed `self.slopes`).
   - **k, b are NOT static constants — they are recomputed LIVE every frame** by
     `sub_3d280` (writes `k`→ctx[0x50] @0x3d32c, `b`→ctx[0x54] @0x3d330) from: the factory
     **NUC blocks** (per sensor-temperature, stride **0x21c**, fields +0x1730/+0x1734/
     +0x17a8…+0x1900), the **live sensor temperature** ctx[0x80], and the **live shutter/
     FFC reference** (`sub_3d9f4`). This is *why* FFC affects temperature and *why* there
     are no fixed k,b to extract. The `shift` (ctx[0x775c]) folds into the linear term as
     a `<<(7-shift)` scaling — i.e. `radiance = a*raw + b` with `a=(k+1)<<(7-shift)`.
   - **Cali parser can't be run offline.** `sub_34832` is a thin shim that immediately
     delegates to a **PLT import** (`sub_30024`) — the real parse runs in an *external*
     library behind C++ stream layers (not in libcoresdk.so). So the NUC blocks can't be
     expanded on x86 (would need full multi-`.so` + libstdc++ dynamic emulation). The raw
     factory maps themselves ARE plain in `mag_cali.bin` (header 1024 B + **48 maps** of
     160×120 = 6 sensor-temps × 8 states), but applying them needs the external assembler.
   - **NET (what is reversed vs. fitted):** the *fixed* radiometric curve (Planck LUT +
     mK encoding + interpolation) is reversed **exactly** and integrated in
     `radiometry.py`. The *per-camera* linear term `(a,b)` — which the firmware itself
     re-derives live — is recovered from 2–3 `(raw, known °C)` references via `calibrate()`
     over the exact curve. This is the camera's true radiometry, not a guessed shape; it
     matches the firmware except for re-deriving `(a,b)` as the sensor drifts (handled by
     periodic re-FFC + re-cal). The header anchor tables (A=hdr[9..14], B=A−500, C) are
     internal NUC params (near-collinear) — tested, they do **not** form a usable scene-
     temperature calibration, so a reference-free default is not derivable from them.
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

## FLAT-FIELD / NUC CORRECTION — reverse-engineered (2026-06-26)

The camera's "flat-field" is a standard uncooled-microbolometer pipeline, split across
the internal frame buffers in the per-device context (stride 0x498 in the device array):

- `ctx[0x208]` = raw input frame (as read off EP 0x81, heavy column FPN, uncorrected).
- `ctx[0x234]` = filtered/corrected output frame (what `MAG_GetOutputRawData`,
  `MAG_GetFilteredRaw` return; also the buffer `sub_45014`/temperature read from).

Pipeline (raw -> corrected):
1. **Shutter one-point NUC (the dominant flat-field).** `SetShutterState`(0x6BB6B672)
   closes an internal shutter (uniform target); the per-pixel response is captured as the
   offset reference and subtracted (`MAG_GetCurrentOffset`@0x4c010 -> `sub_486b8` computes
   the offset). This kills the column FPN. **We replicate this exactly** in
   `magcam.py trigger_ffc()` (close shutter, average frames, subtract, reopen). It must be
   re-run periodically because the offset drifts — that is *why* FFC must be pressed often.
2. **Factory per-pixel NUC + defect map.** `mag_cali.bin` carries **48 plain 160×120 maps**
   (offset 1024, stride 38400) = a family of per-pixel **offset/reference** frames (smooth
   vignette + column FPN), interleaved **gain/coefficient** maps, and a **bad-pixel map**
   (visible as sparse speckle — confirms the original app masks bad pixels). They are
   organised by **6 sensor-temperatures × 8 gain/range states**. Extracted as-is to
   `recon/factory_nuc_maps.npy` (+ `recon/factory_nuc_montage.png`).
3. **Apply step is NOT cleanly runnable offline.** The map selection/blend (by live sensor
   temperature) and the cali parse run behind the same statically-inlined C++ stream layer
   as the radiometry parser (`sub_34832` -> PLT import) — so the exact factory application
   formula can't be executed on x86.

NET: the flat-field *architecture* is reversed and the factory maps are extracted, but the
bit-exact factory application is locked behind the un-runnable parser. Our pipeline already
reproduces all three physical effects empirically — shutter FFC (offset NUC), `enhance.py`
flat-field (residual vignette + column/row FPN, ~11× non-uniformity reduction) and
bad-pixel correction — so the *result* matches the firmware without using the factory
coefficients verbatim. The non-redundant piece a factory map *could* add is the fixed
per-pixel **gain** (responsivity) that shutter-only can't capture; applying it is optional
and risky (wrong fixed-point/selection would re-introduce non-uniformity), so it is left
out by default.

### Factory-NUC offline-integration attempt — VERDICT: not viable (2026-06-29)
Tried to integrate the factory NUC empirically, validating candidates against the live
shutter-FFC reference (= ground-truth per-pixel offset at the current sensor temp ~23123,
captured live). Block structure confirmed: **6 sensor-temp blocks × [2 gain maps + 6
reference frames]**. Results:
  * full per-pixel factory map vs live FFC offset: corr 0.02–0.26 (no match);
  * smooth reference frames (systematic) vs FFC: ~0.02 (no match);
  * gain maps are 0x8000-centred fixed-point (vignette extraction saturates);
  * only the *column-FPN profile* of the gain maps matched (0.92) — but that is the shared
    readout-channel structure the live FFC already removes, so redundant.
Conclusion: the per-pixel gain/offset is derived by the external parser from the 6
reference frames per block; that derivation can't be reproduced offline, and the live FFC
(fresh, per-session) already removes the same correctable systematic FPN. So the factory
NUC is NOT wired into the pipeline (would risk regression). Extraction kept in
`factory_nuc.py` (research, not imported) + `recon/factory_nuc_maps.npy`. The only reliable
route to the real factory gain is to run the parser on ARM (android/PLAN.md JNI path).

## EMULATION ROUTE (route A) — see `recon/EMULATION_NUC.md`
A full Unicorn emulation of libcoresdk.so was built (`recon/emu_harness.py`) and the real
cali parser `sub_424d0` was driven to completion on `mag_cali.bin` (`recon/emu_parse_nuc.py`),
decoding the cali structure and the per-pixel NUC apply formula `sub_45ca4`:
`corrected[i] = clamp(((raw[i]-offset_ref[i])/2 * gain_seg)>>shift + offset_seg)` — a
**per-pixel multi-segment piecewise-linear** correction (so the factory NUC is NOT two flat
maps, which is why flat-map subtraction failed). Finishing it (filling the gain/offset
tables) needs the rest of the StartProcessImage init chain — see `recon/EMULATION_NUC.md`
for the complete record and resume plan. Route B (empirical two-point) is being used for a
quick usable result.
