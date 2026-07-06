package com.coursegist.server.service;

import com.coursegist.server.dto.AgentState;
import com.coursegist.server.dto.AnalysisResult;
import com.coursegist.server.dto.LectureContext;
import com.coursegist.server.utils.LlmUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AgentLoopService {

    private static final int MAX_ROUNDS = 2;

    @Autowired
    private LlmUtils llmUtils;

    @Autowired
    private LongLectureContextService longLectureContextService;

    @Autowired
    private AgentCheckpointService checkpointService;

    public AgentState run(LectureContext context) {
        return run(null, context);
    }

    public AgentState run(Long videoId, LectureContext context) {
        LectureContext relevantContext = longLectureContextService.selectRelevant(context);
        AgentState.AgentPlan plan = videoId == null ? null
                : checkpointService.loadPlan(videoId, relevantContext.userGoal());
        if (plan == null) {
            plan = llmUtils.plan(relevantContext);
            if (videoId != null) checkpointService.savePlan(videoId, relevantContext.userGoal(), plan);
        }
        AgentState state = new AgentState(relevantContext.userGoal(), plan, null, null, 0);

        for (int round = 1; round <= MAX_ROUNDS; round++) {
            AnalysisResult result = llmUtils.execute(relevantContext, plan, state.critique());
            AgentState.CriticResult critique = llmUtils.critique(relevantContext, plan, result);
            state = new AgentState(relevantContext.userGoal(), plan, result, critique, round);

            if (critique.passed()) {
                break;
            }
            if (videoId != null) checkpointService.saveCriticState(videoId, state);
        }
        if (videoId != null) checkpointService.saveResult(videoId, state);
        return state;
    }
}
