package com.example.demo.ai.asr;

/**
 * ASR 服务异常
 *
 * <p>当 Python 语音识别服务不可用或转写失败时抛出。</p>
 */
public class AsrException extends RuntimeException {

    public AsrException(String message) {
        super(message);
    }

    public AsrException(String message, Throwable cause) {
        super(message, cause);
    }
}
