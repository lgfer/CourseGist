package com.coursegist.server.service;

import com.coursegist.server.dto.AgentState;
import com.coursegist.server.dto.LectureContext;
import com.coursegist.server.entity.CourseVideo;
import com.coursegist.server.mapper.CourseVideoMapper;
import com.coursegist.server.strategy.AiAnalysisStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class AiService {

    @Autowired
    private CourseVideoMapper courseVideoMapper;

    @Autowired
    @Qualifier("defaultAiStrategy")
    private AiAnalysisStrategy aiAnalysisStrategy;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private LectureContextService lectureContextService;

    @Autowired
    private AgentLoopService agentLoopService;

    @Autowired
    private AgentCheckpointService checkpointService;

    public void asyncAnalyze(Long videoId, String userGoal) {
        System.out.println("[worker] 加载课程记录, videoId=" + videoId);

        CourseVideo courseVideo = courseVideoMapper.selectById(videoId);
        if (courseVideo == null) return;

        try {
            AgentState agentState = checkpointService.loadResult(videoId, userGoal);
            if (agentState != null) {
                courseVideo.setAiSummary(agentState.result().toMarkdown());
                courseVideoMapper.updateById(courseVideo);
                return;
            }

            // ASR 转写与场景关键帧 OCR 并行执行，再按时间轴合并成统一上下文
            LectureContext lectureContext = checkpointService.loadContext(videoId);
            if (lectureContext == null) {
                lectureContext = lectureContextService.build(courseVideo.getFilePath(), userGoal);
                checkpointService.saveContext(videoId, lectureContext);
            } else {
                lectureContext = new LectureContext(lectureContext.source(), userGoal, lectureContext.segments());
            }
            courseVideo.setTranscriptText(lectureContext.transcriptText());

            // Planner -> Executor -> Critic 受控循环，最多两轮后强制收敛
            agentState = agentLoopService.run(videoId, lectureContext);
            courseVideo.setAiSummary(agentState.result().toMarkdown());

            courseVideoMapper.updateById(courseVideo);

            // 缓存 Key 的拼装规则需与 CourseController#getList 保持一致
            String userIdStr = (courseVideo.getUserId() == null) ? "anon" : String.valueOf(courseVideo.getUserId());
            String cacheKey = "course:list:user:" + userIdStr;

            // 结果落库后主动失效列表缓存，保证前端轮询能拿到最新状态
            Boolean deleteResult = redisTemplate.delete(cacheKey);
            System.out.println("[worker] 解析完成, 缓存失效结果=" + deleteResult + ", key=" + cacheKey);

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("[worker] 任务失败: " + e.getMessage());

            // 失败同样需要失效缓存，否则前端会一直读到中间状态
            String userIdStr = (courseVideo.getUserId() == null) ? "anon" : String.valueOf(courseVideo.getUserId());
            redisTemplate.delete("course:list:user:" + userIdStr);
            throw new IllegalStateException("AI analysis failed", e);
        }
    }

    public String followUp(Long videoId, String question) {
        LectureContext context = checkpointService.loadContext(videoId);
        if (context == null) {
            throw new IllegalStateException("该课程尚未完成 LectureContext 构建");
        }
        LectureContext followUpContext = new LectureContext(context.source(), question, context.segments());
        return agentLoopService.run(followUpContext).result().toMarkdown();
    }



    // 仅执行语音转写的轻量任务，不走 Agent 工作流
    @Async("aiTaskExecutor")
    public void asyncTranscribe(Long videoId) {
        System.out.println("[worker] 开始讲稿提取任务, videoId=" + videoId);

        CourseVideo courseVideo = courseVideoMapper.selectById(videoId);
        if (courseVideo == null) return;

        try {
            String text = aiAnalysisStrategy.transcribe(courseVideo.getFilePath());
            courseVideo.setTranscriptText(text);

            courseVideoMapper.updateById(courseVideo);

            String userIdStr = (courseVideo.getUserId() == null) ? "anon" : String.valueOf(courseVideo.getUserId());
            String cacheKey = "course:list:user:" + userIdStr;
            redisTemplate.delete(cacheKey);

            System.out.println("[worker] 讲稿提取完成, 已失效缓存 key=" + cacheKey);

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("[worker] 讲稿提取失败: " + e.getMessage());
        }
    }
}
