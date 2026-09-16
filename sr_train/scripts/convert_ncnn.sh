#!/usr/bin/env bash
# TorchScript/ONNX -> ncnn (.param/.bin), fp16 for Vulkan.
#
# Two routes. pnnx is the one to prefer: it reads the traced .pt directly, keeps the
# graph closer to the original and folds better. onnx2ncnn is the fallback when pnnx
# chokes on an op.
#
#   ./scripts/convert_ncnn.sh export/thermal_x4 160 120
#
# Needs: pnnx (https://github.com/pnnx/pnnx/releases) and/or ncnn's onnx2ncnn +
# ncnnoptimize on PATH.
set -euo pipefail

BASE="${1:?usage: convert_ncnn.sh <export/basename> [W] [H]}"
W="${2:-160}"
H="${3:-120}"
OUT="$(dirname "$BASE")"

if command -v pnnx >/dev/null 2>&1 && [ -f "$BASE.pt" ]; then
  echo "== pnnx route =="
  # inputshape drives shape inference; pnnx emits .param/.bin next to the input
  pnnx "$BASE.pt" inputshape="[1,3,$H,$W]" device=cpu
  # pnnx names its output <base>.ncnn.param / .ncnn.bin
  mv -f "$BASE.ncnn.param" "$OUT/thermal_x4.param"
  mv -f "$BASE.ncnn.bin"   "$OUT/thermal_x4.bin"
elif command -v onnx2ncnn >/dev/null 2>&1 && [ -f "$BASE.onnx" ]; then
  echo "== onnx2ncnn route =="
  # simplify first or onnx2ncnn trips over shape ops emitted by the exporter
  python -m onnxsim "$BASE.onnx" "$BASE.sim.onnx"
  onnx2ncnn "$BASE.sim.onnx" "$OUT/thermal_x4.param" "$OUT/thermal_x4.bin"
else
  echo "neither pnnx nor onnx2ncnn found (or inputs missing)" >&2
  exit 1
fi

echo "== ncnnoptimize (fp16 storage + arithmetic) =="
# the trailing 1 selects fp16; use 0 to keep fp32 when chasing an accuracy problem
ncnnoptimize "$OUT/thermal_x4.param" "$OUT/thermal_x4.bin" \
             "$OUT/thermal_x4_fp16.param" "$OUT/thermal_x4_fp16.bin" 1

echo
echo "wrote:"
ls -la "$OUT"/thermal_x4*.param "$OUT"/thermal_x4*.bin
echo
echo "next: python scripts/verify_ncnn.py --ref ${BASE}_ref.pt --param $OUT/thermal_x4_fp16.param --bin $OUT/thermal_x4_fp16.bin"
