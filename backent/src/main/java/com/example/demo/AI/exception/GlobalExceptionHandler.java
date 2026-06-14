package com.example.demo.ai.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 全局异常处理器
 *
 * 将 Controller 层所有未捕获的异常转换为统一的 ErrorResponse 响应格式，
 * 避免异常堆栈泄露到前端，同时记录完整的错误上下文到日志。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理通用业务异常
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        log.warn("[{}] 参数校验失败: {}", MDC.get("traceId"), ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, "INVALID_PARAM", ex.getMessage(), request);
    }

    /**
     * 处理文件上传超过大小限制
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleUploadSizeExceeded(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        log.warn("[{}] 文件上传超限: {}", MDC.get("traceId"), ex.getMessage());
        return buildResponse(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "文件大小超过限制", request);
    }

    /**
     * 处理 AI 服务调用异常（DeepSeek / Ollama）
     */
    @ExceptionHandler(com.example.demo.ai.exception.AiServiceException.class)
    public ResponseEntity<ErrorResponse> handleAiService(AiServiceException ex, HttpServletRequest request) {
        log.error("[{}] AI 服务异常: {}", MDC.get("traceId"), ex.getMessage(), ex);
        return buildResponse(HttpStatus.SERVICE_UNAVAILABLE, "AI_SERVICE_ERROR", ex.getMessage(), request);
    }

    /**
     * 兜底：处理所有未明确处理的异常
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex, HttpServletRequest request) {
        log.error("[{}] 未预期异常: {}", MDC.get("traceId"), ex.getMessage(), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务器内部错误，请稍后再试", request);
    }

    // ===== 辅助方法 =====

    private ResponseEntity<ErrorResponse> buildResponse(HttpStatus status, String code, String message, HttpServletRequest request) {
        ErrorResponse error = new ErrorResponse(
                status.value(),
                code,
                message,
                request.getRequestURI()
        );
        return new ResponseEntity<>(error, status);
    }
}
