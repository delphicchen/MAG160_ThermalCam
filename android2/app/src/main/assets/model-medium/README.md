The bundled **medium** thermal SR model — "Thermal SR model size → Medium" in the drawer.

    thermal_160x120_fp16.param   thermal_160x120_fp16.bin

One pair serves every camera: the exported graph has no fixed input shape (the 256×192 and
192×256 exports of this model are byte-identical), and the app falls back to the 160×120
file when no file matches the frame size. The size is read from the `.param` (first
Convolution `0=48`), not from this folder's name. A medium model imported in the drawer
(`files/model-medium/`) overrides this one; see `../model/README.md` and
`docs/THERMAL_SR_MODEL.md`.

Bundled now (2026-11-14): **v5** — the same SRVGGNetCompact 48/10 graph (the `.param` is
byte-identical to v4's), new weights: trained re-parameterised (3×3 + 1×1 + identity, folded
back) and distilled from an RRDBNet 64/23 teacher, 60k iterations from the v4 weights, L1 +
FFT spectrum + gradient against GT and teacher, no GAN (`sr_train/PLAN_V5.md`,
`train_thermal_sr_v5.ipynb`). Package `thermal_ncnn_v5_medium48.zip`.

v4 → v5, both rebuilt from their ncnn fp16 files (PyTorch CPU): PSNR 30.38 → **30.49 dB** on
the 200 CIDIS val frames (clean 4× area-down — ranks models only); invented peaks / frame
28.4 → **24.0** on 20 of them. Not yet checked on real MAG160 / Tiny frames.

The v4 model it replaces — 2026-10-27: SRVGGNetCompact **48/10**, 1-channel (°C sensor view), Colab
`train_thermal_sr_medium.ipynb` — recipe v4 (`v4-l1-fft1-grad05-noiselog-medium48`): warm
start = the bundled v4 64/10 pruned to 48 channels (largest-L1 filters), then 80k iterations
of L1 + FFT spectrum + gradient loss, log-uniform sensor noise, no GAN; converted with pnnx
fp16. Package `thermal_ncnn_L1FFT_medium48.zip`, files sha256 `6fd629a5…` (.param) /
`5a81c621…` (.bin).

Checked against the bundled full and lite on the 200 held-out CIDIS thermal val frames
(160×112 → 640×448, ncnn CPU, the app's 0..1 one-channel input):

| | full 64/10 | medium 48/10 | lite 32/10 |
|---|---|---|---|
| PSNR vs ground truth | 30.10 dB | 29.93 dB | 29.65 dB |
| output vs full | — | 39.2 dB | 37.0 dB |
| energy error (8-bit levels) | 3.01 | 3.09 | 3.33 |
| invented peaks / frame (clean · σ 0.01 input) | 32.8 · 22.0 | 40.2 · 26.5 | 35.8 · 22.7 |
| CPU net time (4 threads, x86, relative) | 1.00 | 0.68 | 0.36 |

So: between full and lite in sharpness and speed, but with ~20 % more invented local peaks
than either — small, faint ones (the check counts peaks 0.03 above bicubic), worth a look on
real frames before recommending it over full. Measure the phone speed-up with the Debug
overlay's `net` time. Not yet checked on real MAG160 / Tiny2C frames.
