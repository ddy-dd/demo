package com.example.demo.ai.exception;

/**
 * AI 服务调用自定义异常
 *
 * 封装 DeepSeek / Ollama 等模型调用失败时的异常信息，
 * 由 GlobalExceptionHandler 统一捕获并返回友好提示。
 */
public class AiServiceException extends RuntimeException {

    public AiServiceException(String message) {
        super(message);
    }

    public AiServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
