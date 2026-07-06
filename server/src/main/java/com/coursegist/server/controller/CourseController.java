package com.coursegist.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.coursegist.server.entity.CourseVideo;
import com.coursegist.server.mapper.CourseVideoMapper;
import com.coursegist.server.service.CourseService;
import com.coursegist.server.utils.MinioUtils;
import com.coursegist.server.utils.YtDlpUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/course")
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class CourseController {

    @Autowired(required = false)
    private CourseVideoMapper courseVideoMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MinioUtils minioUtils;

    @Autowired
    private YtDlpUtils ytDlpUtils;

    @Autowired
    private CourseService courseService;

    @PostMapping("/init-upload")
    public ResponseEntity<String> initUpload(@RequestParam String filename,
                                             @RequestParam int totalChunks,
                                             @RequestParam(value = "userId", required = false) Long userId) {
        try {
            return ResponseEntity.ok(courseService.initChunkedUpload(filename, totalChunks, userId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Failed to initialize upload");
        }
    }

    @GetMapping("/upload-status")
    public ResponseEntity<?> uploadStatus(@RequestParam String uploadId) {
        try {
            Set<Integer> uploadedChunks = courseService.getUploadedChunks(uploadId);
            return ResponseEntity.ok(uploadedChunks);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/upload-chunk")
    public ResponseEntity<String> uploadChunk(@RequestParam String uploadId,
                                              @RequestParam int chunkIndex,
                                              @RequestParam int totalChunks,
                                              @RequestParam("file") MultipartFile file) {
        try {
            courseService.uploadChunk(uploadId, chunkIndex, totalChunks, file);
            return ResponseEntity.ok("Chunk uploaded");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Chunk upload failed");
        }
    }

    @PostMapping("/complete-upload")
    public ResponseEntity<String> completeUpload(@RequestParam String uploadId) {
        try {
            courseService.completeChunkedUpload(uploadId);
            return ResponseEntity.ok("Upload success");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Upload merge failed");
        }
    }


    @PostMapping("/upload")
    public ResponseEntity<String> upload(@RequestParam("file") MultipartFile file,
                                         @RequestParam(value = "userId", required = false) Long userId) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body("Upload failed: file is empty");
        }
        if (courseVideoMapper == null) {
            return ResponseEntity.status(500).body("Upload failed: database not ready");
        }
        try {
            String md5 = courseService.calculateMd5(file);
            String fileUrl = minioUtils.uploadFile(file);

            CourseVideo courseVideo = new CourseVideo();
            courseVideo.setFilename(file.getOriginalFilename());
            courseVideo.setFilePath(fileUrl);
            courseVideo.setStatus("COMPLETED");
            courseVideo.setUploadTime(LocalDateTime.now());

            if (userId != null) {
                courseVideo.setUserId(userId);
            }

            courseVideoMapper.insert(courseVideo);
            courseService.rememberContentHash(courseVideo.getId(), md5);

            if (userId != null) {
                String cacheKey = "course:list:user:" + userId;
                redisTemplate.delete(cacheKey);
            }

            return ResponseEntity.ok("Upload success");

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Upload failed: " + e.getMessage());
        }
    }

    @PostMapping("/upload-url")
    public org.springframework.http.ResponseEntity<String> uploadUrl(@RequestParam("url") String url,
                                                                     @RequestParam(value = "userId", required = false) Long userId) {
        File tempFile = null;
        try {
            if (url == null || url.isBlank()) {
                return org.springframework.http.ResponseEntity.badRequest().body("Upload failed: url is empty");
            }
            if (courseVideoMapper == null) {
                return org.springframework.http.ResponseEntity.status(500).body("Upload failed: database not ready");
            }
            System.out.println("[course] 收到链接导入请求: " + url);

            tempFile = ytDlpUtils.downloadVideo(url);

            String md5 = courseService.calculateMd5(tempFile);
            String fileUrl = minioUtils.uploadLocalFile(tempFile);

            CourseVideo courseVideo = new CourseVideo();
            courseVideo.setFilename("WEB_" + tempFile.getName());
            courseVideo.setFilePath(fileUrl);
            courseVideo.setStatus("COMPLETED");
            courseVideo.setUploadTime(LocalDateTime.now());

            if (userId != null) {
                courseVideo.setUserId(userId);
            }

            courseVideoMapper.insert(courseVideo);
            courseService.rememberContentHash(courseVideo.getId(), md5);

            if (userId != null) {
                String cacheKey = "course:list:user:" + userId;
                redisTemplate.delete(cacheKey);
            }

            return org.springframework.http.ResponseEntity.ok("Upload success");

        } catch (Exception e) {
            e.printStackTrace();
            return org.springframework.http.ResponseEntity.status(500).body("Upload failed: " + e.getMessage());
        } finally {
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    @GetMapping("/list")
    public List<CourseVideo> getList(@RequestParam(value = "userId", required = false) Long userId) {
        String cacheKey = "course:list:user:" + (userId == null ? "anon" : userId);

        try {
            String json = redisTemplate.opsForValue().get(cacheKey);
            if (json != null) {
                System.out.println("[course] 列表命中 Redis 缓存");
                return objectMapper.readValue(json, new TypeReference<List<CourseVideo>>(){});
            }
        } catch (Exception e) {
            System.err.println("[course] Redis 读取失败: " + e.getMessage());
        }

        QueryWrapper<CourseVideo> query = new QueryWrapper<>();
        if (userId != null) {
            query.eq("user_id", userId);
        } else {
            return List.of();
        }
        List<CourseVideo> list = courseVideoMapper.selectList(query.orderByDesc("id"));

        try {
            String jsonToWrite = objectMapper.writeValueAsString(list);
            redisTemplate.opsForValue().set(cacheKey, jsonToWrite, 30, TimeUnit.MINUTES);
            System.out.println("[course] 列表已写入 Redis 缓存");
        } catch (Exception e) {
            e.printStackTrace();
        }

        return list;
    }

    @DeleteMapping("/delete")
    public String delete(@RequestParam("id") Long id,
                         @RequestParam(value = "userId", required = false) Long userId) {

        CourseVideo media = courseVideoMapper.selectById(id);
        if (media == null) return "文件不存在";

        if (userId != null && !media.getUserId().equals(userId)) {
            return "无权删除他人的文件";
        }

        if (media.getFilePath() != null && media.getFilePath().startsWith("http")) {
            minioUtils.removeFile(media.getFilePath());
        }

        courseVideoMapper.deleteById(id);

        if (media.getUserId() != null) {
            String cacheKey = "course:list:user:" + media.getUserId();
            redisTemplate.delete(cacheKey);
        }

        return "删除成功";
    }
}
