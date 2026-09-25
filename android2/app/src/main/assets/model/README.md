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

Bundled now (2026-09-17): SRVGGNetCompact 64/16, Colab run `thermal_srvgg_x4_gan`
net_g_40000 (stage 1: 8k L1 iterations, stage 2: 40k perceptual + GAN), converted with
pnnx fp16. ncnn vs PyTorch on a thermal-like frame: max 0.49 / mean 0.05 of an 8-bit level.
