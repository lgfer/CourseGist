package com.coursegist.server.controller;

import com.coursegist.server.dto.AnalysisTaskMsg;
import com.coursegist.server.entity.CourseVideo;
import com.coursegist.server.mapper.CourseVideoMapper;
import com.coursegist.server.service.AiService;
import com.coursegist.server.strategy.AiAnalysisStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/analysis")
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class AnalysisController {

    @Autowired
    private CourseVideoMapper courseVideoMapper;

    @Autowired
    @Qualifier("defaultAiStrategy")
    private AiAnalysisStrategy aiAnalysisStrategy;

    @Autowired
    private AiService aiService;


    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private org.apache.rocketmq.spring.core.RocketMQTemplate rocketMQTemplate;

    @Autowired
    private org.redisson.api.RedissonClient redissonClient;

    // 提交课程解析任务：令牌桶限流 -> 内容级去重 -> 投递 RocketMQ
    @GetMapping("/ai")
    public String aiAnalyze(@RequestParam Long id,
                            @RequestParam(defaultValue = "梳理本节课的核心知识点，生成结构化学习笔记") String goal) {
        String activeKey = null;

        try {
            if (goal.isBlank() || goal.length() > 500) {
                return "学习目标不能为空且不能超过 500 字";
            }
            // 全局速率限制：每分钟最多受理 10 次解析，防止恶意刷量导致 Token 成本失控
            String limitKey = "rate:ai:global";
            org.redisson.api.RRateLimiter rateLimiter = redissonClient.getRateLimiter(limitKey);
            // RateType.OVERALL 表示所有实例共享同一份令牌
            rateLimiter.trySetRate(org.redisson.api.RateType.OVERALL, 10, 1, org.redisson.api.RateIntervalUnit.MINUTES);

            if (!rateLimiter.tryAcquire(1)) {
                return "⚠️ 当前解析请求较多，请 1 分钟后再试";
            }

            CourseVideo file = courseVideoMapper.selectById(id);
            if (file == null) return "文件不存在";
            if (file.getAiSummary() != null && file.getAiSummary().contains("正在")) {
                return "任务已在后台运行，无需重复提交";
            }

            String contentHash = redisTemplate.opsForValue().get("course:md5:" + id);
            if (contentHash == null || !contentHash.matches("[a-f0-9]{32}")) {
                contentHash = "course-" + id;
            }
            activeKey = "lecture:active:" + contentHash;
            Boolean accepted = redisTemplate.opsForValue()
                    .setIfAbsent(activeKey, String.valueOf(id), 2, TimeUnit.HOURS);
            if (!Boolean.TRUE.equals(accepted)) {
                return "⚠️ 相同内容的课程正在解析中，请勿重复提交";
            }

            file.setAiSummary("任务已进入队列，等待调度...");
            courseVideoMapper.updateById(file);
            String userIdKey = (file.getUserId() == null) ? "anon" : String.valueOf(file.getUserId());
            redisTemplate.delete("course:list:user:" + userIdKey);

            AnalysisTaskMsg msg = new AnalysisTaskMsg(id, "START_ANALYSIS", contentHash, goal);
            rocketMQTemplate.convertAndSend("lecture-analysis-topic", msg);

            return "✅ 任务已提交，正在排队解析";

        } catch (Exception e) {
            e.printStackTrace();
            if (activeKey != null) {
                redisTemplate.delete(activeKey);
            }
            return "❌ 提交失败: " + e.getMessage();
        }
    }

    // 仅提取讲稿全文，不生成笔记
    @GetMapping("/transcribe")
    public String transcribe(@RequestParam Long id) {
        CourseVideo courseVideo = courseVideoMapper.selectById(id);
        if (courseVideo == null) return "❌ 找不到课程记录";

        // 调用异步服务
        aiService.asyncTranscribe(id);

        return "✅ 讲稿提取已在后台运行，请稍后查看";
    }

    @GetMapping("/follow-up")
    public String followUp(@RequestParam Long id, @RequestParam String question) {
        if (question.isBlank() || question.length() > 500) {
            return "追问内容不能为空且不能超过 500 字";
        }
        return aiService.followUp(id, question);
    }

    // 将课程转码为 mp3 供下载
    @GetMapping("/download")
    public ResponseEntity<Resource> download(@RequestParam Long id) throws IOException {
        CourseVideo courseVideo = courseVideoMapper.selectById(id);
        if (courseVideo == null) return ResponseEntity.notFound().build();

        String inputPath = courseVideo.getFilePath();

        if (!inputPath.startsWith("http")) {
            if (!new File(inputPath).exists()) return ResponseEntity.notFound().build();
        }

        String outputMp3Path = System.getProperty("java.io.tmpdir") + File.separator + "download_" + UUID.randomUUID() + ".mp3";
        System.out.println("[download] 从源地址转码音频: " + inputPath);

        boolean success = runFfmpeg(inputPath, outputMp3Path);

        if (!success) return ResponseEntity.internalServerError().build();

        File mp3File = new File(outputMp3Path);
        Resource resource = new FileSystemResource(mp3File);

        String fileName = "audio.mp3";
        if (courseVideo.getFilename() != null) {
            fileName = courseVideo.getFilename().replaceAll("\\.[^.]+$", "") + ".mp3";
        }
        String encodedName = URLEncoder.encode(fileName, StandardCharsets.UTF_8);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                .body(resource);
    }

    private boolean runFfmpeg(String inputPath, String outputPath) {
        try {
            List<String> command = new ArrayList<>();
            command.add("ffmpeg");
            command.add("-y");
            command.add("-i");
            command.add(inputPath);
            command.add("-vn");
            command.add("-acodec");
            command.add("libmp3lame");
            command.add("-q:a");
            command.add("2");
            command.add(outputPath);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            Process process = pb.start();
            return process.waitFor(15, TimeUnit.MINUTES) && process.exitValue() == 0;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}
