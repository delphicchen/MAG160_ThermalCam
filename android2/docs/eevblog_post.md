# Draft reply — EEVblog "Attempt on reverse engineering the Magnity MAG160core USB thermal camera"

Framing: the factory apps no longer run on current Android, so we rebuilt a viewer.
Findings below are a by-product. Honest about what is verified and what is not.

---

Hi all — your protocol write-up saved me a lot of time, so here is what I found going at
the same camera from the Android side. My unit is a Mag-Mx (VID 0x833C, PID
0x0001/0x0002), 160x120 @ 15 fps.

The reason I started: **the factory apps (Mag-Cx / Mag-Mx) no longer work on Android
15+.** So I sat down with Claude Code and rebuilt a viewer from scratch — reverse
engineering the vendor SDK as I went. It streams, reads absolute temperature with
min/max/spot markers, saves snapshots and records video. No watermark (TinLethax, that
one is for you). Source, plus a long reference document of everything below:

https://github.com/delphicchen/MAG160_ThermalCam

Two separate things came out of this, and I want to keep them apart, because one is
solid and the other is partly inference.

## 1. Driving the vendor SDK yourself — this part works

You do not have to decode the calibration file to get real temperature. The Android
SDK's `libcoresdk.so` (arm64) gives it to you directly, and you can link it into your own
app:

- `linkCamera` — it requests USB permission itself and opens its own
  UsbDeviceConnection
- `prepare`, then `startProcessImage`
- poll `getTemperatureData()` — one int per pixel

**The unit is milli-Celsius**, not the centi-Kelvin the firmware's own piecewise curve
works in; the native side has already converted. Cross-check: the factory Elo Java
wrapper's `createLegalResult` and `getInnerIRCameraTemp` both do `rawInt * 0.001d`, and
there is no 273.15 term anywhere in that class. Also available: `getTemperatureProbe(x,
y)` for a single point, and a frame-statistics call returning min/ave/max plus average
NETD.

Gotchas that cost me time:

- USB is exclusive. Any raw reader of yours must close first, and the firmware
  re-enumerates when you release it — wait ~750 ms before the SDK's first enumeration
  attempt or it will miss the device.
- The SDK runs its own shutter/FFC schedule and its own NUC. Do not layer your own
  correction on top of its output; it is already fully corrected absolute temperature.
- On Android 14+ a custom-action USB permission receiver must declare its export state
  or you get a SecurityException at registration — instant crash on launch.
- USB attach broadcasts are unreliable on recent Android. Polling `UsbManager` every 2 s
  is what actually works, but only auto-connect devices you already hold permission for,
  or every re-enumeration re-prompts the user.

That is what my app uses for measurement today.

## 2. What the disassembly says about calibration — read out of the binary, partly unverified

Before settling on the SDK I tried to reproduce the radiometry myself: Ghidra on
`libcoresdk.so`, plus a Unicorn harness to run individual functions against captured
frames without hardware attached.

**Important caveat, stated up front:** everything in this section was *read out of the
vendor binary*, not independently derived and confirmed against ground truth. I have
never compared my reimplementation pixel-by-pixel against the SDK's own output, and I
have no black body. Treat it as a map of the binary, not as verified physics.

### mag_cali.bin (the file you pull over EP 0x84)

128-byte header, then 48 maps of 160x120 u16 (38400 bytes each); 1,843,328 bytes total.

    +0    u32   magic   1520762883
    +4    u32   160     width
    +8    u32   120     height
    +12   u32   6       number of cali groups
    +16   u32   3       number of NUC segments
    +20   u32   8       maps per group
    +28   u32   1024    map stride
    +36   u32x6 [9564, 19330, 29108, 34111, 39244, 49091]   anchor table A (FPA temps)
    +60   u32x6 [9064, 18830, 28608, 33611, 38744, 48591]   anchor table B (= A - 500)
    +84   u32x6 [8041, 9311, 10588, 11301, 12075, 13642]    scene-temp anchors
    +108  u32x5 [32, 32, 32, 32, 32]                        unknown

48 maps = 6 groups of 8. Group 0 is the gate-0 NUC (map 0 = gain, 1 = offset, 2 and 3 =
breakpoints). Groups 1..5 are gain/offset pairs, each calibrated at a different FPA
temperature from anchor table A:

    group 1 : gain = map 8,  offset = map 10   @ FPA 19330
    group 2 : gain = map 16, offset = map 18   @ FPA 29108
    group 3 : gain = map 24, offset = map 26   @ FPA 34111
    group 4 : gain = map 32, offset = map 34   @ FPA 39244
    group 5 : gain = map 40, offset = map 42   @ FPA 49091

Odd slots in groups 1..5 are unused in my file.

On your black-body question: I believe the table at +84 is the scene-temperature anchor
set for the radiometric fit, but **I have not pinned down its unit** — the values look
too small for centi-Kelvin and too large for centi-Celsius over a plausible calibration
range. Anyone with a black body could settle it by comparing readings against that
table.

### The 2x2 pattern is four dies

On a flat scene the frame splits into four quadrants at different levels: the sensor is
stitched from 4 dies. The gate-0 NUC removes per-pixel FPN and leaves the per-die step
completely untouched — groups 1..5 are what address it.

What I implemented (my inference, not confirmed firmware behaviour):

    corrected = (raw - offset_d[g]) * GREF / gain_d[g]

`d` = die (I use quadrants, 80x60 each), `g` = the group whose FPA anchor is nearest the
live FPA temperature, `GREF` = mean of the group-1 gain map (~31457 on my unit). The
firmware actually takes the die index from `ctx+0x80`, written at runtime by a device
vtable method I could not trace statically — so the quadrant mapping is a guess that
happens to work; it could really be column strips.

Flatness on a uniform scene, range across the four quadrants:

    raw frame                       ~50987 counts
    gate-0 NUC only                 ~50987   (2x2 unchanged)
    per-die correction              ~1500
    deliberately wrong FPA group    ~10x worse

Again: that is self-consistency — the image got flat — not accuracy. The per-die gain
maps show the structure growing with sensor temperature (bottom-left / top-left ratio
1.046, 1.152, 1.213, 1.303 across groups 2..5), which is why picking the group by FPA
temperature matters.

### Radiometric core

Per pixel, always applied first:

    corrected_f32 = raw_u16 * (1.0 + ctx[0x50]) + ctx[0x54]

`ctx+0x50` / `ctx+0x54` are per-frame floats written during setup. The per-die block
after it is a piecewise-linear segment set (nseg = 3 in the header), **not** a lookup
table — I chased a phantom "135-entry per-die LUT with bilinear interpolation" for a
while before the disassembly said otherwise.

### Why I gave up on my own pipeline

Honest result: it never got good enough. Gate-0 NUC plus the per-die correction kills
the visible 2x2 seam, but the full firmware NUC is multi-gate and is built through the
same device vtable I cannot reach without hardware, so my version needed a level trim
and a seam patch to stay put, and it drifted after every FFC. The SDK path agrees with
the factory app; mine did not. So the app now uses the SDK for all measurement, and I
removed my pipeline from the live code (it is still in git history, documented, in case
the SDK ever breaks on a future Android release).

## Two traps worth knowing

**1. The function at 0x400431d0 is not a LUT builder.** It builds a geometric distortion
remap into `ctx+0x254`: 16 bytes per pixel, four `(u16 source_index, u16 Q15 weight)`
taps forming a 2x2 bilinear sample, weights summing to 32768. And at the shipped
coefficient it is effectively identity — p = 0, 1e-6 and 1e-5 all produce a byte-identical
table, because the value that reaches the per-pixel loop is reassigned to
`0.999 / (ratio^2 * p + 1)`. Sub-pixel. I did not implement it.

**2. A learned flat-field map goes stale at the next FFC.** Averaging a uniform scene and
freezing the residual as a correction map looks great — until the next shutter event. The
map is defined relative to the current FFC reference, NUC group and seam state, and an
FFC replaces all three. Measured here: residual FPN sd 0.00 right after learning, 8.05
after the next FFC, i.e. it re-injects the seams it was removing. Keep the averaged *raw*
frame and re-derive the map whenever the reference changes.

## Still open

- Unit of the scene-temperature anchors at +84 (needs a black body)
- What writes `ctx+0x80`, i.e. the real die-index mapping
- The multi-gate NUC builder — reachable only through the device vtable, so it needs the
  camera in the loop rather than static analysis

One unrelated detail others might find useful: for display I upscale on the GPU with the
Anime4K CNN shader, run over the temperature field *before* palette mapping. Thermal
images are smooth regions with hard edges, much like animation, so animation-oriented
upscalers suit them better than a generic resize. Measurement still reads the native
160x120 grid.

Happy to answer questions, dump my `mag_cali.bin` for cross-checking against another
serial, or build an APK for anyone who wants to try the viewer on their own unit. The
app is free for noncommercial use; if you find any of this useful, a star on the repo is
welcome — it tells me whether it is worth continuing to tidy up.
