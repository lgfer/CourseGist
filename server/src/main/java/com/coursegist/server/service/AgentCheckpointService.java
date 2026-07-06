package com.coursegist.server.service;

import com.coursegist.server.dto.AgentState;
import com.coursegist.server.dto.LectureContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class AgentCheckpointService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    public LectureContext loadContext(Long videoId) {
        return read(checkpointKey(videoId), "context", LectureContext.class);
    }

    public AgentState loadResult(Long videoId, String goal) {
        return read(goalKey(videoId, goal), "result", AgentState.class);
    }

    public AgentState.AgentPlan loadPlan(Long videoId, String goal) {
        return read(goalKey(videoId, goal), "plan", AgentState.AgentPlan.class);
    }

    public void saveContext(Long videoId, LectureContext context) {
        write(checkpointKey(videoId), "context", "CONTEXT_COMPLETED", context);
    }

    public void saveResult(Long videoId, AgentState state) {
        write(goalKey(videoId, state.goal()), "result", "ANALYSIS_COMPLETED", state);
    }

    public void savePlan(Long videoId, String goal, AgentState.AgentPlan plan) {
        write(goalKey(videoId, goal), "plan", "PLAN_COMPLETED", plan);
    }

    public void saveCriticState(Long videoId, AgentState state) {
        write(goalKey(videoId, state.goal()), "criticState", "CRITIC_COMPLETED", state);
    }

    private <T> T read(String key, String field, Class<T> type) {
        try {
            Object value = redisTemplate.opsForHash().get(key, field);
            return value == null ? null : objectMapper.readValue(value.toString(), type);
        } catch (Exception e) {
            throw new IllegalStateException("读取 Agent Checkpoint 失败", e);
        }
    }

    private void write(String key, String field, String stage, Object value) {
        try {
            redisTemplate.opsForHash().put(key, field, objectMapper.writeValueAsString(value));
            redisTemplate.opsForHash().put(key, "stage", stage);
        } catch (Exception e) {
            throw new IllegalStateException("保存 Agent Checkpoint 失败", e);
        }
    }

    private String checkpointKey(Long videoId) {
        return "agent:ckpt:" + videoId;
    }

    private String goalKey(Long videoId, String goal) {
        return checkpointKey(videoId) + ":goal:" + Integer.toHexString(String.valueOf(goal).hashCode());
    }
}
