package com.coursegist.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("course_videos")
public class CourseVideo {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;          // 上传者

    private String filename;
    private String status;        //UPLOADED, COMPLETED
    private String filePath;

    // 解析产物
    private String aiSummary;
    private String transcriptText;
    private String coverUrl;

    // 上传时间由数据库默认值填充
    private LocalDateTime uploadTime;
}