package com.example.demo.ai.asr;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * ASR（语音转文字）REST 控制器
 *
 * <p>接收前端上传的音频文件，转发给 Python mlx-whisper 微服务，
 * 返回转写文本。</p>
 */
@RestController
@Slf4j
@RequestMapping("/asr")
public class AsrController {

    private final AsrService asrService;

    public AsrController(AsrService asrService) {
        this.asrService = asrService;
    }

    /**
     * 音频转写
     *
     * @param file 上传的音频文件（webm / wav / mp3 / m4a 等）
     * @return { text: "转写结果" }
     */
    @PostMapping(value = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> transcribe(
            @RequestParam("file") MultipartFile file) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "音频文件为空"
            ));
        }

        try {
            String filename = file.getOriginalFilename();
            if (filename == null || filename.isBlank()) {
                filename = "audio.webm";
            }

            var response = asrService.transcribe(file.getBytes(), filename);

            return ResponseEntity.ok(Map.of(
                    "text", response.text(),
                    "language", response.language() != null ? response.language() : "zh",
                    "segments", response.segments()
            ));
        } catch (IOException e) {
            log.error("读取上传音频失败", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "读取音频文件失败: " + e.getMessage()
            ));
        } catch (AsrException e) {
            log.warn("ASR 服务不可用: {}", e.getMessage());
            return ResponseEntity.status(503).body(Map.of(
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("ASR 转写失败", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "语音转写失败: " + e.getMessage()
            ));
        }
    }
}
