# Magnity MAG-Mx 833c — Complete Reverse-Engineering Reference

> **Purpose**: Single-source reference for any agent working on this project. Covers the
> firmware pipeline architecture, every known function entry point, ctx field map,
> cali.bin format, per-die flat-field mechanism, radiometric core, and the LUT builder.
> All addresses are VA in `libcoresdk.so` (base0x40000000, Thumb mode).

> **Reading order**: §1 (overview) → §2 (call chain) → §3 (ctx fields) → §4 (cali.bin)
> → §5 (per-die flat-field) → §6 (radiometric) → §7 (LUT builder) → §8 (build/parser)
> → §9 (device object) → §10 (Android integration) → §11 (known gaps).

---

## 1. Architecture Overview

The firmware runs a **two-layer device manager**:

```
MAG_StartProcessImage (0x4004cf5c)
  └→ Orchestrator core (0x4004cfe0)        ← outer layer, manages device table
       └→ dispatch via device_entry[0] (blx r7)  ← calls into inner device
            └→ Process step (0x4004228c)     ← inner layer, per-frame processing
                 ├→ Parser/setup (0x400424d0)    ← on first frame or FPA switch
                 ├→ Per-pixel executor (0x400447c0) ← per-pixel loop
                 │    └→ sub_3f970 (0x4003f970)  ← radiometric core (per-pixel)
                 └→ Post-process (0x400404e8)    ← current→temp conversion + palette
```

**Key design**: The inner device is a vtable-based object stored at ctx+0x20c/ctx+0x218.
Without a real camera device, these point to sentinel values (0xdeadbee0). The NUC
apply and per-pixel loop are invoked through this vtable at runtime — they cannot be
called directly from static analysis.

---

## 2. Complete Call Chain (every entry point)

### 2.1 Entry Point

| Function | VA | Role |
|---|---|---|
| `MAG_StartProcessImage` | `0x4004cf5c` | Public API entry; sets up ctx, calls orchestrator |
| Orchestrator core | `0x4004cfe0` | Outer loop: device table iteration, frame dispatch |

### 2.2 Device Table (Orchestrator)

The orchestrator manages a two-level device table:

- **JNI table** (outer): stride = `0x14` (20 bytes per entry)
  - Entry+0x00: device pointer
  - Entry+0x10: internal device pointer
- **Internal table** (inner): stride = `0x498` (1176 bytes per entry)
  - Entry+0x24: → ctx (the main processing context, ~0x20000 bytes)
  - Entry+0x40: → device vtable methods

### 2.3 Process Step (0x4004228c)

The inner device's main processing function. Called via `blx r7` where r7 =
`device_entry[0]` (the vtable function pointer).

**Preconditions**: ctx+0x20c and ctx+0x218 must be valid device objects (not 0xdeadbee0).

**Reads** (from ctx):
- ctx+0x04: frame width (160)
- ctx+0x08: frame height (120)
- ctx+0x80: die index (0–5) — set by device object, not by this function
- ctx+0x234: raw input frame pointer (u16*)
- ctx+0x254: per-pixel LUT buffer (from LUT builder)
- ctx+0x1e2c, ctx+0x1e30: grid dimensions (N, M) for radiometric

**Calls** (literal bl, partial list):
- `0x400431d0` — LUT builder (radiometric curve + per-pixel bilinear indices)
- `0x400455bc` — LUT lookup helper (called from sub_3f970)
- `0x400404e8` — post-process (current→temp, palette)
- `0x400447c0` — per-pixel executor (reads ctx+0x234, calls sub_3f970)

**Indirect calls** (via blx, through vtable or function pointers):
- Device NUC apply — through ctx+0x20c vtable
- Per-pixel loop — through ctx+0x218 or internal dispatch

### 2.4 Per-Pixel Executor (0x400447c0)

Reads the raw frame from ctx+0x234, iterates over all pixels, and for each pixel:
1. Reads raw value (u16)
2. Stores die index to ctx+0x80 (via register-indirect, not literal store)
3. Calls sub_3f970 (radiometric core) for each pixel
4. Writes output to ctx+0x234 (overwrites in-place)

### 2.5 Radiometric Core — sub_3f970 (0x4003f970)

**The heart of per-pixel radiometric conversion.** Does two things:
1. **Global correction**: `raw_f32 = raw * (1 + ctx+0x50) + ctx+0x54`
2. **Per-die LUT bilinear interpolation**: maps corrected raw → temperature

#### sub_3f970 Disassembly (annotated)

```
; r11 = ctx (base context pointer)
; r10 = raw value (u16, from input frame)
; r12 = flag (0 = normal, nonzero = skip LUT)

0x4003f9d0: ldr.w r0, [r11, r0]        ; r0 = ctx field (max die count)
0x4003f9d4: movs r1, #0
0x4003f9d6: ldr.w r9, [r11, #0x80]     ; r9 = ctx+0x80 = die index
0x4003f9da: vldr s0, [pc, #0x3a0]      ; s0 = lower bound constant
0x4003f9de: cmp r9, r0                  ; bounds check: die_index < max?
0x4003f9e0: mov.w r0, #0x21c           ; r0 = LUT stride (540 bytes)
0x4003f9e4: it hs
0x4003f9e6: movhs.w r9, #0             ; if out of range, reset to die 0
0x4003f9ea: vldr s2, [pc, #0x394]      ; s2 = upper bound constant
0x4003f9ee: mla r0, r9, r0, r11        ; r0 = die_index * 0x21c + ctx
0x4003f9f2: add.w r6, r0, #0x1900      ; r6 = &ctx[die].lut_data (base of LUT entry)
0x4003f9f6: movs r0, #0
0x4003f9f8: vldr s4, [r6]              ; s4 = first float of LUT entry
0x4003f9fc: vcmpe.f32 s4, s0           ; check s4 >= lower_bound
0x4003fa04: vcmpe.f32 s4, s2           ; check s4 <= upper_bound
0x4003fa14: cmp.w r12, #0              ; skip LUT if r12 != 0
0x4003fa18: and.w r4, r0, r1           ; r4 = validity flag
0x4003fa1c: beq.w 0x4003fdc2           ; if r12==0, jump to global-only path
0x4003fa20: cmp r4, #0
0x4003fa22: bne.w 0x4003fdc2           ; if invalid, jump to global-only path
```

**If LUT valid, proceed to bilinear interpolation:**

```
0x4003fa26: mov.w r0, #0x21c
0x4003fa2e: mla r0, r9, r0, r11        ; r0 = die * 0x21c + ctx
0x4003fa32: movw r1, #0x17a8
0x4003fa36: add.w r8, r0, r1           ; r8 = &ctx[die].param_A  (+0x17a8 from base)
0x4003fa3a: vldr s0, [r8]              ; s0 = param_A (float)
0x4003fa3e: vcmpe.f32 s0, s16          ; check param_A >= 1.0
0x4003fa46: blt.w 0x4003fdba           ; if <1.0, skip to error path
```

**Per-die LUT entry structure** (at ctx + die * 0x21c):

| Offset (from ctx+die*0x21c) | Field | Type | Meaning |
|---|---|---|---|
| +0x17a8 | param_A | f32 | Curve parameter (must be ≥1.0) |
| +0x17ac | param_B | f32 | Curve parameter (must be ≥1.0) |
| +0x17b0 | param_C | f32 | Scale factor (must be >0) |
| +0x17b4 | param_D | f32 | Offset for low-range path |
| +0x17b8 | param_E | f32 | Scale factor (must be >0) |
| +0x17bc | param_F | f32 | Offset for high-range path |
| +0x1900 | lut_data | f32[135] | Bilinear LUT data (135 floats = 0xC4 bytes) |

**Bilinear interpolation** (simplified):

```
; Inputs: s2 = corrected_raw (float), LUT entry at r6
; The LUT contains 135 pairs of (x_value, f_value)
; Binary search for the two nearest x_values surrounding corrected_raw
; Then linearly interpolate f_value between them
;
; Result: temperature in raw units (later converted by post-process)
```

**Key formula** (global correction, always applied):

```
corrected = raw_u16 * (1.0 + ctx[0x50]) + ctx[0x54]
```

Where ctx+0x50 and ctx+0x54 are per-frame global gain/offset floats, set during
the setup/parser phase.

### 2.6 Post-Process (0x400404e8)

Converts the radiometric output to display-ready values:
- Maps through palette LUT (256 entries)
- Applies orientation (0/90/180/270 + mirror)
- Computes spot statistics (min/max/mean)

### 2.7 NUC Apply — sub_45ca4 (0x40045ca4)

**Bit-exact per-pixel piecewise NUC.** Despite being a dead code path in the static
call graph (no literal bl to this address), it IS the live NUC — invoked through the
device object vtable at runtime.

```
; Inputs: r0 = input u16 value, r1 = die index, r2 = ctx
; Uses: ctx+0x16d8 (ref), ctx+0x16e0 (gain), ctx+0x16ec (offset)
;       ctx+0x16f4 (nseg flag), ctx+0x16f8 (shift)
;
; v = (raw - ref[die]) >> 1
; seg = first s where v > bp[die,s]  (else last segment)
; output = clamp((v * gain[die,seg]) >> shift + offset[die,seg], 0, 65535)
```

**Verified**: numpy port (`np_apply`) produces bit-exact output vs firmware emulation.

**Build-time buffer layout** (after `run_build()`):

| ctx offset | Pointer | Content |
|---|---|---|
| ctx+0x16d8 | 0x50120440 | ref frame (u16, 160×120) |
| ctx+0x16dc | 0 | (unused) |
| ctx+0x16e0 | 0x50621840 | gain tables (u16, N×H×W×nseg) |
| ctx+0x16e4 | 0x50133440 | ref2 frame |
| ctx+0x16e8 | 0 | (unused) |
| ctx+0x16ec | 0x50661c40 | offset tables (u16, N×H×W×nseg) |
| ctx+0x16f0 | 0 | (unused) |
| ctx+0x16f4 | u32 | nseg flag (3 for NUC) |
| ctx+0x16f8 | u32 | shift (12 for NUC) |

---

## 3. ctx Field Map (partial, key fields)

The main processing context is ~0x20000 bytes (128 KB). Key fields:

| Offset | Size | Type | Meaning | Set by |
|---|---|---|---|---|
| +0x04 | u32 | int | Frame width (160) | Setup |
| +0x08 | u32 | int | Frame height (120) | Setup |
| +0x50 | f32 | float | Global gain correction | Parser/setup |
| +0x54 | f32 | float | Global offset correction | Parser/setup |
| +0x80 | u32 | int | **Die index (0–5)** | Device object (runtime) |
| +0x1900 | f32 | float[135×6] | **Per-die radiometric LUT** | LUT builder |
| +0x17a8 | f32×6 | float×6 | Per-die curve params (A–F) | LUT builder |
| +0x16d8 | ptr | u16* | Ref frame (gate-0) | Build step |
| +0x16e0 | ptr | u16* | Gain tables | Build step |
| +0x16ec | ptr | u16* | Offset tables | Build step |
| +0x16f4 | u32 | int | nseg (3 for NUC) | Build step |
| +0x16f8 | u32 | int | Shift (12 for NUC) | Build step |
| +0x1738 | u32 | flag | LUT-ready flag (0/1) | Parser/setup |
| +0x17a8+die*0x21c | f32×6 | struct | Per-die curve params | LUT builder |
| +0x1900+die*0x21c | f32[135] | float[] | Per-die LUT data | LUT builder |
| +0x1e2c | u32 | int | Grid cols (M) | Setup |
| +0x1e30 | u32 | int | Grid rows (N) | Setup |
| +0x20c | ptr | obj* | Device object (JNI table) | Device open |
| +0x218 | ptr | obj* | Device object (internal table) | Device open |
| +0x234 | ptr | u16* | Raw input frame pointer | Per-pixel executor |
| +0x254 | ptr | void* | Per-pixel LUT buffer (heap) | LUT builder |

**Note**: ctx+0x80 is written via register-indirect store (not a literal `str rX, [rY, #0x80]`). The writer is inside the device object's vtable method, which is populated at device-open time. Cannot be traced statically without hardware.

---

## 4. cali.bin Format

File: `mag_cali.bin` (downloaded from camera via EP-0x84)

### 4.1 Header (128 bytes)

| Offset | Size | Type | Value | Meaning |
|---|---|---|---|---|
| +0 | u32 | magic | 1520762883 | Identifies cali file |
| +4 | u32 | int | 160 | Width |
| +8 | u32 | int | 120 | Height |
| +12 | u32 | int | 6 | Number of cali groups |
| +16 | u32 | int | 3 | Number of NUC segments (nseg) |
| +20 | u32 | int | 8 | Maps per group |
| +24 | u64 | hex | 0xFFFFFFFFFFFF3C0B | Unknown (possibly max value marker) |
| +28 | u32 | int | 1024 | Map stride (bytes per map row) |
| +32 | u32 | int | 0 | Reserved |
| +36–56 | u32×6 | int[] | [9564,19330,29108,34111,39244,49091] | **Anchor table A** (FPA temps ×100) |
| +60–80 | u32×6 | int[] | [9064,18830,28608,33611,38744,48591] | **Anchor table B** (FPA temps, -500 offset) |
| +84–104 | u32×6 | int[] | [8041,9311,10588,11301,12075,13642] | **Scene temp anchors** (for radiometric calibration) |
| +108–124 | u32×5 | int[] | [32,32,32,32,32] | Unknown (possibly max pixel value) |

### 4.2 Map Data (128 + 48 × 38400 = 1,843,328 bytes)

48 maps of 160×120 u16, stored sequentially. Each map is 160×120 = 19200 u16 values
= 38400 bytes. Maps are organized into 6 groups of 8 maps:

| Map | Group | Content | Description |
|---|---|---|---|
| 0 | 0 | gain_0 | Gate-0 NUC gain (high-mean, ~31K–60K) |
| 1 | 0 | offset_0 | Gate-0 NUC offset |
| 2 | 0 | bp_0 | Gate-0 NUC breakpoint 1 |
| 3 | 0 | bp_1 | Gate-0 NUC breakpoint 2 |
| 4–7 | 0 | (reserved) | Additional gate-0 maps |
| 8 | 1 | **gain_d0** | Per-die gain at FPA 19330 |
| 9 | 1 | (unused) | — |
| 10 | 1 | **offset_d0** | Per-die offset at FPA 19330 |
| 11 | 1 | (unused) | — |
| 12–15 | 1 | (reserved) | — |
| 16 | 2 | **gain_d1** | Per-die gain at FPA 29108 |
| 18 | 2 | **offset_d1** | Per-die offset at FPA 29108 |
| 24 | 3 | **gain_d2** | Per-die gain at FPA 34111 |
| 26 | 3 | **offset_d2** | Per-die offset at FPA 34111 |
| 32 | 4 | **gain_d3** | Per-die gain at FPA 39244 |
| 34 | 4 | **offset_d3** | Per-die offset at FPA 39244 |
| 40 | 5 | **gain_d4** | Per-die gain at FPA 49091 |
| 42 | 5 | **offset_d4** | Per-die offset at FPA 49091 |

**Groups 1–5** are the per-die calibration pairs. Each group is calibrated at a
different FPA temperature (from anchor table A: [19330, 29108, 34111, 39244, 49091]).

### 4.3 Per-Die Map Statistics

| Map | Mean | Min | Max | 2×2 Structure |
|---|---|---|---|---|
| gain_d0 (8) | 31457 | 32768 | 32768 | Weak (BL/TL ratio ~1.0) |
| gain_d1 (16) | 40516 | 25263 | 53548 | Strong (BL/TL=1.046) |
| gain_d2 (24) | 55799 | 25278 | 74524 | Strong (BL/TL=1.152) |
| gain_d3 (32) | 60116 | 25321 | 82368 | Strong (BL/TL=1.213) |
| gain_d4 (40) | 60117 | 25305 | 84819 | Strong (BL/TL=1.303) |
| offset_d0 (10) | -32 | 32768 | 32768 | Flat (zero structure) |
| offset_d1 (18) | 43115 | -24459 | 105016 | Strong 2×2 |
| offset_d2 (26) | 33442 | -51208 | 109444 | Strong 2×2 |
| offset_d3 (34) | 10035 | -79755 | 118238 | Strong 2×2 |
| offset_d4 (42) | 12549 | -109198 | 133102 | Strong 2×2 |

**Key observation**: gain ratios grow with FPA temperature — the 2×2 pattern is
FPA-dependent. The matching pair (same FPA group) flattens perfectly; wrong pair
leaves 10× residual.

---

## 5. Per-Die Flat-Field Mechanism

### 5.1 Root Cause of 2×2 Partition

The image shows a 2×2 stitched-die partition because the sensor is composed of 4 die
quadrants, each with different gain/offset characteristics. The gate-0 NUC (maps 0–7)
removes high-frequency per-pixel FPN but does NOT remove the per-die 2×2 pattern.

### 5.2 Correction Formula

For each pixel in die `d` at FPA temperature group `g`:

```
corrected = (raw - offset_d[g]) * GREF / gain_d[g]
```

Where:
- `raw` = raw u16 count
- `offset_d[g]` = offset map mean for die `d` at FPA group `g`
- `gain_d[g]` = gain map mean for die `d` at FPA group `g`
- `GREF` = reference gain (mean of gain_d0, ~31457)

### 5.3 FPA-Grid Selection

The live FPA temperature selects the nearest calibration group:

```
fpa_anchors = [19330, 29108, 34111, 39244, 49091]  (from cali header +36)
groups = [1, 2, 3, 4, 5]  (cali groups)

nearest_group = argmin(|fpa_live - fpa_anchors[i]|) for i in 0..4
```

### 5.4 Die Assignment

The 160×120 frame is divided into 4 quadrants (80×60 each):

| Quadrant | Die Index | Pixels |
|---|---|---|
| Top-left | 0 | [0..79, 0..59] |
| Top-right | 1 | [80..159, 0..59] |
| Bottom-left | 2 | [0..79, 60..119] |
| Bottom-right | 3 | [80..159, 60..119] |

**Note**: The firmware uses ctx+0x80 as die index, set by the device object's vtable
method at runtime. The quadrant-based assignment above is our best inference; the
actual firmware may use a different mapping (e.g., column-based strips).

### 5.5 Validation Results

| Test | 2×2 Range (counts) |
|---|---|
| Raw frame (no correction) | ~50987 |
| Gate-0 NUC only | ~50987 (2×2 unchanged) |
| Per-die flat-field only | ~1500 |
| Flat-field + gate-0 NUC | ~1500 |
| Cross-pair validation (wrong FPA) | ~10× worse |
| Self-validation (synthetic true×G+O) | 0.000 (perfect) |

### 5.6 Android Implementation

- `FactoryFlatField.kt` — loads `factory_flatfield.npz`, selects nearest FPA group,
  applies per-die correction to raw frame
- `factory_flatfield.npz` — contains:
  - `fpa` (int32, 5,) — FPA anchor temps [19330,29108,34111,39244,49091]
  - `die_gain` (float64, 5×4) — per-die gain means [fpa_group][die]
  - `die_off` (float64, 5×4) — per-die offset means [fpa_group][die]
  - `gref` (float64, 5,) — reference gain per FPA group
  - `width` (int64, scalar) — frame width (160)
  - `height` (int64, scalar) — frame height (120)
- Applied in `ViewerViewModel.kt` **before** `FactoryNuc.apply()` (gate-0 NUC)

---

## 6. Radiometric Core — Detailed Formula

### 6.1 Global Correction (always applied)

```
corrected_f32 = raw_u16 × (1.0 + ctx[0x50]) + ctx[0x54]
```

ctx+0x50 and ctx+0x54 are per-frame floats set during setup/parser. They represent
the global gain and offset for the radiometric conversion.

### 6.2 Per-Die Block — what it actually is

> **CORRECTION (2026-09-06).** There is no per-die 135-entry LUT and no bilinear
> interpolation here. `ctx+0x1900` is a **scalar emissivity**, and the whole
> block is read verbatim from `mag_cali.bin` — nothing computes it.

`sub_3f970` loads `ctx+0x80` (die index), clamps it against `ctx+0x16f8`, forms
`base = ctx + die*0x21c`, and then reads scalars out of that block. The bounds
check at `0x4003f9f8` is on `[base+0x1900]`, the emissivity — if it falls outside
the two literal bounds the code takes the global-only path at `0x4003fdc2`.

### 6.3 Block Layout (0x21c bytes per entry)

Written by the parser at `0x40042b68`..`0x40042ce8`, all offsets from
`ctx + die*0x21c`:

| Offset | Size | Content |
|---|---|---|
| +0x16fc | 32 B | profile name — `"lensf6.5"` |
| +0x171c | f32 | 0.0065 |
| +0x1720 | f32 | 1.0 |
| +0x1724 | f32 | 2.0 |
| +0x1728 | f32 | 1.0 |
| +0x172c | u32 | `n1` = 12 — the output shift (>>12 = /4096); parser rejects > 15 |
| +0x1730 | u32 | 20701 |
| +0x1734 | u32 | 20201 |
| +0x1738 | u32 | `n2` = 8 — segment count; parser rejects outside 1..25 |
| +0x173c | i32[n2] | segment breakpoints: 9344 9959 10299 10545 10760 11109 11356 11707 |
| +0x17a0 | f32[2] | ambient compensation: -9.0061e-06, 1.5125e-04 |
| +0x17a8 | f32 | param_A = 10.60016 (`sub_3f970` requires >= 1.0) |
| +0x17ac | f32 | param_B = 6.8699 (>= 1.0) — knee of the `lin()` map |
| +0x17b0 | f32 | param_C = 443.25949 (> 0) |
| +0x17b4 | f32 | param_D = 13828.37 |
| +0x17b8 | f32 | param_E = 44.490898 (> 0) |
| +0x17bc | f32 | param_F = 16567.859 |
| +0x17d0 | i32[n2][2] | piecewise (gain, offset) pairs |
| +0x1898 | u32 + i32[] | 12, then -1 -16 -25 -30 -36 -45 -51 -60 |
| +0x1900 | f32 | **emissivity** = 1.0, parser-clamped to (0, 1] |
| +0x1904 | i32 | 19790 — output clamp low (centi-K) |
| +0x1908 | i32 | 44930 — output clamp high (centi-K) |
| +0x1910 | f32 | 0.0, parser-clamped else zeroed |

Source: last 544 bytes of `mag_cali.bin` (offset 1855872), magic `0x6BB60001`,
4-byte magic + 0x21c payload. Exactly one such record exists in the file.
Extractor: `recon/extract_lens_profile.py` → `android2/recon/lens_profile.npz`.

### 6.3.1 params A..F — the `lin()` map (0x4003fa4a..0x4003fb3a)

A two-segment linear map with its knee at B, applied both to the measured value
and to the constant reference A:

```
lin(x) = (x > B) ? (E*x + F) : (C*x + D)
```

Continuous at B: `C*B + D = 16873.4` vs `E*B + F = 16873.5`. This is the
emissivity / reflected-ambient compensation; with emissivity = 1.0 it degenerates.

### 6.3.2 Segment application (0x40040020)

```
v    = (planck_interp - ctx[0x7750]) >> ctx[0x7754]     ; clamped to >= 0
seg  = first i in [0, n2-2] with v <= breakpoints[i], else n2-1
T_cK = ((gain[seg] * v) >> n1) + offset[seg]            ; 64-bit multiply
```

`v` is the **Planck-inverted** value, not raw counts — this curve is the final
linearisation applied after Planck inversion. All 8 segments are continuous at
their breakpoints to better than 0.005%, which independently confirms `n1 = 12`.

```
v = 9344  -> -24.85 degC       v = 10545 ->  73.81 degC
v = 9959  ->  24.12 degC       v = 11707 -> 170.53 degC
```

### 6.4 When ctx+0x80 is Out of Range

If die index ≥ max_die_count, sub_3f970 resets it to 0 (line 0x4003f9e6). This is
a safety fallback — die 0 is always valid.

### 6.5 When LUT is Invalid

If the first float of the LUT entry is NaN or out of bounds, sub_3f970 jumps to
0x4003fdc2 (global-only path): applies only the global correction without per-die
LUT interpolation. This is the fallback when the LUT hasn't been built yet.

---

## 7. Lens Distortion Remap Builder (0x400431d0)

> **CORRECTION (2026-09-06).** This function was previously documented as the
> "per-die radiometric LUT builder". It is not. It builds a **geometric
> distortion remap table**, and it never touches `ctx+0x1900`.

### 7.1 Signature

```
void remap_builder(ctx* r0, float p_bits_in_r1)
```

`p` arrives as raw float bits in **r1** (`mov r4, r1` at 0x431ea, `vmov s22, r4`
at 0x43216) — not in s0/s22 directly.

### 7.2 Output: ctx+0x254

```
free(ctx[0x254]) if non-null
ctx[0x254] = calloc(rows * cols * 4, 4)        ; rows=ctx[0x1e30]=120, cols=ctx[0x1e2c]=160
```

16 bytes per pixel = 4 × u32, each `(u16 source_index, u16 Q15 weight)`.
The four indices are `i, i+1, i+160, i+161` (a 2×2 bilinear tap on the
160-wide source grid) and the four weights sum to exactly 32768.

Measured behaviour:

| Pixel | index | weights | meaning |
|---|---|---|---|
| `[60,80]` (centre) | 9680 = 60*160+80 | 32256,256,256,0 | identity |
| `[0,0]` | 0 | 28160,2560,1792,256 | source ≈ (0.063, 0.086) |
| `[119,159]` | 19038 | 256,1792,2560,28160 | source ≈ (118.94, 158.91) |

Corners pull inward → **barrel/pincushion correction**.

### 7.3 Curve

A 10001-entry helper curve is built on the stack:

```
x    = i * 9.9999997e-05          ; literal at 0x435f4
f(x) = x / (x*x*p + 1.0)
```

It does **not** reach the output. `s22` is reassigned at 0x43318 to
`0.999 / (ratio² * p + 1)` where `ratio = min(rows-1,cols-1) / diag`, and only
that value feeds the per-pixel loop. Confirmed empirically: p = 0, 1e-6 and 1e-5
all produce a byte-identical `ctx+0x254`. With p = 0 the map degenerates to a
0.999 scale, i.e. essentially identity.

Other literals: `0.499` (0x43618), `128.0` (0x43614), `0.999` (0x435f8).

### 7.4 Callee Functions

| Address | Role |
|---|---|
| 0x4002ff58 | free |
| 0x4002ff70 | calloc |
| 0x400300f0 | sqrt fallback (NaN path) |
| 0x4003009c | stack canary check |

### 7.5 Practical impact

Negligible for the seam work — with the shipped coefficient the remap is
sub-pixel. Not implemented in `android2/`.

---

## 8. Build / Parser Phase

### 8.1 Build Step (0x4003baa0)

Called once during camera initialization. Reads `mag_cali.bin` and constructs the
per-pixel NUC tables in ctx.

**Input**: cali.bin data (128B header + 48 maps)
**Output**: ctx+0x16d8 (ref), ctx+0x16e0 (gain), ctx+0x16ec (offset), ctx+0x16f4 (nseg),
ctx+0x16f8 (shift)

**Heap allocation**: calls0x4002ff58 (malloc) for ref (0x50120440), gain (0x50621840),
offset (0x50661c40). Total ~6.3 MB.

**Stack**: 0x1e04 bytes (~7.7 KB)

### 8.2 Parser/Setup (0x400424d0 = PARSER)

Called per-frame or on FPA switch. Reads additional parameters from ctx and sets up
the processing pipeline.

**Reads**: ctx+0x1738 (LUT-ready flag), ctx+0x17a8 (per-die params), ctx+0x1e2c/0x1e30
(grid dims)

**Writes**: ctx+0x1900 (LUT data, conditional on ctx+0x1738), ctx+0x50/0x54 (global
correction)

**Conditional**: ctx+0x1900 is only populated if ctx+0x1738 is set (by device init).
If not set, ctx+0x1900 stays 0 (causing sub_3f970 to use global-only path).

### 8.3 Build Buffer Pointers

After `run_build()` (Unicorn emulation):

| Pointer | Content | Size |
|---|---|---|
| 0x50120440 | ref frame (u16, 160×120) | 38400 |
| 0x50133440 | ref2 frame | 38400 |
| 0x50621840 | gain tables (u16, N×H×W×nseg) | ~5.4 MB |
| 0x50661c40 | offset tables (u16, N×H×W×nseg) | ~5.4 MB |

Heap top after build: 0x50723540. bufA (frame buffer) ~0x50020040.

---

## 9. Device Object (vtable)

### 9.1 Structure

The device object is a vtable-based C++ object. Two pointers in ctx:
- ctx+0x20c: → JNI table (outer device handle)
- ctx+0x218: → internal table (inner device, contains vtable)

### 9.2 Known Vtable Slots

| Slot | Content | Called by |
|---|---|---|
| device_entry[0] | Process step function pointer | Orchestrator (0x4004cfe0) via `blx r7` |
| device_entry[0x24] | → ctx pointer | Used to access ctx from device |

### 9.3 Sentinel Value

When no real device is connected, ctx+0x218 points to a sentinel object containing
0xdeadbee0. The process step (0x4004228c) checks ctx+0x218 and crashes if it's
the sentinel (cannot proceed without device).

### 9.4 What the Device Object Provides

The device object's vtable methods:
1. **NUC apply** — invokes sub_45ca4 (0x40045ca4) per-pixel
2. **Die index assignment** — writes ctx+0x80 per pixel
3. **Per-die LUT construction** — builds ctx+0x1900 from cali data
4. **Frame buffer management** — allocates/fills ctx+0x234

Without the device object, the pipeline cannot run the per-pixel loop or NUC. This
is why our Android implementation bypasses the device entirely and applies corrections
directly in Kotlin.

---

## 10. Android Integration (android2/)

### 10.1 Current Pipeline (ViewerViewModel)

```
raw frame (u16, 160×120)
  │
  ├→ FactoryFlatField.apply(raw, fpa)      ← per-die 2×2 correction
  │    (loads factory_flatfield.npz, selects nearest FPA group)
  │
  ├→ FactoryFlatField.apply(ffcRef, fpa)   ← correct shutter reference
  │
  ├→ FactoryNuc.apply(corrected_raw, fpa, corrected_ffcRef)  ← gate-0 NUC
  │    (per-pixel piecewise: v=(raw-ref)>>1, gain/offset tables)
  │
  ├→ seamResidual + seam2d                 ← seam removal
  ├→ bad pixel correction (3×3 median)
  ├→ orientation (rotate + mirror)
  ├→ bilateral denoise
  └→ auto-range → palette → ARGB display
```

### 10.2 File Map (android2/)

| File | Role |
|---|---|
| `app/src/main/java/com/magnity/viewer/pipeline/FactoryFlatField.kt` | Per-die flat-field (NEW) |
| `app/src/main/java/com/magnity/viewer/pipeline/FactoryNuc.kt` | Gate-0 NUC (existing) |
| `app/src/main/java/com/magnity/viewer/pipeline/Radiometry.kt` | Planck-LUT radiometry |
| `app/src/main/java/com/magnity/viewer/ui/ViewerViewModel.kt` | Runtime pipeline |
| `app/src/main/java/com/magnity/viewer/data/Npy.kt` | NPY reader (+toFloatArray) |
| `app/src/main/java/com/magnity/viewer/usb/MagCamera.kt` | USB driver |
| `recon/factory_flatfield.npz` | Per-die flat-field data (NEW) |
| `recon/factory_nuc_grid.npz` | Gate-0 NUC tables |
| `recon/planck_luts.npy` | Planck LUTs |

### 10.3 Build

```bash
cd android2
export JAVA_HOME=/opt/android-studio/jbr
export PATH=$JAVA_HOME/bin:$PATH
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

Assets copied by `copyThermalAssets` task: `factory_nuc_grid.npz`, `factory_flatfield.npz`,
`planck_luts.npy`.

---

## 11. Known Gaps / Unfinished Work

### 11.1 ctx+0x80 Writer

The die index is set per-pixel by the device object's vtable method. We cannot trace
this statically. Our implementation infers the die index from pixel position (quadrant).
If the firmware uses a different mapping, the flat-field will be applied to wrong pixels.

**How to verify**: capture a frame with a known uniform scene, compare the 2×2 pattern
in the raw frame vs the corrected frame. If the seam alignment is off, the die
assignment needs adjustment.

### 11.2 Per-Die LUT Contents — RESOLVED (not a gap)

There is no per-die LUT. See §6.2/§6.3: the block is a single lens profile
(`lensf6.5`) read straight out of `mag_cali.bin`, and `ctx+0x1900` is a scalar
emissivity. Fully extracted; no SDK, no 32-bit device, no emulation required.

### 11.3 LUT Builder Full Formula — RESOLVED (wrong function)

`0x400431d0` is the lens distortion remap builder (§7), not a radiometric LUT
builder. Its output is `ctx+0x254` and its effect at the shipped coefficient is
sub-pixel.

### 11.4 Device Vtable Complete Structure

Only one slot identified (process step function pointer). The full vtable has unknown
number of slots. Key missing methods:
- Per-die LUT construction (builds ctx+0x1900 from cali maps)
- Die index assignment (writes ctx+0x80 per pixel)
- Runtime multi-gate NUC builder (builds bufB/bufC)

### 11.5 Multi-Gate NUC

The build step only produces gate-0 (per-pixel FPN). The firmware's full NUC uses
multiple gates (breakpoints + per-segment gain/offset). The multi-gate NUC builder
is called through the device vtable and cannot be run without hardware.

Our current approach: gate-0 NUC via FactoryNuc.kt + per-die flat-field via
FactoryFlatField.kt. This covers the most visible artifact (2×2 partition) but
does not replicate the full multi-gate correction.

---

## 12. Build Commands & Reproducibility

### 12.1 Unicorn Emulation (recon/)

```bash
cd recon
source activate testenv  # or: conda activate testenv
python emu_build_nuc.py  # runs build step, outputs ctx dump + NUC tables
```

Requires: unicorn 2.1.4, numpy 2.2.6, capstone 6.0.0 (in testenv conda env).
Python: `/home/delphic/miniconda3/envs/testenv/bin/python`

### 12.2 APK Compilation

```bash
cd android2
export JAVA_HOME=/opt/android-studio/jbr
export PATH=$JAVA_HOME/bin:$PATH
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk` (~27 MB)

---

## Appendix A: Function Address Quick Reference

| VA | Name / Role | Notes |
|---|---|---|
| 0x4004cf5c | MAG_StartProcessImage | Public API entry |
| 0x4004cfe0 | Orchestrator core | Device table management |
| 0x400424d0 | Parser/setup (PARSER) | Cali→ctx setup, conditional LUT |
| 0x4004228c | Process step | Per-frame dispatch, needs device |
| 0x400447c0 | Per-pixel executor | Reads ctx+0x234, calls sub_3f970 |
| 0x4003f970 | sub_3f970 (radiometric core) | Per-pixel: global + LUT bilinear |
| 0x40045ca4 | sub_45ca4 (NUC apply) | Per-pixel piecewise NUC |
| 0x400431d0 | LUT builder | 10001-point curve + per-pixel indices |
| 0x400404e8 | Post-process | Current→temp, palette, orientation |
| 0x400455bc | LUT lookup helper | Binary search + interpolation |
| 0x4003baa0 | Build step | Cali→NUC tables |
| 0x4002ff58 | malloc | Heap allocation |
| 0x4002ff70 | calloc | Heap zero-allocation |
| 0x40030000 | free | Heap deallocation |
| 0x400300f0 | sqrt fallback | NaN-safe sqrt |
| 0x40047dfc | Stats function | Min/max/mean computation |
| 0x40051f80 | Error logging | String format + log |
| 0x40052034 | Error reporting | Error output |

---

## Appendix B: Emulation Harness

Key emulation scripts in `recon/`:

| File | Role |
|---|---|
| `emu_common.py` | `build_parsed_ctx()`, `CALI` path, `Emu` harness |
| `emu_build_nuc.py` | `run_build()`, `np_apply`, buffer table layout |
| `flatfield.py` | Per-die flat-field validation |
| `diemeans.py` | Die-mean extraction from cali maps |
| `validate_full2.py` | Full pipeline validation |

---

*Last updated: 2026-08-30. Based on reverse engineering of `libcoresdk.so` (MAG-Mx firmware)
and live camera probing.*
