#!/usr/bin/env bash
# Builds the Chatterbox model pack that ships inside the APK.
#
#   chatterbox/build_model_pack.sh [output dir]
#
# The pack is two GGUF files produced from the upstream (MIT) Chatterbox Turbo
# checkpoints by the converters in the chatterbox.cpp port. Nothing here runs on the
# device: this is a host-side build step, like image/build_guest_image.sh.
#
# Provenance of every input, so a rebuild is reproducible:
#
#   port        github.com/gianni-cor/chatterbox.cpp  (MIT)  — pinned commit below
#   ggml        github.com/ggml-org/ggml              (MIT)  — pinned by the port's scripts/setup-ggml.sh
#   weights     huggingface.co/ResembleAI/chatterbox-turbo    (MIT)
#   converter   scripts/convert-t3-turbo-to-gguf.py, scripts/convert-s3gen-to-gguf.py
#
# The converter side needs Python + torch once (setup only); the C++ engine has no
# Python or PyTorch dependency at runtime.
set -euo pipefail

OUT_DIR="${1:-app/src/main/assets/chatterbox}"
WORK="${CHATTERBOX_WORK:-$HOME/.cache/chatterbox-work}"
PORT_COMMIT="${CHATTERBOX_PORT_COMMIT:-ddca05fb69c2910b0d7b5eae420d360ed98c067b}"
T3_QUANT="${T3_QUANT:-q8_0}"
# S3Gen stays at f16: its q8_0/q4_0 conversions produce a full-length but entirely silent clip
# (rms 0.0000 / peak 0.000 vs 0.0365 / 0.384 at f16), so a block-quantized vocoder GGUF must never
# ship. That is 1.07 GB instead of 830 MB — correctness over size.
S3GEN_QUANT="${S3GEN_QUANT:-f16}"

PORT="$WORK/chatterbox.cpp"
PY="$WORK/venv/bin/python"
HF_HOME="${HF_HOME:-$WORK/hf}"
export HF_HOME

[ -d "$PORT/src" ] || { echo "error: no chatterbox.cpp checkout at $PORT (clone it first)" >&2; exit 1; }
[ -x "$PY" ]      || { echo "error: no converter venv at $PY" >&2; exit 1; }

mkdir -p "$OUT_DIR" "$PORT/models"

CKPT=$(ls -d "$HF_HOME"/hub/models--ResembleAI--chatterbox-turbo/snapshots/*/ 2>/dev/null | head -1)
if [ -z "$CKPT" ]; then
    echo "--- downloading ResembleAI/chatterbox-turbo ---"
    CKPT=$("$PY" -c "from huggingface_hub import snapshot_download;print(snapshot_download('ResembleAI/chatterbox-turbo', allow_patterns=['*.safetensors','*.json','*.txt','*.pt']))" | tail -1)
fi
echo "checkpoints: $CKPT"

echo "--- T3 Turbo ($T3_QUANT) ---"
"$PY" "$PORT/scripts/convert-t3-turbo-to-gguf.py" --ckpt-dir "$CKPT" \
    --out "$PORT/models/cbx-t3-turbo-$T3_QUANT.gguf" --quant "$T3_QUANT"

echo "--- S3Gen Turbo ($S3GEN_QUANT) ---"
"$PY" "$PORT/scripts/convert-s3gen-to-gguf.py" --variant turbo --ckpt-dir "$CKPT" \
    --out "$PORT/models/cbx-s3gen-turbo-$S3GEN_QUANT.gguf" --quant "$S3GEN_QUANT"

cp "$PORT/models/cbx-t3-turbo-$T3_QUANT.gguf"   "$OUT_DIR/cbx-t3-turbo-$T3_QUANT.gguf"
cp "$PORT/models/cbx-s3gen-turbo-$S3GEN_QUANT.gguf" "$OUT_DIR/cbx-s3gen-turbo-$S3GEN_QUANT.gguf"

echo "--- pack ---"
ls -la "$OUT_DIR"
echo "provenance: port=$PORT_COMMIT t3=$T3_QUANT s3gen=$S3GEN_QUANT weights=ResembleAI/chatterbox-turbo"
