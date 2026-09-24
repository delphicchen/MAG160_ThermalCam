#!/usr/bin/env bash
# TorchScript/ONNX -> ncnn (.param/.bin), fp16 for Vulkan.
#
# Two routes. pnnx is the one to prefer: it reads the traced .pt directly, keeps the
# graph closer to the original and folds better. onnx2ncnn is the fallback when pnnx
# chokes on an op.
#
#   ./scripts/convert_ncnn.sh export/thermal_x4_160x120 160 120 thermal_160x120
#   -> export/thermal_160x120_fp16.param / .bin
#
# Needs: pnnx (pip install pnnx), or ncnn's onnx2ncnn + ncnnoptimize on PATH.
# pnnx writes fp16 weights itself (fp16=1), so ncnnoptimize is only needed for the
# onnx2ncnn route — `pip install ncnn` does not ship it.
set -euo pipefail

BASE="${1:?usage: convert_ncnn.sh <export/basename> [W] [H] [output name]}"
W="${2:-160}"
H="${3:-120}"
NAME="${4:-thermal_x4}"
OUT="$(dirname "$BASE")"
PARAM="$OUT/${NAME}_fp16.param"
BIN="$OUT/${NAME}_fp16.bin"

# export_onnx.py records the input shape (1- or 3-channel models); older exports lack it
if [ -f "${BASE}_inputshape.txt" ]; then
  SHAPE="$(tr -d '[:space:]' < "${BASE}_inputshape.txt")"
else
  SHAPE="[1,3,$H,$W]"
fi

if command -v pnnx >/dev/null 2>&1 && [ -f "$BASE.pt" ]; then
  echo "== pnnx route ($SHAPE) =="
  # inputshape drives shape inference; pnnx emits <base>.ncnn.param / .ncnn.bin
  pnnx "$BASE.pt" inputshape="$SHAPE" device=cpu fp16=1
  mv -f "$BASE.ncnn.bin" "$BIN"
  # pnnx names the blobs in0 / out0; the app and verify_ncnn.py look up data / output
  # (the names the ONNX route carries), so rename them as whole tokens.
  sed 's/\<in0\>/data/g; s/\<out0\>/output/g' "$BASE.ncnn.param" > "$PARAM"
  rm -f "$BASE.ncnn.param"
elif command -v onnx2ncnn >/dev/null 2>&1 && command -v ncnnoptimize >/dev/null 2>&1 \
     && [ -f "$BASE.onnx" ]; then
  echo "== onnx2ncnn route =="
  # simplify first or onnx2ncnn trips over shape ops emitted by the exporter
  python -m onnxsim "$BASE.onnx" "$BASE.sim.onnx"
  onnx2ncnn "$BASE.sim.onnx" "$OUT/${NAME}.param" "$OUT/${NAME}.bin"
  echo "== ncnnoptimize (fp16 storage + arithmetic) =="
  # the trailing 1 selects fp16; use 0 to keep fp32 when chasing an accuracy problem
  ncnnoptimize "$OUT/${NAME}.param" "$OUT/${NAME}.bin" "$PARAM" "$BIN" 1
else
  echo "no usable route: need pnnx + $BASE.pt, or onnx2ncnn + ncnnoptimize + $BASE.onnx" >&2
  exit 1
fi

for blob in data output; do
  grep -qw "$blob" "$PARAM" || { echo "blob '$blob' missing from $PARAM" >&2; exit 1; }
done

echo
echo "wrote:"
ls -la "$PARAM" "$BIN"
echo
echo "next: python scripts/verify_ncnn.py --ref ${BASE}_ref.pt --param $PARAM --bin $BIN"
