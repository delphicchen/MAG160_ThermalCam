# Magnity MAG-Mx Thermal Camera — Linux app

A native Linux viewer for the Magnity 833c USB thermal camera (the camera whose only
official software is the Android `MAG-Mx.apk`). Built by reverse-engineering the USB
protocol from the APK's native libs + live probing — see **`PROTOCOL.md`**.

## What works
- Live thermal stream **160×120 @ 15 fps** over USB (pure Python / pyusb).
- **FFC (flat-field / shutter) correction** → clean image, no fixed-pattern noise.
- PySide6 viewer: color palettes, auto/manual range, hot/cold spot markers, cursor
  readout, snapshot, on-demand FFC.
- **Tier-1 image enhancement** (`enhance.py`, toggleable in the viewer):
  - **Flat-field / shading correction** — the shutter FFC can't remove the lens-shading
    vignette (bright edges / dark centre) or residual column stripes, because the shutter
    sits behind the lens. Point at a uniform-temperature surface and click **“Flat-field
    cal”**: it builds a per-pixel shading map and subtracts it (here: full-frame
    non-uniformity dropped **227 → 21 counts, ~11×**). Saved to `flatfield.npy`.
  - **Bad-pixel correction** — the original app masks the sensor's bad/blinking pixels;
    we rebuild that map automatically (learned on FFC, ~30 pixels here) and replace them
    with the neighbourhood median, plus a per-frame impulse catcher.
  - **Temporal denoise** — motion-adaptive frame averaging (halves frame-to-frame noise
    on static scenes, no ghosting on motion).
  - **Spatial denoise** — edge-preserving bilateral filter.
  - **Super-res ×2 (multi-frame)** — registers recent frames with sub-pixel phase
    correlation and shift-and-adds them onto a 2× grid (natural hand jitter supplies the
    sub-pixel diversity; degrades gracefully to cubic upscale when static). Display-only.
  - Measurement vs display are separated: °C / min / max use the value-safe layer
    (bad-pixel + temporal only); spatial smoothing / super-res are display-only.
- **Factory NUC (radiometric)** — the camera's own per-pixel, multi-segment
  piecewise-linear non-uniformity correction, reversed out of `mag_cali.bin` by running
  the firmware build chain under ARM emulation (the numpy apply is **bit-exact** to the
  SDK). Tables are pre-stored over a grid of FPA (sensor) temperatures
  (`factory_nuc_grid.npz`, built with `recon/build_nuc_grid.py`); the viewer reads the
  **live FPA temp** (frame tail+8) and interpolates the grid, then applies the firmware's
  exact per-pixel correction. Toggle **“Factory NUC (radiometric)”** — it runs on the
  uncorrected raw using the shutter dark frame as the offset reference (auto-captured on
  enable) plus the factory per-pixel gain + piecewise curve, replacing the flat-field /
  gain stages. On a near-uniform scene it cut column fixed-pattern noise ~**4×** vs the
  plain FFC. See `recon/EMULATION_NUC.md` for the full reverse-engineering write-up.
- **View options:** **Auto-FFC** (timer-based, default 60 s — the microbolometer drifts
  so the shutter reference needs periodic refreshing; this re-FFCs for you), and
  **Mirror (left-right)** flip (applied at input so display, cursor and measurement stay
  consistent).

- **Temperature (°C)** via `radiometry.py`: the full raw→temperature formula was
  reverse-engineered from the SDK (`radiance = a*raw+b → Planck LUT → °C`); the camera's
  built-in Planck radiance curves were extracted (`recon/planck_luts.npy`). The two
  per-camera coefficients are recovered from a few **known-temperature reference points**
  (the proprietary on-camera coefficient blob is expanded by an ARM-only parser we can't
  run on x86, so we anchor with references instead — see `PROTOCOL.md`).

### Calibrating temperature
1. Run `viewer.py`, let it warm up, hit **FFC** once.
2. Point the cursor at an object whose temperature you know (e.g. ice water ≈ 0 °C, a cup
   of warm water you measured, your hand via a clinical/IR thermometer ≈ 33 °C).
3. Click **“+ Add cal point”** and enter the °C. Repeat for ≥2 points spread across the
   range you care about (3–4 points = better).
4. After 2 points the readout switches to °C automatically. Calibration is saved to
   `calibration.json` and reloaded next run.
   - Re-running **FFC** shifts the raw values, so calibrate after the FFC you'll use; if
     you re-FFC and readings drift, just add a fresh point or clear & redo.

## Not done yet
- Box / line ROI temperature tools (only point + hot/cold spot so far).
- Refining the milli-Kelvin unit assumption — more reference points across a wide range
  will tighten absolute accuracy.

## Setup
```bash
pip install pyusb numpy pillow PySide6 matplotlib   # (most already present)
# one-time USB permission (lets the 'plugdev' group talk to the camera):
sudo cp 99-magnity-thermal.rules /etc/udev/rules.d/
sudo udevadm control --reload-rules && sudo udevadm trigger
# then replug the camera
```

## Run
```bash
python3 viewer.py          # the GUI
python3 magcam.py          # headless self-test: grabs a frame, writes /tmp/mag_raw.png + /tmp/mag_ffc.png
```

## Files
- `viewer.py`   — PySide6 live viewer (entry point).
- `magcam.py`   — `MagCamera` driver: open / stream / FFC / get_frame.
- `PROTOCOL.md` — full reverse-engineered USB protocol + remaining work.
- `recon/`      — reverse-engineering scripts (`disasm.py`, `probe.py`, `grab3.py`, …).
- `99-magnity-thermal.rules` — udev rule for non-root USB access.

## Notes
- The camera re-enumerates (new USB address) between runs / on protocol errors; this is
  normal and the udev rule matches by vendor id so access keeps working.
- **Every command must be followed by a read of its EP-0x82 ack** or the firmware wedges
  — this is the key protocol gotcha (handled in `magcam.py`).
