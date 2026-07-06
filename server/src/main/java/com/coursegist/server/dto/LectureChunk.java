package com.coursegist.server.dto;

import java.util.List;

/**
 * 长课程的五分钟语义块：摘要与关键词用于检索排序，原始片段在命中后按需装载。
 */
public record LectureChunk(
        long startTime,
        long endTime,
        String segmentSummary,
        List<String> keywords,
        List<LectureContext.LectureSegment> rawSegments,
        List<Double> embedding
) {
    public record ChunkSummary(
            String segmentSummary,
            List<String> keywords
    ) {
    }
}
