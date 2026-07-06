package com.coursegist.server.dto;

import java.util.List;

/**
 * 将一节课的语音转写与画面 OCR 信息按时间轴对齐，整理成 Agent 可直接消费的统一上下文。
 */
public record LectureContext(
        String source,
        String userGoal,
        List<LectureSegment> segments
) {
    public record LectureSegment(
            long startMs,
            long endMs,
            String transcript,
            List<String> ocrTexts,
            List<String> evidenceFrames
    ) {
    }

    public String transcriptText() {
        return segments.stream()
                .map(LectureSegment::transcript)
                .filter(text -> text != null && !text.isBlank())
                .reduce("", (left, right) -> left.isEmpty() ? right : left + "\n" + right);
    }
}
