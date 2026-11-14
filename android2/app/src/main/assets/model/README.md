Drop a trained thermal SR model here to ship it inside the APK:

    thermal_120x160_fp16.param   thermal_120x160_fp16.bin    (portrait, rotate 90/270)
    thermal_160x120_fp16.param   thermal_160x120_fp16.bin    (rotate 0/180)

Produced by sr_train/ (see sr_train/README.md and docs/THERMAL_SR_MODEL.md). The app
works without them — the Thermal SR upscaler simply reports that no model is installed
and falls back to Anime4K. A model in the app's external files dir overrides a bundled
one, which is how to test a new model without rebuilding: in the drawer, **Load SR model
(.zip)…** imports a package — a zip of the `thermal_<w>x<h>_fp16.param/.bin` files, as the
Colab export produces — and **Use built-in** removes it again. `adb push` into
`Android/data/com.magnity.viewer/files/model/` still works too. The app reads from the
`.param` whether a model is 1-channel (the v2 recipe) or 3-channel (this first release).

Bundled now (2026-11-14): **v5** — the same SRVGGNetCompact 64/10 graph (the `.param` is
byte-identical to v4's), new weights: trained re-parameterised (3×3 + 1×1 + identity, folded
back) and distilled from an RRDBNet 64/23 teacher, 60k iterations from the v4 weights, L1 +
FFT spectrum + gradient against GT and teacher, no GAN (`sr_train/PLAN_V5.md`,
`train_thermal_sr_v5.ipynb`). Package `thermal_ncnn_v5.zip`.

v4 → v5, both rebuilt from their ncnn fp16 files (PyTorch CPU): PSNR 30.54 → **30.71 dB** on
the 200 CIDIS val frames (clean 4× area-down — ranks models only); invented peaks / frame
22.4 → **21.3** on 20 of them. Not yet checked on real MAG160 / Tiny frames.

The v4 model it replaces — 2026-09-25: SRVGGNetCompact 64/10, 1-channel (°C sensor view), Colab
recipe v4 `thermal_srvgg_x4_net` net_g_80000 — single stage, L1 + FFT spectrum + gradient
loss, log-uniform sensor noise, no GAN — converted with pnnx fp16. On the 38 held-out real
MAG160 frames it invents ~5.0 hot spots per frame (v3 40k: 5.9) with a worst excess of 52
levels (v3: 129).
