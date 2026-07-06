package com.coursegist.server.strategy.impl;

import com.coursegist.server.strategy.AiAnalysisStrategy;
import com.coursegist.server.utils.AsrUtils;
import com.coursegist.server.utils.LlmUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component("defaultAiStrategy")
public class DefaultAnalysisStrategy implements AiAnalysisStrategy {

    @Autowired
    private AsrUtils asrUtils;

    @Autowired
    private LlmUtils llmUtils;

    @Override
    public String transcribe(String videoPath) {
        return processVideoToText(videoPath);
    }

    @Override
    public String generateSummary(String videoPath) {
        String text = processVideoToText(videoPath);
        if (text.startsWith("❌")) return text;

        return llmUtils.analyzeContent("请对以下课程转写文本进行提炼，直接列出核心知识点：\n" + text);
    }


    private String processVideoToText(String inputPath) {
        if (inputPath == null || inputPath.isEmpty()) return "❌ 路径为空";

        // 本地路径需校验存在性；HTTP 地址交给 FFmpeg 自行拉流
        if (!inputPath.startsWith("http")) {
            File localFile = new File(inputPath);
            if (!localFile.exists()) return "❌ 磁盘找不到文件: " + inputPath;
        }

        String outputMp3Path = System.getProperty("java.io.tmpdir") + File.separator + "temp_" + UUID.randomUUID() + ".mp3";

        try {
            System.out.println("[strategy] 处理课程源: " + inputPath);

            // FFmpeg 原生支持 HTTP 输入，无需先落盘
            boolean success = extractAudio(inputPath, outputMp3Path);
            if (!success) return "FFmpeg 转换失败 (可能是网络超时或文件损坏)";

            String text = asrUtils.audioToText(outputMp3Path);
            return text;

        } catch (Exception e) {
            e.printStackTrace();
            return "处理异常: " + e.getMessage();
        } finally {
            // 清理临时音频
            File mp3 = new File(outputMp3Path);
            if (mp3.exists()) mp3.delete();
        }
    }

    // === FFmpeg 工具 ===
    private boolean extractAudio(String inputPath, String outputPath) {
        Process process = null;
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

            process = pb.start();
            //网络流可能比较慢，给多点时间
            boolean finished = process.waitFor(15, java.util.concurrent.TimeUnit.MINUTES);

            if (finished) {
                return process.exitValue() == 0;
            } else {
                process.destroyForcibly();
                return false;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}