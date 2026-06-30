# Emulation route to the factory NUC — reverse-engineering record (route A)

Goal of route A: run the camera SDK's own ARM code under emulation (no Android device) to
expand `mag_cali.bin` into the **per-pixel factory NUC** and the radiometric tables, instead
of guessing the map format.

Status: **COMPLETE (2026-06-30).** The full factory build chain now runs end-to-end under
emulation and the per-pixel piecewise gain/offset tables are extracted. A pure-numpy port of
the apply is **bit-exact** to the emulated firmware (max|diff| = 0 over all 19200 px at three
raw levels). Deliverable: `emu_build_nuc.py` → `factory_nuc_tables.npz`.

## TL;DR — the build chain (all emulated from the real libcoresdk.so)
```
parser   sub_424d0(ctx, path)      -> populate ctx + the 6.3 MB bufA
prepare  sub_3a7f8(ctx, 0, 0)      -> sub_3a574 (section-select into bufA) +
                                       sub_3b024 (temp-prep -> interp weight ctx[0x13e0])
build    sub_3a7f8(ctx, 1, 2)      -> breakpoint interp -> bufB,  gain build (sub_3bc54) -> bufC
apply    sub_45ca4(ctx, raw)       -> per-pixel piecewise correction
```
`sub_3a7f8(ctx, mode, param)` gates on mode at 0x3a8bc (`cmp r6,#1; blt return`): **mode 0 =
prepare only, mode 1 = run the interp+gain build** (mode 1 also needs param>=2; it then
auto-computes the radiometric ranges ctx[0x7760/68/64/6c/70]). Must call mode 0 THEN mode 1.
Temp source for section bracketing is **ctx[0x38]** (anchor-table-A units, A=[9564..49091]).

### Confirmed table layout (nseg=3 segments, npix=19200) and apply
```
breakpoint[i,s] = bufB_int16 [2*i + s]            s in 0..nseg-2   (ctx[0x16e0])
gain[i,s]       = bufC_uint16[2*i + 2*npix*s]     s in 0..nseg-1   (ctx[0x16ec])
offset[i,s]     = bufC_uint16[2*i + 2*npix*s + 1]
apply:  v = (raw[i] - offset_ref[i]) >> 1
        s = first segment with v <= breakpoint[i,s] (else last)
        out = clamp(((v*gain[i,s]) >> shift) + offset[i,s], 0, 65535)   shift=ctx[0x104]=12
```
`offset_ref` = ctx[0x1d58] (per-pixel dark/reference frame; ctx[0x1d70] must be !=0). In the
partial init it is null, so the apply driver feeds the interpolated reference (ctx[0x16d8])
in its place. The full device init loads it from the cali. NB: bufB/bufC are **interpolated
for the FPA temp in ctx[0x38]** — rerun the build per operating point.

Historical note (pre-2026-06-30): the original blocker was "drive the lazy build chain that
fills the gain/offset tables". The two missing pieces were (a) the build is a SEPARATE
function `sub_3a7f8` from the section-select `sub_3a574` (sub_3a8c0 is mid-`sub_3a7f8`, and my
earlier capstone disasm of it was 2 bytes misaligned — real loop head is 0x3a8c2), and (b) it
only runs the interp/gain loop when called with **mode>=1**. Supplying the radiometric range
gates + calling mode 0 then mode 1 unlocked it; no full `MAG_StartProcessImage` init needed.

All addresses are **file/vaddr offsets in `libcoresdk.so`** (armeabi-v7a, ARM32 Thumb).
Emulator maps it at `BASE = 0x40000000`, so runtime addr = BASE + offset.

---

## 1. Emulation harness — `recon/emu_harness.py` (REUSABLE, validated)

- Maps all PT_LOAD segments of `libcoresdk.so` at `BASE=0x40000000`.
- Applies `R_ARM_RELATIVE` relocations (and binds internal `GLOB_DAT/ABS32/JUMP_SLOT`).
- **Imports trapped**: every undefined-symbol GOT slot is pointed at `FAKE=0x10000000`
  (a region filled with Thumb `bx lr` = `0x4770`); a `UC_HOOK_CODE` over FAKE dispatches to
  Python handlers (malloc/memcpy/…); unhandled imports return 0 and are logged.
- **VFP/NEON MUST be enabled** or `vpush/vldr` = "invalid instruction":
  `reg_write(C1_C0_2, … | (0xf<<20))` (CPACR) and `reg_write(FPEXC, 0x40000000)`.
- `HEAP=0x50000000` (bump allocator `Emu.malloc`), `STACK=0x7ff00000`.
- `Emu.call(addr, args=(), regs_f={s_idx:float}, count=…)` runs a Thumb function (sets r0-r3,
  LR=sentinel `0xdeadbee0`, emu_start until sentinel).
- **VALIDATION (passes)**: emulated `sub_3f970` (BASE+0x3f970) == `radiometry.py` bit-exact
  (U: 12000→261283, 13000→296470, 14117→333137, 15414→373147). Run: `python3 emu_harness.py`.

### PLT → import name resolution
`.plt` base `0x2ff2c`, PLT0 = 20 bytes, each stub 12 bytes; for a stub at `S`:
`idx = (S - 0x2ff2c - 20)//12`, `name = .rel.plt[idx]` symbol.
Key imports used by the parse/build path:
`fread=0x2ffb8, fseek=0x2ffd0, ftell=0x2ffc4, fopen=0x2ffdc, feof=0x2ffa0, fwrite=0x30024,
__aeabi_memclr4=0x3003c, _ZdaPv(operator delete[])=0x2ff58, strncpy=0x30168, access=0x30108,
pthread_mutex_lock=0x30048/unlock=0x30054, __errno=0x300a8`. Also need `operator new`
`_Znwj`/`_Znaj` → malloc.

---

## 2. Cali parser — `sub_424d0(r0=context, r1=path)`  (driver: `recon/emu_parse_nuc.py`)

- Found via the cali magic check `movt r1,#0x5aa5` @ **0x42642**; accepts magic
  `0x5AA50003` / `0x5AA50004`.
- Called from **0x4d190** (in `MAG_StartProcessImage`); the context is the persistent
  per-device object = `[device_table + dev_idx*0x498 + 4]`.
- Reader: **`sub_346aa(stream, dest, size, count)`** = `fread(dest,size,count, stream[+4])`.
- IFStream ctor **`sub_348d0(streamobj, path)`**: `fopen` → `fseek(END)` → `ftell` →
  `streamobj[+8] = filesize` → `fseek(0)`. (We serve `mag_cali.bin` via hooked
  fopen/fread/fseek/ftell/feof — the real wrappers then "just work".)

### Gates the parser validates (must be set in ctx before calling, else it diverts to an
error/log path that null-derefs an unconstructed `std::ostream` @ pc 0x1fbeb2 `[r2-0xc]`):
| ctx offset | value | meaning |
|---|---|---|
| `0x20c`  | 160 (≠0) | W, nonzero gate (+ path[0] must be ≠0) |
| `0x1e2c` | 160 | W — compared to cali header W @0x4274a |
| `0x1e30` | 120 | H — compared to cali header H |
| `0x7740` | (bound) | coeff bound-check loop @0x427f6; also pixel-count later |
- **Size check @0x42900**: `cmp filesize(stream[+8]), expected_sl`; `expected_sl=6304128`
  (= the 6.3MB NUC working-buffer size) > our 1.86MB file under partial init, so the driver
  **forces it to pass** by writing `R0=0x7fffffff` at 0x42900. (With full device init the
  real `expected_sl` would be ≤ filesize.)
- Result: **parser consumes all 1,856,416 bytes, returns OK**, allocates ~7.35 MB heap.

---

## 3. Cali file structure (decoded from the parsed context)

Header (first ~128 bytes), little-endian u32: `magic=0x5AA50003, W=160, H=120, 6, 3, 8,
-50000, dataoff=1024, …` then three 6-entry sensor-temp anchor tables:
```
A @hdr+36 = [9564,19330,29108,34111,39244,49091]   -> ctx[0x1404..0x1418]
B @hdr+60 = [9064,18830,28608,33611,38744,48591]   -> ctx[0x1454..0x1468]  (= A-500)
C @hdr+84 = [8041, 9311,10588,11301,12075,13642]   -> ctx[0x14a4..0x14b8]
then [32,32,32,32,32]                              -> ctx[0x14f4..0x1504]
```
Data @1024: **48 maps of 160×120 uint16** (stride 38400) = **6 sensor-temp blocks ×
[2 gain maps (pos 0,1) + 6 reference frames (pos 2..7)]**. (Plain in the file; also dumped
to `recon/factory_nuc_maps.npy`.) The 6 frame-tail sensor-temp readings (live tail+8) are in
the **same units as table A**, so the live FPA temp directly indexes these 6 blocks.

### What the parser sets in ctx (73 nonzero fields; see also `emu_ctx.npy`)
- `ctx[0x4c]=7, ctx[0x104]=12, ctx[0x20c]=160`
- A/B/C tables (above); `[32×5]` @0x14f4
- **heap pointers**: `ctx[0x1540]→bufA(6,297,600 B = the 48 maps, 6 sensor-temp sections)`,
  `ctx[0x1544]`/`ctx[0x16e0]→bufB(263,168 B)`, `ctx[0x16ec]→bufC(786,432 B)`,
  `ctx[0x1548]→ small`. **bufB and bufC are ZERO after parse — filled lazily.**
- section index table `ctx[0x159c..0x15b0]=[128,1049728,2099328,3148928,4198528,5248128]`
  (6 sections of bufA, stride ~1,049,600).
- radiometric params `ctx[0x774c]=8 (num LUTs), ctx[0x7750]=-50000, ctx[0x7754]=3`.

---

## 4. Per-pixel NUC APPLY — `sub_45ca4(ctx)` (loop body 0x45d02–0x45d56, DECODED)

Per pixel `i`:
```
diff   = raw[i] - offset_ref[i]                 # ip = ctx[0x1d58] (per-pixel offset ref); r1 = raw frame
v      = diff >> 1                              # /2
seg    = linear-search per-pixel breakpoints in bufB(ctx[0x16e0]) for v   # inner loop, r4=ctx[0x13f4]-1 segments
gain   = bufC[ stride*seg ]      (u16)          # r6 = ctx[0x16ec]
offset = bufC[ stride*seg + 1 ]  (u16)
out    = (v * gain) >> shift  +  offset         # shift = [sp+4]
out    = clamp(out, 0, 65535)
*ctx[0x22c]++ = out  (u16)                       # corrected output frame
```
- It's a **per-pixel, multi-segment piecewise-linear** correction (NOT two flat maps).
- bufB ≈ 13.7 B/px (per-pixel breakpoints), bufC ≈ 41 B/px (per-pixel gain+offset per seg) →
  ~3–5 segments/pixel.
- Inputs needed to run apply: `ctx[0x1d58]` (offset_ref), the raw frame ptr, `ctx[0x22c]`
  (out), `ctx[0x7740]` (pixel count), `ctx[0x13f4]` (#segments), `shift`, and **bufB/bufC
  pre-filled by the build step**.
- **Why our earlier flat-map attempts failed** (corr 0.02–0.26 vs live FFC): the factory NUC
  is per-pixel piecewise; you cannot subtract it as a flat map. Near one operating temp the
  curve linearizes to one gain+offset per pixel → this is what route B captures.

---

## 5. NUC BUILD — `sub_3a7f8` (fills bufB/bufC) — DONE, run by `emu_build_nuc.py`

`sub_3a7f8(ctx, mode, param)` is the per-frame build orchestrator (NOT `sub_3a8c0`, which is a
mid-function block of `sub_3a7f8` — the breakpoint interp loop, real head 0x3a8c2). It:
- locks `ctx+0x21c` mutex; gates on `ctx[0x20c]` (W) and `ctx[0x12d4]`.
- **mode 0** (`r1==0`): calls `sub_3a574(ctx)` (bracket FPA temp `ctx[0x38]` vs anchors A,
  set bufA section pointers `ctx[0x16d8/dc/e4/e8]`) then `sub_3b024(ctx)` (temp-prep: compute
  interp weight `ctx[0x13e0]` via `(T-Tlo)<<16/(Thi-Tlo)` clamped), then returns at 0x3a8bc
  because `cmp r6,#1; blt`.
- **mode 1, param>=2**: auto-computes radiometric ranges `ctx[0x7760/68/64/6c/70]` from
  `ctx[0x7740]`(npix)/`ctx[0x13f4]`(nseg), then runs the breakpoint interp loop (→ bufB) and
  `bl sub_3bc54` (→ bufC gain/offset). Reached because `cmp r6,#1` is not `<1`.

So the build = `sub_3a7f8(ctx,0,0)` then `sub_3a7f8(ctx,1,2)`. No radiometric-LUT-init or full
`MAG_StartProcessImage` needed — the build is pure int16 map interpolation of bufA (the
radLUT/`.bss` tables are only used by the later temp-conversion `sub_3f970`, not the NUC).

Other bufB/bufC references: `0x3c8ce` is a second copy of the apply; `0x42a02` (parser alloc).

---

## 6. Route A — DONE. How to reproduce / extend

`python3 emu_build_nuc.py` runs parse→prepare→build→apply and writes `factory_nuc_tables.npz`
(`breakpoints[H,W,nseg-1]`, `gain[H,W,nseg]`, `offset[H,W,nseg]`, `ref[H,W]`, `nseg`, `shift`,
`fpa_anchor`). It also proves the numpy apply (`np_apply`) is bit-exact to the emulated
firmware apply. For other operating points, set `FPA_ANCHOR` (ctx[0x38], anchor-A units) and
rerun — bufB/bufC are temperature-interpolated.

To integrate into the live app: either (a) cache `factory_nuc_tables.npz` for a grid of FPA
temps and interpolate at runtime, or (b) reimplement the cheap build (section-select +
weight + breakpoint/gain interp) in numpy so tables track the live FPA temp; then apply with
`np_apply` using the app's shutter/FFC reference as `offset_ref`.

## Files
`recon/emu_harness.py` (engine, validated bit-exact), `recon/emu_common.py` (shared parser
setup: `build_parsed_ctx()`), **`recon/emu_build_nuc.py` (route-A deliverable: build+extract+
numpy-validate → `factory_nuc_tables.npz`)**, `recon/emu_parse_nuc.py` (parser dump driver),
`recon/emu_ctx.npy` (parsed context dump), `recon/emu_heap.bin` (7.35 MB heap dump — gitignore),
`recon/factory_nuc_maps.npy` (48 raw maps), `recon/planck_luts.npy` (8 Planck curves),
`recon/factory_nuc_tables.npz` (extracted per-pixel breakpoints + gain/offset @ fpa_anchor).
Full reliable disasm: `llvm-objdump -d --triple=thumbv7-linux-android libcoresdk.so`
(needed — linear capstone over `.text` desyncs on the literal pools).
See also `PROTOCOL.md` (radiometry/temperature) and the project memory.
