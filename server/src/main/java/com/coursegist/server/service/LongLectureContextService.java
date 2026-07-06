package com.coursegist.server.service;

import com.coursegist.server.dto.LectureChunk;
import com.coursegist.server.dto.LectureContext;
import com.coursegist.server.utils.LlmUtils;
import com.coursegist.server.utils.EmbeddingUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class LongLectureContextService {

    private static final long CHUNK_MS = 5 * 60 * 1000L;
    private static final int TOP_K = 3;

    @Autowired
    private LlmUtils llmUtils;

    @Autowired
    private EmbeddingUtils embeddingUtils;

    public LectureContext selectRelevant(LectureContext context) {
        if (context.segments().isEmpty()
                || context.segments().get(context.segments().size() - 1).endMs() <= CHUNK_MS) {
            return context;
        }

        List<LectureChunk> chunks = buildChunks(context.segments());
        List<Double> queryEmbedding = embeddingUtils.embed(context.userGoal());

        List<LectureContext.LectureSegment> selectedSegments = chunks.stream()
                .sorted(Comparator.comparingDouble(
                        (LectureChunk chunk) -> hybridScore(
                                context.userGoal(), queryEmbedding, chunk)
                ).reversed())
                .limit(TOP_K)
                .flatMap(chunk -> chunk.rawSegments().stream())
                .sorted(Comparator.comparingLong(LectureContext.LectureSegment::startMs))
                .toList();

        return new LectureContext(context.source(), context.userGoal(), selectedSegments);
    }

    private List<LectureChunk> buildChunks(List<LectureContext.LectureSegment> segments) {
        List<LectureChunk> chunks = new ArrayList<>();
        for (long start = 0; start <= segments.get(segments.size() - 1).startMs(); start += CHUNK_MS) {
            long end = start + CHUNK_MS;
            long chunkStart = start;
            List<LectureContext.LectureSegment> rawSegments = segments.stream()
                    .filter(segment -> segment.startMs() >= chunkStart && segment.startMs() < end)
                    .toList();
            if (rawSegments.isEmpty()) continue;

            LectureChunk.ChunkSummary summary = llmUtils.summarizeChunk(rawSegments);
            String embeddingText = summary.segmentSummary() + "\n" + String.join(" ", summary.keywords());
            chunks.add(new LectureChunk(
                    start,
                    end,
                    summary.segmentSummary(),
                    summary.keywords(),
                    rawSegments,
                    embeddingUtils.embed(embeddingText)
            ));
        }
        return chunks;
    }

    private double hybridScore(String goal, List<Double> queryEmbedding, LectureChunk chunk) {
        // 先用轻量关键词命中做初筛；数据规模上来后可替换为分词器或 Reranker
        long matched = chunk.keywords().stream()
                .filter(keyword -> goal != null && goal.contains(keyword))
                .count();
        double keywordScore = chunk.keywords().isEmpty() ? 0 : (double) matched / chunk.keywords().size();
        return cosine(queryEmbedding, chunk.embedding()) * 0.7 + keywordScore * 0.3;
    }

    private double cosine(List<Double> left, List<Double> right) {
        if (left.size() != right.size() || left.isEmpty()) return 0;

        double dot = 0;
        double leftLength = 0;
        double rightLength = 0;
        for (int i = 0; i < left.size(); i++) {
            dot += left.get(i) * right.get(i);
            leftLength += left.get(i) * left.get(i);
            rightLength += right.get(i) * right.get(i);
        }
        if (leftLength == 0 || rightLength == 0) return 0;
        return dot / (Math.sqrt(leftLength) * Math.sqrt(rightLength));
    }
}
