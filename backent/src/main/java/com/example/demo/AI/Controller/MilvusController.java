package com.example.demo.AI.Controller;

import com.example.demo.AI.ServiceImpl.Service.KnowledgeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;


@RestController
@Slf4j
@RequestMapping("/zvec")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:5173")
public class MilvusController {

    private final KnowledgeService knowledgeService;

    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file) {
        if(file.isEmpty()){
            log.warn("文件为空");
        }

        try{
            knowledgeService.AddKnowledge(file);
            log.info("上传成功");
        }catch (Exception e){
            log.error("上传失败");
        }

        return "上传成功";
    }

}
