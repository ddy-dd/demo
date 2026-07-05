package com.example.demo.ai.asr;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.concurrent.TimeUnit;

/**
 * ASR（语音转文字）进程管理器
 *
 * <p>当 app.asr.managed=true 时，Spring Boot 启动后自动拉起 Python ASR 微服务，
 * 关闭时自动终止进程。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.asr", name = "managed", havingValue = "true")
public class AsrProcessManager {

    private final AsrConfig config;
    private Process asrProcess;

    public AsrProcessManager(AsrConfig config) {
        this.config = config;
    }

    @PostConstruct
    public void start() {
        int port = extractPort(config.getBaseUrl());

        String userDir = System.getProperty("user.dir");
        File workingDir = new File(userDir, config.getScriptDir() != null
                ? config.getScriptDir() : "asr-server");

        if (!workingDir.exists() || !workingDir.isDirectory()) {
            log.warn("ASR 目录不存在: {}，跳过自动启动", workingDir.getAbsolutePath());
            return;
        }

        File startScript = new File(workingDir, "start.sh");
        if (!startScript.exists()) {
            log.warn("ASR 启动脚本不存在: {}，跳过自动启动", startScript.getAbsolutePath());
            return;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "bash", startScript.getAbsolutePath()
            );
            pb.directory(workingDir);
            pb.environment().put("ASR_PORT", String.valueOf(port));
            pb.redirectErrorStream(true);

            asrProcess = pb.start();

            Thread outputReader = new Thread(() -> {
                try (var reader = asrProcess.inputReader()) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.info("[ASR] {}", line);
                    }
                } catch (IOException e) {
                    // 进程结束时的正常异常
                }
            }, "asr-log-reader");
            outputReader.setDaemon(true);
            outputReader.start();

            log.info("🚀 ASR 进程已拉起，后台等待就绪...");

            Thread healthChecker = new Thread(() -> waitForHealth(port), "asr-health");
            healthChecker.setDaemon(true);
            healthChecker.start();

        } catch (IOException e) {
            log.error("启动 ASR 进程失败", e);
        }
    }

    @PreDestroy
    public void stop() {
        if (asrProcess == null || !asrProcess.isAlive()) {
            return;
        }
        log.info("🛑 停止 ASR 服务进程...");
        asrProcess.destroy();
        try {
            if (!asrProcess.waitFor(5, TimeUnit.SECONDS)) {
                asrProcess.destroyForcibly();
                asrProcess.waitFor(3, TimeUnit.SECONDS);
            }
            log.info("ASR 进程已终止");
        } catch (InterruptedException e) {
            log.warn("等待 ASR 进程终止被中断", e);
            Thread.currentThread().interrupt();
        }
    }

    private void waitForHealth(int port) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(120);
        int attempt = 0;

        while (System.currentTimeMillis() < deadline) {
            try {
                attempt++;
                TimeUnit.SECONDS.sleep(2);

                var url = URI.create("http://localhost:" + port + "/health").toURL();
                var conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                conn.setRequestMethod("GET");

                if (conn.getResponseCode() == 200) {
                    log.info("✅ ASR 服务就绪 (localhost:{})", port);
                    return;
                }

                if (attempt % 10 == 0) {
                    log.info("⏳ 等待 ASR 服务启动... (已等待 {}s)", attempt * 2);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                if (attempt % 10 == 0) {
                    log.info("⏳ 等待 ASR 服务启动... (已等待 {}s)", attempt * 2);
                }
            }
        }

        log.warn("⚠ ASR 服务未能在 120s 内就绪，请检查 Python/mlx-whisper 环境");
    }

    private static int extractPort(String baseUrl) {
        if (baseUrl == null) return 8001;
        try {
            var uri = URI.create(baseUrl);
            int p = uri.getPort();
            return p > 0 ? p : 8001;
        } catch (Exception e) {
            return 8001;
        }
    }
}
