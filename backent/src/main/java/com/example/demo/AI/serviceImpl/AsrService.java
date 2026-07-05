package com.example.demo.ai.serviceImpl;

import com.example.demo.ai.config.AsrConfig;
import com.example.demo.ai.exception.AsrException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * ASR（语音转文字）服务
 *
 * <p>将前端上传的音频数据转发给 Python mlx-whisper 微服务进行转写，
 * 返回识别文本。</p>
 */
@Slf4j
@Service
public class AsrService {

    private final RestTemplate restTemplate;
    private final AsrConfig config;

    public AsrService(AsrConfig config) {
        this.config = config;

        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(config.getTimeout());
        factory.setReadTimeout(config.getTimeout());

        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 转写音频文件
     *
     * @param audioData 音频原始字节
     * @param filename  文件名（用于 Content-Disposition）
     * @return 转写文本
     */
    public AsrResponse transcribe(byte[] audioData, String filename) {
        long t0 = System.currentTimeMillis();

        try {
            // 构建 multipart 请求体
            var body = new LinkedMultiValueMap<String, Object>();
            body.add("file", new ByteArrayResource(audioData) {
                @Override
                public String getFilename() {
                    return filename != null ? filename : "audio.webm";
                }
            });

            var headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            var entity = new HttpEntity<>(body, headers);

            // POST 到 Python ASR 服务，让 RestTemplate 直接反序列化
            var response = restTemplate.exchange(
                    config.getBaseUrl() + "/transcribe",
                    HttpMethod.POST,
                    entity,
                    AsrResponse.class
            );

            long elapsed = System.currentTimeMillis() - t0;
            AsrResponse result = response.getBody();

            if (result == null) {
                throw new AsrException("ASR 服务返回空响应");
            }

            log.info("ASR 转写完成 ({}ms): {}", elapsed, truncate(result.text(), 80));
            return result;

        } catch (AsrException e) {
            throw e;
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("Connect to")) {
                throw new AsrException("ASR 服务未启动，请先启动 Python 语音识别服务", e);
            }
            if (msg != null && msg.contains("Read timed out")) {
                throw new AsrException("ASR 服务响应超时，请检查 Python 语音识别服务状态", e);
            }
            throw new AsrException("语音转写失败: " + e.getMessage(), e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // ── 响应 DTO ──────────────────────────────────────────

    /** ASR 服务返回的响应结构 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AsrResponse(
            String text,
            String language,
            List<Segment> segments
    ) {}

    /** 分段信息 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Segment(
            @JsonProperty("start") double start,
            @JsonProperty("end") double end,
            String text
    ) {}
}
