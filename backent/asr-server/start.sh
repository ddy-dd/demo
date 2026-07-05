#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

# 创建虚拟环境（如不存在）
if [ ! -d .venv ]; then
    python3 -m venv .venv
fi

source .venv/bin/activate
pip install -q -r requirements.txt

MODEL="${ASR_MODEL:-mlx-community/whisper-large-v3-turbo}"
PORT="${ASR_PORT:-8001}"

echo "🚀 ASR Service starting on port ${PORT} (model: ${MODEL})"
uvicorn app:app --host 0.0.0.0 --port "${PORT}"
