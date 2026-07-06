package com.coursegist.server.utils;

import com.coursegist.server.dto.AgentState;
import com.coursegist.server.dto.AnalysisResult;
import com.coursegist.server.dto.LectureChunk;
import com.coursegist.server.dto.LectureContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LlmUtils {

    private static final String SYSTEM_PROMPT = """
            # 角色
            你是一位经验丰富的课程教研专家，擅长把讲师的口语化讲解重新组织成便于复习的结构化笔记。

            # 目标
            过滤口头禅、寒暄和重复表述，从课程转写文本中提炼知识点，输出条理清晰的学习笔记。

            # 约束
            1. 若文本过短或没有实质内容，直接输出「本节课暂无可提炼的知识点」。
            2. 不要输出任何开场白或客套结尾。
            3. 严格按照下面的 Markdown 结构组织内容：

            ## 课程速览
            ## 知识点精讲
            ### 1. 知识点标题
            ## 重点原文摘录
            ## 课程标签
            """;

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public LlmUtils(@Value("${ai.llm.api-key}") String apiKey,
                         @Value("${ai.llm.base-url}") String baseUrl,
                         @Value("${ai.llm.model:deepseek-ai/DeepSeek-R1-Distill-Qwen-32B}") String modelName,
                         ObjectMapper objectMapper) {
        this.chatModel = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .build();
        this.objectMapper = objectMapper;
    }

    public String analyzeContent(String content) {
        if (content == null || content.isBlank()) {
            return "本节课暂无可提炼的知识点";
        }
        return chatModel.chat(SYSTEM_PROMPT + "\n\n待分析文本：\n" + content);
    }

    public AgentState.AgentPlan plan(LectureContext context) {
        try {
            String prompt = """
                    你是课程解析 Agent 中的 Planner。请先理解学习者的目标，再把它拆解成 3 到 5 个可执行的子任务。
                    每个子任务都必须仅依靠 LectureContext 中的 ASR 转写、板书/PPT 的 OCR 文本以及时间戳证据即可完成。
                    只返回 JSON：
                    {
                      "understoodGoal": "对学习者目标的明确理解",
                      "tasks": ["子任务1", "子任务2", "子任务3"]
                    }
                    LectureContext:
                    """ + objectMapper.writeValueAsString(context);
            return parseJson(chatModel.chat(prompt), AgentState.AgentPlan.class);
        } catch (Exception e) {
            throw new IllegalStateException("Agent 任务规划失败", e);
        }
    }

    public LectureChunk.ChunkSummary summarizeChunk(List<LectureContext.LectureSegment> segments) {
        try {
            String prompt = """
                    压缩以下五分钟的课程片段，保留讲师讲解的知识点、例题、结论，以及板书或 PPT 上的重要 OCR 内容。
                    只返回 JSON：
                    {
                      "segmentSummary": "不超过 200 字的片段摘要",
                      "keywords": ["关键词1", "关键词2", "关键词3"]
                    }
                    原始片段：
                    """ + objectMapper.writeValueAsString(segments);
            return parseJson(chatModel.chat(prompt), LectureChunk.ChunkSummary.class);
        } catch (Exception e) {
            throw new IllegalStateException("课程片段摘要失败", e);
        }
    }

    public AnalysisResult execute(LectureContext context,
                                  AgentState.AgentPlan plan,
                                  AgentState.CriticResult previousCritique) {
        try {
            String prompt = """
                    你是课程解析 Agent 中的 Executor。请按照 Planner 给出的计划分析 LectureContext，并生成结构化的学习笔记。
                    每条重要结论都必须绑定 LectureContext 里真实存在的 timestampMs，并标明来源是 ASR 还是 OCR。
                    禁止引入课程内容之外的事实。
                    如果存在 Critic 的反馈，必须逐项修正后再输出。

                    只返回 JSON：
                    {
                      "title": "笔记标题",
                      "conclusions": ["结论"],
                      "evidence": [
                        {"timestampMs": 120000, "source": "ASR或OCR", "content": "证据内容"}
                      ],
                      "suggestions": ["建议"]
                    }

                    Plan:
                    """ + objectMapper.writeValueAsString(plan) + """

                    PreviousCritique:
                    """ + objectMapper.writeValueAsString(previousCritique) + """

                    LectureContext:
                    """ + objectMapper.writeValueAsString(context);
            return parseJson(chatModel.chat(prompt), AnalysisResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("Agent 执行失败", e);
        }
    }

    public AgentState.CriticResult critique(LectureContext context,
                                            AgentState.AgentPlan plan,
                                            AnalysisResult result) {
        try {
            String prompt = """
                    你是课程解析 Agent 中的 Critic，只负责校验，不负责改写内容。
                    校验标准：
                    1. 产物是否覆盖了学习者目标以及 Planner 拆解出的全部子任务；
                    2. 每条重要结论是否都能在 LectureContext 中找到对应的时间戳证据；
                    3. 是否存在课程内容不支持的结论；
                    4. title、conclusions、evidence、suggestions 四个字段是否完整。

                    只有全部满足时 passed 才能为 true。
                    requiredTimestamps 填写需要重新读取或补充分析的时间戳毫秒值。
                    只返回 JSON：
                    {
                      "passed": false,
                      "feedback": ["具体修改建议"],
                      "missingRequirements": ["遗漏要求"],
                      "unsupportedClaims": ["无证据结论"],
                      "requiredTimestamps": [120000]
                    }

                    Plan:
                    """ + objectMapper.writeValueAsString(plan) + """

                    Draft:
                    """ + objectMapper.writeValueAsString(result) + """

                    LectureContext:
                    """ + objectMapper.writeValueAsString(context);
            return parseJson(chatModel.chat(prompt), AgentState.CriticResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("Critic 校验失败", e);
        }
    }

    private <T> T parseJson(String response, Class<T> type) throws Exception {
        String json = response
                .replace("```json", "")
                .replace("```", "")
                .trim();
        return objectMapper.readValue(json, type);
    }
}
