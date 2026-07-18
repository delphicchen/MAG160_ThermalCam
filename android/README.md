# MAG Thermal — Android port

Native Android app for the Magnity 833c USB thermal camera: the Linux viewer
(`../viewer.py` + friends) ported back to the platform the camera originally shipped
for — but running our reverse-engineered open pipeline instead of the closed
`MAG-Mx.apk` SDK.

**Target class: Dimensity 9200 or faster** (arm64-v8a only, Android 13 / API 33+).
The image pipeline (FFC, bad-pixel, temporal/bilateral denoise, factory NUC, Planck
radiometry) runs comfortably in real time on a big-core at 160×120 @ 15 fps; neural
super-resolution runs on the MediaTek APU via NNAPI when available (XNNPACK / CPU
fallback otherwise).

## Building

Open `android/` in Android Studio (Ladybug+) and run, or from the command line:

```bash
cd android
./gradlew assembleDebug        # needs the Android SDK (API 35) + JDK 17
```

The shared binary assets — `thermal_espcn_{2,4}x.onnx(.data)`, `factory_nuc_grid.npz`,
`recon/planck_luts.npy` — are **copied from the repository root at build time**
(`copyThermalAssets` task), so the Android and Linux apps always use identical models,
NUC tables and Planck curves. There is nothing to convert or regenerate.

## Running

1. Connect the camera to the phone with a USB-C OTG adapter (the camera is
   self-powered per its descriptor, drawing ≤100 mA from the port).
2. Plug-in auto-launches the app (USB `device_filter.xml`, VID 0x833C); or open the
   app and tap **Connect camera** and accept the USB permission dialog.
3. The app performs a startup FFC and begins streaming. From there the controls mirror
   the Linux viewer:
   - **FFC (shutter)** and **Auto-FFC** keyed on live FPA-temperature drift (the
     firmware's real shutter trigger), with a time fallback.
   - **Palettes** (ironbow, inferno, jet, …) — the exact same 256-entry LUTs, baked
     into `Palettes.kt`.
   - **Enhance** toggles: bad-pixel correction (learned + per-frame), flat-field /
     2-point gain NUC capture, temporal + spatial denoise.
   - **Factory NUC (radiometric)** — the bit-exact per-pixel piecewise correction
     reversed from `mag_cali.bin`, interpolated from the FPA-temp grid.
   - **Neural super-res 2×/4×** — ESPCN via ONNX Runtime (NNAPI → XNNPACK → CPU).
   - **Temperature**: tap the image to lock a CAL marker on a known-temperature
     object, then *+ Add cal point* and enter its °C (≥2 points calibrates the
     Planck-LUT radiometry; long-press the image clears the marker). Calibration
     persists across runs.
   - **Snapshot** (PNG → `Pictures/MagThermal`) and **DDT save/load** — radiometric
     snapshots in the same `.ddt` format as the Linux app, re-measurable offline.

## Code map

| file | role | ports |
|---|---|---|
| `usb/MagCamera.kt` | USB driver: cmd+ack transport, EP 0x81 frame reader, shutter FFC, FPA telemetry | `magcam.py` |
| `pipeline/Radiometry.kt` | Planck-LUT radiometry + reference-point calibration | `radiometry.py` |
| `pipeline/FactoryNuc.kt` | factory per-pixel piecewise NUC from the FPA-temp grid | `factory_nuc_grid.py` |
| `pipeline/Enhancer.kt` | BPC, temporal IIR, flat-field, 2-pt gain NUC | `enhance.py` |
| `pipeline/ImageOps.kt` | median/gaussian/bilateral/bicubic float-image primitives | (OpenCV calls) |
| `pipeline/NeuralSR.kt` | ESPCN inference, NNAPI/XNNPACK/CPU | `enhance.py NeuralSR` |
| `data/Npy.kt` | minimal NPY/NPZ reader (shared assets, unconverted) | — |
| `data/Ddt.kt` | radiometric snapshot format (interchangeable with Linux) | `ddt.py` |
| `data/CalibrationStore.kt` | `calibration.json` persistence (same schema) | `viewer.py` |
| `ui/ThermalViewModel.kt` | processing loop, state, bursts, auto-FFC | `viewer.py Viewer` |
| `ui/ThermalScreen.kt` | Compose UI: live view, markers, controls | `viewer.py` UI |
| `ui/Palettes.kt` | baked 256-entry palette LUTs (identical to matplotlib's) | `viewer.py make_lut` |

## Verification status

The platform-independent core (`Npy`, `Radiometry`, `FactoryNuc`, `Enhancer`,
`ImageOps`, `Ddt`, `CalibrationStore`, `Palettes`) was compiled on the JVM and tested
against the repository's real assets during the port:

- `Radiometry` inverse mapping (radiance → milli-Kelvin) is **bit-identical** to
  `radiometry.py` across all 8 LUTs (2000 random probes each), and `calibrate()`
  recovers the same (lut, a, b) with the same rms.
- `FactoryNuc.apply` is **per-pixel identical** to `factory_nuc_grid.py` across grid
  edges, blends and the nearest/no-blend branch (fpa ∈ {8000 … 31000}).
- DDT and calibration files round-trip and interchange with the Python formats.

Not yet exercised on hardware: the USB layer (`MagCamera.kt` — a straight port of the
proven `magcam.py` sequence, including the mandatory EP-0x82 ack-after-every-command
rule) and the Compose UI. First on-device run should verify: streaming starts, FFC,
FPA readout, then NNAPI acceptance of the ESPCN graphs.

## Differences vs the Linux viewer

- On-device SR **training** is not ported (`train_sr.py` stays a desktop workflow);
  the app ships whatever ONNX models are in the repo root. Train on the desktop, drop
  the improved `.onnx(.data)` at the repo root, rebuild.
- Learned flat-field / gain maps persist as little binary blobs in app storage
  (`flatfield.bin`, `gain_nuc.bin`) rather than `.npy`/`.npz`.
- Manual range editing is not exposed (auto-range on by default, off = full 0–65535),
  same as the desktop defaults.
