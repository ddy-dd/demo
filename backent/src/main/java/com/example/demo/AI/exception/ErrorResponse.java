package com.example.demo.ai.exception;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 统一 API 错误响应体
 *
 * 所有异常经过 GlobalExceptionHandler 处理后统一返回此格式，
 * 保证前端收到的错误结构一致，便于统一处理。
 */
@Data
@AllArgsConstructor
public class ErrorResponse {

    /** HTTP 状态码 */
    private int status;

    /** 业务错误码（可用于前端 i18n 或分类处理） */
    private String code;

    /** 用户可读的错误描述 */
    private String message;

    /** 错误发生时间 */
    private LocalDateTime timestamp;

    /** 请求路径 */
    private String path;

    public ErrorResponse(int status, String code, String message, String path) {
        this.status = status;
        this.code = code;
        this.message = message;
        this.timestamp = LocalDateTime.now();
        this.path = path;
    }
}
