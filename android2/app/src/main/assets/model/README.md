Drop a trained thermal SR model here to ship it inside the APK:

    thermal_120x160_fp16.param   thermal_120x160_fp16.bin    (portrait, rotate 90/270)
    thermal_160x120_fp16.param   thermal_160x120_fp16.bin    (rotate 0/180)

Produced by sr_train/ (see sr_train/README.md and docs/THERMAL_SR_MODEL.md). The app
works without them — the Thermal SR upscaler simply reports that no model is installed
and falls back to Anime4K. A file pushed into the app's external files dir overrides a
bundled one, which is how to test a new model without rebuilding.
