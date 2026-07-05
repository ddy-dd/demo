package com.example.demo.ai.asr;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * ASR（语音转文字）服务配置
 *
 * <p>从 application.yml 读取 app.asr.* 配置项，
 * 指向 Python mlx-whisper 微服务。</p>
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "app.asr")
public class AsrConfig {

    /** Python ASR 服务地址 */
    private String baseUrl = "http://localhost:8001";

    /** HTTP 请求超时（毫秒） */
    private int timeout = 30000;

    /** 是否由 Spring Boot 自动管理 Python 进程（启动时拉起，关闭时杀掉） */
    private boolean managed = false;

    /** ASR 服务脚本目录（相对于 user.dir） */
    private String scriptDir = "asr-server";
}
