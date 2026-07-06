package com.coursegist.server.strategy;

/**
 * AI 分析策略接口。
 * 路径参数使用 String，同时兼容本地磁盘路径与 MinIO 的 HTTP 地址。
 */
public interface AiAnalysisStrategy {

    /**
     * 将课程视频转写为讲稿文本
     * @param videoPath 课程视频路径或 URL
     */
    String transcribe(String videoPath);

    /**
     * 对课程内容生成学习笔记
     * @param videoPath 课程视频路径或 URL
     */
    String generateSummary(String videoPath);
}