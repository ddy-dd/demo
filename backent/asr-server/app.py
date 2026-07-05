"""
ASR 微服务 — 基于 mlx-whisper 的语音转文字服务

启动方式：
    cd backent/asr-server
    pip install -r requirements.txt
    uvicorn app:app --host 0.0.0.0 --port 8001

环境变量：
    ASR_MODEL  模型名称（默认 mlx-community/whisper-large-v3-turbo）
    ASR_PORT   服务端口（默认 8001）
"""
import os
os.environ["HF_ENDPOINT"] = "https://hf-mirror.com"
import io
import time
import logging
import asyncio
import struct
import subprocess
from contextlib import asynccontextmanager
from fastapi import FastAPI, UploadFile, File, HTTPException
from fastapi.responses import JSONResponse

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
)
log = logging.getLogger(__name__)


# ── 音频解码 ────────────────────────────────────────────────


def decode_audio(audio_bytes: bytes, sample_rate: int = 16000):
    """
    用 ffmpeg 将任意格式音频解码为 16kHz mono float32 PCM。

    Args:
        audio_bytes: 原始音频文件字节（webm / mp3 / wav / m4a 等）
        sample_rate: 目标采样率（Whisper 要求 16kHz）

    Returns:
        numpy float32 数组，shape (samples,)

    Raises:
        RuntimeError: ffmpeg 不可用或解码失败
    """
    import numpy as np

    cmd = [
        "ffmpeg", "-nostdin", "-loglevel", "error",
        "-i", "pipe:0",
        "-ar", str(sample_rate),
        "-ac", "1",
        "-f", "s16le",      # 16-bit signed little-endian PCM
        "-",
    ]

    try:
        proc = subprocess.run(cmd, input=audio_bytes, capture_output=True, timeout=30)
    except FileNotFoundError:
        raise RuntimeError(
            "ffmpeg 未安装。请运行: brew install ffmpeg"
        )
    except subprocess.TimeoutExpired:
        raise RuntimeError("音频解码超时")

    if proc.returncode != 0:
        err = proc.stderr.decode().strip() or "未知错误"
        raise RuntimeError(f"音频解码失败: {err}")

    if len(proc.stdout) == 0:
        raise RuntimeError("音频解码结果为空")

    # s16le → float32
    samples = np.frombuffer(proc.stdout, dtype=np.int16).astype(np.float32) / 32768.0

    if len(samples) == 0:
        raise RuntimeError("解码后音频为空")

    log.info("音频解码完成: %d 样本 (%.1fs)", len(samples), len(samples) / sample_rate)
    return samples


# ── 模型懒加载 ─────────────────────────────────────────────
_model = None
_model_name = None


def _load_model():
    """同步加载 mlx-whisper 模型（在后台线程中执行）"""
    global _model, _model_name
    model_name = os.environ.get(
        "ASR_MODEL", "mlx-community/whisper-large-v3-turbo"
    )
    if _model is not None and _model_name == model_name:
        return _model

    t0 = time.time()
    log.info("加载模型: %s ...", model_name)
    import mlx_whisper

    # 用一段静音音频提前加载模型到内存
    import numpy as np
    silence = np.zeros(16000, dtype=np.float32)  # 1秒静音 16kHz
    mlx_whisper.transcribe(silence, path_or_hf_repo=model_name, language="zh", verbose=False)

    _model = mlx_whisper
    _model_name = model_name
    log.info("模型加载完成 (%.1fs)", time.time() - t0)
    return _model


@asynccontextmanager
async def lifespan(app: FastAPI):
    """启动时后台加载模型"""
    loop = asyncio.get_running_loop()
    loop.run_in_executor(None, _load_model)
    yield


app = FastAPI(
    title="ASR Service",
    description="mlx-whisper 语音转文字服务",
    version="1.0.0",
    lifespan=lifespan,
)


# ── API 端点 ───────────────────────────────────────────────


@app.get("/health")
async def health():
    """健康检查"""
    return {
        "status": "ok",
        "model": _model_name or os.environ.get("ASR_MODEL", "未加载"),
    }


@app.post("/transcribe")
async def transcribe(file: UploadFile = File(...)):
    """
    语音转文字

    接收上传的音频文件（webm / wav / mp3 / m4a 等），
    返回转写文本及分段信息。
    """
    if not file.filename:
        raise HTTPException(400, "文件名不能为空")

    try:
        audio_bytes = await file.read()
    except Exception as e:
        raise HTTPException(400, f"读取音频文件失败: {e}")

    if len(audio_bytes) == 0:
        raise HTTPException(400, "音频文件为空")

    # 1. 用 ffmpeg 解码为 16kHz mono PCM
    try:
        audio_array = decode_audio(audio_bytes)
    except RuntimeError as e:
        log.warning("ffmpeg 解码失败: %s, 尝试直接传给 whisper", e)
        audio_array = audio_bytes

    # 2. 语音转写
    model = _load_model()
    model_name = _model_name

    try:
        t0 = time.time()
        result = model.transcribe(
            audio_array,
            path_or_hf_repo=model_name,
            language="zh",
            verbose=False,
        )
    except ImportError:
        raise HTTPException(500, "mlx-whisper 未安装，请运行: pip install mlx-whisper")
    except Exception as e:
        log.error("转写失败: %s", e)
        raise HTTPException(500, f"转写失败: {e}")

    elapsed = round(time.time() - t0, 2)
    text = (result.get("text") or "").strip()
    log.info("转写完成 (%.1fs): %s", elapsed, text[:80])

    # segments 字段名兼容不同版本
    raw_segments = result.get("segments") or []
    segments = []
    for s in raw_segments:
        seg_text = (s.get("text") or "").strip()
        if seg_text:
            segments.append({
                "start": round(float(s.get("start", 0)), 2),
                "end": round(float(s.get("end", 0)), 2),
                "text": seg_text,
            })

    return JSONResponse({
        "text": text,
        "language": result.get("language", "zh"),
        "segments": segments,
    })
