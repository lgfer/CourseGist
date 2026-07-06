package com.coursegist.server.dto;

import java.io.Serializable;

/**
 * 课程解析任务消息体，经 RocketMQ 投递，需要可序列化。
 */
public class AnalysisTaskMsg implements Serializable {
    private Long videoId;
    private String action; // 例如 START_ANALYSIS
    private String contentHash;
    private String userGoal;

    public AnalysisTaskMsg() {}

    public AnalysisTaskMsg(Long videoId, String action, String contentHash, String userGoal) {
        this.videoId = videoId;
        this.action = action;
        this.contentHash = contentHash;
        this.userGoal = userGoal;
    }

    public Long getVideoId() { return videoId; }
    public void setVideoId(Long videoId) { this.videoId = videoId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public String getUserGoal() { return userGoal; }
    public void setUserGoal(String userGoal) { this.userGoal = userGoal; }
}
