-- CourseGist 初始化脚本
-- docker-compose 启动 MySQL 后执行：mysql -h127.0.0.1 -P3307 -uroot -proot < sql/init.sql

CREATE DATABASE IF NOT EXISTS coursegist_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE coursegist_db;

-- 用户表
CREATE TABLE IF NOT EXISTS users (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    username    VARCHAR(64)  NOT NULL COMMENT '登录账号',
    password    VARCHAR(128) NOT NULL COMMENT '密码',
    nickname    VARCHAR(64)  DEFAULT NULL COMMENT '昵称',
    avatar      VARCHAR(255) DEFAULT NULL COMMENT '头像地址',
    role        VARCHAR(16)  DEFAULT 'USER' COMMENT '角色',
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB COMMENT ='用户表';

-- 课程视频表
CREATE TABLE IF NOT EXISTS course_videos (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT       DEFAULT NULL COMMENT '上传者',
    filename        VARCHAR(255) DEFAULT NULL COMMENT '原始文件名',
    status          VARCHAR(32)  DEFAULT NULL COMMENT 'UPLOADED / PROCESSING / COMPLETED / FAILED',
    file_path       VARCHAR(512) DEFAULT NULL COMMENT 'MinIO 访问地址或本地路径',
    ai_summary      LONGTEXT COMMENT 'AI 课程精讲（Markdown）',
    transcript_text LONGTEXT COMMENT '课程讲稿全文',
    cover_url       VARCHAR(512) DEFAULT NULL COMMENT '封面地址',
    upload_time     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    KEY idx_user_id (user_id)
) ENGINE = InnoDB COMMENT ='课程视频表';
