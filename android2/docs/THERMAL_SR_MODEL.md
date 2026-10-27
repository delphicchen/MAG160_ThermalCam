# Installing a thermal SR model

The "Thermal SR" upscaler runs a Real-ESRGAN-compact model trained by `sr_train/`.

## Shipping it inside the APK (the normal route once a model is good)

Copy the four files into `app/src/main/assets/model/` and rebuild:

    app/src/main/assets/model/thermal_120x160_fp16.param
    app/src/main/assets/model/thermal_120x160_fp16.bin
    app/src/main/assets/model/thermal_160x120_fp16.param
    app/src/main/assets/model/thermal_160x120_fp16.bin

`.param`/`.bin` are in `noCompress`, so ncnn maps them straight out of the APK. The repo
ships without them and the app just falls back to Anime4K.

## Testing a model without rebuilding

    adb push thermal_120x160_fp16.param /sdcard/Android/data/com.magnity.viewer/files/model/
    adb push thermal_120x160_fp16.bin   /sdcard/Android/data/com.magnity.viewer/files/model/

The drawer prints the exact path on the device when no model is present. A file here
**overrides a bundled asset**, so this is how you compare a new checkpoint against the
shipped one.

## Naming

One model per input size, because the exported graph has a static shape:

| orientation | frame | files |
|---|---|---|
| portrait (rotate 90/270, the default) | 120×160 | `thermal_120x160_fp16.{param,bin}` |
| unrotated (rotate 0/180) | 160×120 | `thermal_160x120_fp16.{param,bin}` |

Selecting "Thermal SR" with no matching file falls back to Anime4K and says so.

## Model sizes

The drawer's "Thermal SR model size" picks **Full / Medium / Lite** — SRVGGNetCompact 64/10,
48/10 and 32/10 (≈ 100 / 57 / 26 % of the compute per frame: a lighter one runs cooler and
keeps up on a weaker phone). Each size has its own dir: `model/` (full — the bundled assets
and the paths above), `model-medium/`, `model-lite/`, under both `assets/` and the external
files dir. **Load SR model** reads a package's width from its `.param` (the first
Convolution's `0=`) and files it under that size, so the zip needs no special names; a size
with no model is greyed out, and a picked size that is missing falls back to the full model.
The graph has no fixed shape, so a model exported at one frame size also runs on the others.

Bundled: full (`assets/model/`), lite (`assets/model-lite/`, from 2.8-alpha4) and medium
(`assets/model-medium/`, from 2.8) — one `thermal_160x120_fp16` pair per size for every
camera; each README has the recipe and a comparison.

## Expected blob names

`data` in, `output` out — what `sr_train/scripts/export_onnx.py` produces. A model
converted by other means must use the same names or `nativeRun` fails with "input blob
'data' not found" in logcat.

## Pipeline position

Identical to the other upscalers: the scalar temperature field is normalised to the
display range, upscaled 4×, then palette-mapped — all inside the native call, so only two
arrays cross JNI per frame. Measurement still reads the native 160×120 grid; the upscaler
only ever affects what you see.
