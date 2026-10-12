# depth_export — Depth Anything V2 Metric Small → ncnn

Converts the metric monocular-depth model used by MagViewer's **depth** distance source
(see `android2/PLAN_DEPTH.md`) into the fp16 ncnn package the app imports.

```bash
conda create -n depth python=3.11
conda activate depth
pip install --index-url https://download.pytorch.org/whl/cpu torch torchvision
pip install pnnx ncnn numpy opencv-python-headless huggingface_hub

python depth_export/export_depth_ncnn.py            # indoor, Hypersim (max 20 m)
python depth_export/export_depth_ncnn.py --outdoor  # outdoor, VKITTI (max 80 m)
```

The script clones the official repo into `work/` (for the model definition), downloads
the checkpoint from Hugging Face, traces one fixed-size graph per orientation
(266×196 landscape, 196×266 portrait), converts it with pnnx, checks ncnn against torch
on the same input, and writes `out/depth_metric_ncnn.zip`.

- Input `data`: 1×3×H×W RGB, ImageNet-normalized (mean 0.485/0.456/0.406,
  std 0.229/0.224/0.225). Output `output`: 1×1×H×W depth in metres.
- `work/` and `out/` are not tracked.

## Licence

Depth Anything V2 (<https://github.com/DepthAnything/Depth-Anything-V2>): the repo states
"Depth-Anything-V2-Small model is under the Apache-2.0 license"; the Base/Large/Giant
models are CC-BY-NC-4.0 and are not used. The metric Small checkpoints are listed under
the Small model, but their Hugging Face pages carry no licence tag, so confirm that
before redistributing the converted model.
