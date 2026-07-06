package com.coursegist.server.utils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class YtDlpUtils {

    @Value("${tool.ytdlp.path}")
    private String ytDlpPath;

    @Value("${tool.ffmpeg.dir}")
    private String ffmpegDir;

    public File downloadVideo(String url) throws Exception {
        String tempDir = System.getProperty("java.io.tmpdir");
        String outputName = UUID.randomUUID().toString() + ".mp4";
        String outputPath = tempDir + File.separator + outputName;

        System.out.println("[yt-dlp] 开始下载: " + url);

        List<String> command = new ArrayList<>();
        command.add(ytDlpPath);

        // 不限定 -f 格式，交给 yt-dlp 自行选择最佳兼容格式；附带 UA/Referer 降低被风控概率
        command.add("--user-agent");
        command.add("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
        command.add("--referer");
        command.add("https://www.bilibili.com/");

        // 统一转码为 mp4，方便后续 FFmpeg 处理
        command.add("--recode-video");
        command.add("mp4");

        command.add("--ffmpeg-location");
        command.add(ffmpegDir);

        command.add("-o");
        command.add(outputPath);

        command.add("--no-check-certificate");
        command.add("--no-playlist");

        command.add(url);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        Process process = pb.start();

        StringBuilder logs = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("ERROR") || line.contains("Downloading") || line.contains("[Merger]")) {
                    System.out.println("cmd > " + line);
                }
                logs.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("yt-dlp 下载失败: " + logs.toString());
        }

        File downloadedFile = new File(outputPath);
        if (!downloadedFile.exists()) {
            throw new RuntimeException("下载显示成功但文件未生成");
        }

        System.out.println("[yt-dlp] 下载完成: " + (downloadedFile.length() / 1024) + "KB");
        return downloadedFile;
    }
}