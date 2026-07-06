package com.coursegist.server.consumer;

import com.coursegist.server.dto.AnalysisTaskMsg;
import com.coursegist.server.entity.CourseVideo;
import com.coursegist.server.mapper.CourseVideoMapper;
import com.coursegist.server.service.AiService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@Component
// 订阅课程解析主题，消费组独立命名便于在 Dashboard 中观察堆积情况
@RocketMQMessageListener(topic = "lecture-analysis-topic", consumerGroup = "lecture-consumer-group")
public class LectureAnalysisConsumer implements RocketMQListener<AnalysisTaskMsg> {

    @Autowired
    private AiService aiService;

    @Autowired
    private CourseVideoMapper courseVideoMapper;

    // IO 密集型业务线程池，见 ThreadPoolConfig
    @Autowired
    private Executor aiTaskExecutor;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public void onMessage(AnalysisTaskMsg msg) {
        Long videoId = msg.getVideoId();
        String contentHash = msg.getContentHash();
        if (contentHash == null || !contentHash.matches("([a-f0-9]{32}|course-\\d+)")) {
            contentHash = "course-" + videoId;
        }
        String lockKey = "lock:lecture:" + contentHash;
        String activeKey = "lecture:active:" + contentHash;
        String completedKey = "lecture:done:" + videoId + ":"
                + Integer.toHexString(String.valueOf(msg.getUserGoal()).hashCode());
        System.out.println("[consumer] 收到课程解析任务, videoId=" + videoId);

        // 消费线程只负责接收与派发，耗时的解析逻辑交给业务线程池执行
        CompletableFuture.runAsync(() -> {
            System.out.println("[worker] 开始执行课程解析, videoId=" + videoId);
            RLock lock = redissonClient.getLock(lockKey);
            boolean acquired = false;
            try {
                acquired = lock.tryLock(0, -1, TimeUnit.SECONDS);
                if (!acquired) {
                    System.out.println("[worker] 相同内容的课程正在解析中，跳过重复消息: " + videoId);
                    return;
                }
                if (Boolean.TRUE.equals(redisTemplate.hasKey(completedKey))) {
                    System.out.println("[worker] 任务此前已完成，幂等跳过: " + videoId);
                    return;
                }
                aiService.asyncAnalyze(videoId, msg.getUserGoal());
                redisTemplate.opsForValue().set(completedKey, "1");
            } catch (Exception e) {
                System.err.println("[worker] 任务执行失败: " + e.getMessage());
                markAsFailed(videoId, e.getMessage());
            } finally {
                if (acquired) {
                    redisTemplate.delete(activeKey);
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            }
        }, aiTaskExecutor);
    }

    private void markAsFailed(Long id, String error) {
        CourseVideo file = courseVideoMapper.selectById(id);
        if (file != null) {
            file.setAiSummary("解析失败: " + error);
            courseVideoMapper.updateById(file);
        }
    }
}
