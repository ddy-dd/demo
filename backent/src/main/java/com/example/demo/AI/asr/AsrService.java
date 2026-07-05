package com.example.demo.ai.asr;

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
 * <p>将音频数据转发给 Python mlx-whisper 微服务进行转写，
 * 返回识别文本。</p>
 */
@Slf4j
@Service
public class AsrService {

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public AsrService(AsrConfig config) {
        this.baseUrl = config.getBaseUrl();
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(config.getTimeout());
        factory.setReadTimeout(config.getTimeout());
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 转写音频文件
     *
     * @param audioData 音频原始字节
     * @param filename  文件名
     * @return 转写结果
     */
    public AsrResponse transcribe(byte[] audioData, String filename) {
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

        var response = restTemplate.exchange(
                baseUrl + "/transcribe",
                HttpMethod.POST,
                entity,
                AsrResponse.class
        );

        AsrResponse result = response.getBody();
        if (result == null) {
            throw new RuntimeException("ASR 服务返回空响应");
        }
        return result;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AsrResponse(
            String text,
            String language,
            List<Segment> segments
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Segment(
            @JsonProperty("start") double start,
            @JsonProperty("end") double end,
            String text
    ) {}
}
