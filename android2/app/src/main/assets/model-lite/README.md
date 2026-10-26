The bundled **lite** thermal SR model — "Thermal SR model size → Lite" in the drawer.

    thermal_160x120_fp16.param   thermal_160x120_fp16.bin

One pair serves every camera: the exported graph has no fixed input shape (the 256×192 and
192×256 exports of this model are byte-identical), and the app falls back to the 160×120
file when no file matches the frame size. The size is read from the `.param` (first
Convolution `0=32`), not from this folder's name. A lite model imported in the drawer
(`files/model-lite/`) overrides this one; see `../model/README.md` and
`docs/THERMAL_SR_MODEL.md`.

Bundled now (2026-10-26): SRVGGNetCompact **32/10**, 1-channel (°C sensor view), Colab
`train_thermal_sr_lite.ipynb` — recipe v4 (`v4-l1-fft1-grad05-noiselog-lite32`): warm start
= the bundled v4 64/10 pruned to 32 channels (largest-L1 filters), then 80k iterations of
L1 + FFT spectrum + gradient loss, log-uniform sensor noise, no GAN; converted with pnnx
fp16. Package `thermal_ncnn_L1FFT_lite32.zip`, files sha256 `8cf0374f…` (.param) /
`59288cfb…` (.bin).

Checked against the bundled full 64/10 on the 200 held-out CIDIS thermal val frames
(160×112 → 640×448, ncnn CPU, the app's 0..1 one-channel input):

| | full 64/10 | lite 32/10 |
|---|---|---|
| PSNR vs ground truth | 29.79 dB | 29.37 dB |
| lite vs full output | — | 37.0 dB |
| energy error (8-bit levels) | 3.64 | 3.96 |
| invented peaks / frame (clean · σ 0.01 input) | 32.7 · 22.1 | 35.4 · 22.8 |
| CPU net time (4 threads, x86) | 48 ms | 27 ms |

So: a slightly softer copy of the full model with the same hallucination profile. On a
phone GPU the speed-up should be larger than on CPU (¼ of the convolution work); measure it
with the Debug overlay's `net` time. Not yet checked on real MAG160 / Tiny2C frames.
