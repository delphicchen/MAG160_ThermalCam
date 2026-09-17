# Thermal Real-ESRGAN — training pipeline (160×120 → 640×480)

Fine-tunes a compact Real-ESRGAN for this camera and converts it to ncnn/Vulkan fp16.

Nothing here has been run yet — there is no GPU and no dataset on the machine these
files were written on. Treat the iteration counts and timings as starting points, not
measurements.

---

## Read this before you start: two corrections to the original plan

**1. "compact RRDBNet, nf≈16, 2–4 M parameters" is not self-consistent, and pretrained
init will not transfer.** Run `python scripts/count_params.py`:

| model | params | GMAC @160×120 |
|---|---|---|
| SRVGGNetCompact 64/32 (`realesr-general-x4v3`, as published) | 1.21 M | ~45 |
| SRVGGNetCompact 64/16 (this recipe) | 0.62 M | ~22 |
| SRVGGNetCompact 64/8 | 0.31 M | ~11 |
| RRDBNet 32/12 | ~2.5 M | ~130 |
| RRDBNet 16/6 | ~0.35 M | ~18 |
| RRDBNet 64/23 (`RealESRGAN_x4plus`) | 16.7 M | ~830 |

`nf=16` with few blocks gives ~0.35 M, not 2–4 M; reaching 2–4 M needs `nf=32, nb=12`,
which costs ~6× the compute of the compact route. And **loading pretrained weights into
any of those shapes is a no-op**: `RealESRGAN_x4plus` is RRDBNet 64/23, so every conv has
a different shape and gets skipped, and `realesr-general-x4v3` is not an RRDBNet at all —
it is an `SRVGGNetCompact`. "Allow skip mismatch" would silently leave you training from
scratch.

So the main recipe uses **SRVGGNetCompact**, which is what "compact Real-ESRGAN" actually
refers to: it is 6× cheaper than the RRDBNet route and it is what
`Real-ESRGAN-ncnn-vulkan` already ships, so the conversion path is proven. Note the
published `realesr-general-x4v3` is **num_conv=32** (1.21 M, ~45 GMAC); this recipe halves
it to 16 for the frame budget, and `scripts/truncate_pretrained.py` remaps the weights
onto the shorter network — including the output conv, which a plain load would leave
random. Run it once before stage 1 (the Colab notebook does this for you). `options/alt_rrdb_compact_x4_gan.yml` is the
RRDBNet version as originally specified, with its caveats written at the top — use it if
you want to compare.

**2. The app has no ncnn runtime yet.** Display upscaling currently runs the Anime4K
GLSL shader through GLES 3.0 (`android2/.../pipeline/Anime4kGpu.kt`). Shipping this model
means adding ncnn + Vulkan to the app and a second upscaler backend — a separate job from
everything below, which ends at a verified `.param`/`.bin`.

---

## Layout

```
sr_train/
├── options/
│   ├── 01_thermal_srvgg_x4_net.yml      stage 1, L1 only, 10k iter
│   ├── 02_thermal_srvgg_x4_gan.yml      stage 2, + VGG + GAN, 100k iter
│   └── alt_rrdb_compact_x4_gan.yml      RRDBNet 32/12 variant
├── thermal_arch/thermal_degradation.py  FPN + low-contrast on top of Real-ESRGAN's pipeline
└── scripts/
    ├── prepare_thermal_dataset.py       public datasets → 480×480 HR crops + meta_info
    ├── estimate_fpn_stats.py            measure your sensor's FPN → yml amplitudes
    ├── count_params.py                  params / MACs per candidate
    ├── export_onnx.py                   checkpoint → TorchScript + ONNX + reference output
    ├── convert_ncnn.sh                  pnnx (or onnx2ncnn) → .param/.bin → fp16
    ├── verify_ncnn.py                   PyTorch vs ncnn, with tolerance rationale
    └── hallucination_check.py           fake-hot-spot / energy-preservation validation
```

Work inside a clone of `xinntao/Real-ESRGAN`; these files sit beside it and are
referenced by path.

## Environment

```sh
git clone https://github.com/xinntao/Real-ESRGAN && cd Real-ESRGAN
python -m venv .venv && . .venv/bin/activate
pip install torch torchvision --index-url https://download.pytorch.org/whl/cu121
pip install basicsr facexlib gfpgan opencv-python scipy tb-nightly onnx onnxsim ncnn
python setup.py develop
cp -r ../sr_train/{options,thermal_arch,scripts} .
```

Register the custom models by adding this line near the top of `realesrgan/train.py`
(BasicSR only auto-imports its own package):

```python
import os.path as osp, sys
sys.path.insert(0, osp.dirname(osp.dirname(osp.abspath(__file__))))   # repo root
import thermal_arch.thermal_degradation  # noqa: F401  — registers the two model types
```

The `sys.path` line matters: `python realesrgan/train.py` puts `realesrgan/` first on the
path, where Real-ESRGAN's own `archs` package lives — a top-level directory called `archs`
would be shadowed by it, which is why ours is `thermal_arch`.

If `basicsr` fails on `functional_tensor` (torchvision ≥ 0.17), patch the one import in
`basicsr/data/degradations.py` to `torchvision.transforms.functional`.

Pretrained weights:

```sh
wget -P experiments/pretrained_models \
  https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesr-general-x4v3.pth \
  https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.2.3/RealESRGAN_x4plus_netD.pth
```

### VRAM

Measured shapes, `gt_size: 256`, degradation runs on GPU:

| config | batch | peak VRAM |
|---|---|---|
| stage 1, SRVGGNetCompact | 12 | ~6 GB |
| stage 2, + UNet discriminator + VGG19 | 12 | ~11 GB |
| stage 2, batch 8 | 8 | ~8 GB |
| alt RRDBNet 32/12, stage 2 | 8 | ~14 GB |

12 GB is comfortable for the main recipe; on 8 GB drop `batch_size_per_gpu` to 8 and
`queue_size` to 120. The degradation queue also holds `queue_size` HR/LR pairs in VRAM —
that is the knob to turn first if you are close.

## Data

FLIR's own ADAS download and the PBVS challenge data both require registration. These
Hugging Face mirrors do not — each was verified public and ungated:

| source | images | size | note |
|---|---|---|---|
| `jsonhash/FLIR_aligned` | ~5.1k thermal @ **640×512** | 1.4 GB zip | FLIR ADAS aligned pairs; take only `align/JPEGImages/*_PreviewData.jpeg` |
| `LibreYOLO/flir-camera-objects` | 13.6k @ 640×640 | ~1 GB | the same FLIR ADAS data via Roboflow, stretched to square |
| `Kiuyha/hit-uav-thermal-human-detection` | 4.9k @ 640×640 | ~300 MB | drone thermal, different viewpoints |
| your MAG160Core captures | **validation only** | — | 160×120 is the LR side; no 4× ground truth exists for it |

```sh
python - <<'PY'
from huggingface_hub import hf_hub_download
print(hf_hub_download('jsonhash/FLIR_aligned', 'aligned.zip', repo_type='dataset'))
PY
```

Registration-only sources (FLIR ADAS v2 direct, KAIST Multispectral, PBVS TISR) are still
worth adding if you have them — more variety is the single biggest lever on this model.

```
datasets/thermal_raw/{flir_adas_v2,kaist,pbvs_tisr}/…    # any depth
datasets/val_mag160/*.png                                 # 20 held-out real frames
```

```sh
python scripts/prepare_thermal_dataset.py --raw datasets/thermal_raw --crop 480 --stride 160
```

A 640×512 source yields exactly one 480px tile at stride 360, so the stride is shortened
to overlap them; sub-directories and loose files under `--raw` are both read.

Percentile-stretches each image the way the viewer maps °C to the palette, cuts 480×480
crops, drops flat ones (`--min-std`), writes `datasets/thermal_hr/` and
`datasets/meta_info/thermal_hr.txt`. Aim for ≥ 20k crops. `use_rot` stays off in the
ymls: thermal scenes have a gravity direction (sky cold on top, ground warm), and
rotating teaches orientations the camera will not see.

## Degradation — what changed from `finetune_realesrgan_x4plus.yml`, and why

| change | reason |
|---|---|
| `blur_sigma` 0.2–3.0 → **0.2–1.5** | the fixed 6.5 mm lens is always in focus and the sensor is the resolution limit; heavy blur is a degradation this camera never produces, and training on it costs sharpness |
| `sinc_prob`, `final_sinc_prob` → **0** | sinc ringing models a photo resampling filter the thermal path does not have, and ringing halos beside a hot edge read as fake temperature |
| `gaussian_noise_prob` 0.5 → **0.3**, `poisson_scale_range` → **0.05–3.0** | microbolometer noise is dominated by signal-dependent shot/thermal noise; Poisson should lead |
| `gray_noise_prob` → **0.6** | the sensor is single-channel, so its noise is identical across the replicated R/G/B |
| `resize` stages | kept — the SDK's own scaling and our rotation do resample the frame |
| `jpeg_range` | kept but mild (50–95); the live path has no JPEG, this is only robustness |
| **+ FPN** (`fpn_*`) | new. Per-column, per-row, 2-D and gain fixed-pattern noise. Without it the network sharpens residual NUC stripes into hard vertical lines — the single most visible artefact on this sensor |
| **+ low contrast** (`contrast_range`) | new. Indoors the whole frame can sit within 3 °C; training only on full-contrast crops teaches the network to trust edges far stronger than it will ever see |

Set the FPN amplitudes from your own sensor rather than the defaults:

```sh
# capture ~300 frames of a static wall with no FFC in between, saved as (N,H,W) .npy
python scripts/estimate_fpn_stats.py captures/wall_300.npy
```

## Training

```sh
# stage 1 — L1 only, ~10k iter: let the net meet the thermal degradation first
python realesrgan/train.py -opt options/01_thermal_srvgg_x4_net.yml --auto_resume

# stage 2 — + perceptual + GAN, from stage 1's EMA weights
python realesrgan/train.py -opt options/02_thermal_srvgg_x4_gan.yml --auto_resume
```

Schedule: stage 1 lr 2e-4, halved at 6k, stop at 10k. Stage 2 lr 1e-4 for G and D,
halved at 50k and 80k, stop at 100k. GAN weight is 5e-2 and perceptual 0.5 — both lower
than stock Real-ESRGAN, because a thermal frame has no texture to invent and the GAN term
is exactly what produces plausible-but-absent detail.

Validation is visual and adversarial-free: every 5k checkpoint, run

```sh
python scripts/hallucination_check.py \
    --ckpt experiments/thermal_srvgg_x4_gan/models/net_g_50000.pth \
    --lr datasets/val_mag160 --out results/val_50k
```

It writes a 4-panel comparison per frame (input / bicubic / model / marked invented
peaks) and a CSV with three numbers per frame:

- **energy error** — box-average the 4× output back to 160×120 and compare with the
  input. A faithful upscaler reproduces it to well under one 8-bit level. Drift means the
  network is re-lighting the scene.
- **invented peaks** — local maxima in the output that exceed bicubic by `--delta` and
  have no corresponding maximum in the input. This is the fake-hot-spot count; watch it
  across checkpoints and stop when it starts climbing.
- **range inflation** — how far output min/max exceed the input's.

There is no ground truth at 4× for real frames, so these are comparisons *between
checkpoints*, not absolute scores. Pick the checkpoint where perceived sharpness stops
improving but invented peaks have not yet risen — usually well before the last iteration.

## Convert and verify

```sh
python scripts/export_onnx.py \
    --ckpt experiments/thermal_srvgg_x4_gan/models/net_g_100000.pth \
    --arch srvgg --size 160x120 --out export/thermal_x4_160x120

# pnnx → fp16 .param/.bin with the data/output blob names the app looks up
./scripts/convert_ncnn.sh export/thermal_x4_160x120 160 120 thermal_160x120

python scripts/verify_ncnn.py --ref export/thermal_x4_160x120_ref.pt \
    --param export/thermal_160x120_fp16.param --bin export/thermal_160x120_fp16.bin
python scripts/verify_ncnn.py --ref export/thermal_x4_160x120_ref.pt \
    --param export/thermal_160x120_fp16.param --bin export/thermal_160x120_fp16.bin --vulkan
```

Input shape is fixed at 160×120 on purpose: the frame size never varies, and a static
graph converts most cleanly. Export a second model at 120×160 if you upscale after
rotation. No custom ops are involved — conv, prelu, pixelshuffle and a bilinear skip are
all native to ncnn.

Tolerances (the reasoning is repeated at the top of `verify_ncnn.py`): fp32 vs PyTorch
should agree to < 1e-4 — anything more is a converted op misbehaving, not precision.
fp16 vs PyTorch should land under ~6e-3 max (≈1.5 of an 8-bit level) and 1e-3 mean: fp16
keeps ~10 mantissa bits, each accumulation rounds at ~5e-4 relative, and the error grows
with depth. Vulkan may use fp16 storage with fp32 accumulate depending on the GPU, so the
same file can be slightly more accurate on one device than another.

## Time estimates (single RTX 3090-class GPU)

| step | wall time |
|---|---|
| download FLIR ADAS v2 + KAIST + PBVS | 2–6 h, mostly bandwidth (~60 GB) |
| `prepare_thermal_dataset.py` over ~40k source images | 20–40 min |
| `estimate_fpn_stats.py` | seconds, once you have the capture |
| stage 1, 10k iter @ batch 12 | ~1.5 h |
| stage 2, 100k iter @ batch 12 | ~24–30 h (the VGG and the discriminator dominate) |
| `alt` RRDBNet from scratch, 400k iter | ~6–8 days — the reason it is the alternative |
| export + convert | < 5 min |
| verify + hallucination check on 20 frames | < 2 min |

A useful first pass: stage 1 plus 20k iterations of stage 2 (~6 h) is enough to see
whether the FPN handling works and whether invented peaks stay near zero. Only commit to
the full 100k after that.
