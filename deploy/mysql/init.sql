-- ============================================================
-- 容器初始化脚本（被 docker-compose 挂载到 /docker-entrypoint-initdb.d/）
-- 与 opsai\init.sql 内容一致，唯一区别：
--   不写 CREATE DATABASE / USE，因为 compose 里的 MYSQL_DATABASE=opsai
--   已经由 MySQL 官方镜像自动建库，并且会自动选中该库。
-- ============================================================

-- 表1：知识条目（历史故障案例 / 排查手册 / 处置方案）
CREATE TABLE IF NOT EXISTS kb_document (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  title          VARCHAR(200) NOT NULL COMMENT '标题',
  device_type    VARCHAR(50)  NOT NULL COMMENT '设备类型：server/db/network/middleware/app',
  fault_category VARCHAR(50)  NOT NULL COMMENT '故障类别：cpu/mem/disk/network/db/middleware',
  doc_type       VARCHAR(20)  NOT NULL DEFAULT 'case' COMMENT 'case历史案例 manual排查手册 solution处置方案',
  content        MEDIUMTEXT   NOT NULL COMMENT '正文',
  source         VARCHAR(200) COMMENT '来源',
  status         TINYINT      NOT NULL DEFAULT 0 COMMENT '0待审核 1已发布 2已驳回',
  version        INT          NOT NULL DEFAULT 1,
  chunk_count    INT          NOT NULL DEFAULT 0,
  create_by      BIGINT,
  create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_doc_filter (status, device_type, fault_category)
) ENGINE=InnoDB COMMENT='知识条目';

-- 表2：知识分块 + 向量（RAG 的核心）
CREATE TABLE IF NOT EXISTS kb_chunk (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  doc_id      BIGINT      NOT NULL,
  chunk_no    INT         NOT NULL,
  content     TEXT        NOT NULL COMMENT '这一块的正文',
  embedding   MEDIUMTEXT  NOT NULL COMMENT 'JSON 数组形式的 1024 维向量',
  dim         INT         NOT NULL DEFAULT 1024,
  embed_model VARCHAR(50) NOT NULL DEFAULT 'bge-m3',
  create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_chunk_doc (doc_id)
) ENGINE=InnoDB COMMENT='知识分块与向量';

-- 表3：故障单（用户提交的问题）
CREATE TABLE IF NOT EXISTS fault_ticket (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  ticket_no      VARCHAR(32)  NOT NULL UNIQUE,
  title          VARCHAR(200) NOT NULL,
  device_type    VARCHAR(50),
  fault_category VARCHAR(50),
  phenomenon     TEXT COMMENT '现象描述',
  log_text       MEDIUMTEXT COMMENT '粘贴的日志原文',
  log_file       VARCHAR(255) COMMENT '上传的日志文件路径',
  metric_json    TEXT COMMENT 'CPU/内存/磁盘/网络指标 JSON',
  status         TINYINT NOT NULL DEFAULT 0 COMMENT '0待诊断 1已诊断 2已处理 3已关闭',
  create_by      BIGINT,
  create_time    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB COMMENT='故障单';

-- 表4：诊断记录（一次诊断的全过程留痕）
CREATE TABLE IF NOT EXISTS diagnose_record (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  ticket_id      BIGINT NOT NULL,
  templates_json MEDIUMTEXT COMMENT '预处理得到的日志模板',
  keywords       VARCHAR(500) COMMENT '关键词',
  hits_json      MEDIUMTEXT COMMENT '检索命中的片段和相似度',
  answer_json    MEDIUMTEXT COMMENT '大模型输出的结构化建议',
  raw_answer     MEDIUMTEXT COMMENT '大模型原始输出（调试用）',
  model_name     VARCHAR(80),
  first_token_ms INT COMMENT '首字耗时(毫秒)，论文性能指标',
  cost_ms        INT COMMENT '总耗时(毫秒)',
  adopted        TINYINT NOT NULL DEFAULT 0 COMMENT '建议是否被采纳',
  create_time    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_rec_ticket (ticket_id)
) ENGINE=InnoDB COMMENT='诊断记录';

-- 表5：用户（登录和角色权限）
CREATE TABLE IF NOT EXISTS sys_user (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  username    VARCHAR(50)  NOT NULL UNIQUE,
  password    VARCHAR(100) NOT NULL COMMENT 'BCrypt 加密后的密码',
  real_name   VARCHAR(50),
  role        VARCHAR(20)  NOT NULL DEFAULT 'engineer' COMMENT 'admin/engineer/viewer',
  status      TINYINT NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB COMMENT='用户';

-- 表6：操作日志
CREATE TABLE IF NOT EXISTS sys_log (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  username    VARCHAR(50),
  action      VARCHAR(100),
  detail      VARCHAR(500),
  ip          VARCHAR(50),
  cost_ms     INT,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB COMMENT='操作日志';

-- 表7：自愈事件
-- 状态流转：DETECTED(巡检发现) -> DIAGNOSED(已诊断) -> PROPOSED(已给出处置方案)
--           -> EXECUTED(人工确认并执行) -> VERIFIED(复检通过) / FAILED(复检未通过)
--           -> IGNORED(人工忽略)
CREATE TABLE IF NOT EXISTS self_heal_event (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  rule_key      VARCHAR(50)  NOT NULL COMMENT '触发的巡检规则，如 DISK_HIGH',
  title         VARCHAR(200) NOT NULL COMMENT '事件标题',
  severity      VARCHAR(20)  NOT NULL DEFAULT 'MEDIUM' COMMENT 'LOW/MEDIUM/HIGH',
  metric_name   VARCHAR(50)            COMMENT '指标名，如 disk_usage',
  metric_value  DOUBLE                 COMMENT '实测值',
  threshold     DOUBLE                 COMMENT '阈值',
  status        VARCHAR(20)  NOT NULL DEFAULT 'DETECTED' COMMENT '见上方状态流转',
  diagnosis     TEXT                   COMMENT 'RAG 诊断结果(JSON)',
  action_key    VARCHAR(50)            COMMENT '选中的白名单动作',
  action_plan   TEXT                   COMMENT 'dry-run 预览：执行前告诉人将要做什么',
  action_result TEXT                   COMMENT '执行结果',
  operator      VARCHAR(50)            COMMENT '审批/执行人',
  detect_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  execute_time  DATETIME,
  update_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_heal_status (status),
  KEY idx_heal_rule (rule_key)
) ENGINE=InnoDB COMMENT='自愈事件';

-- ============================================================
-- 给宿主机 / 图形化数据库工具（Navicat、DBeaver、MySQL Workbench）用的账号
--
-- 为什么不直接用 root：MySQL 官方镜像里的 root 只允许从容器内部访问，
-- 从宿主机连 3307 会报 "Access denied for user 'root'@'172.18.0.1'"。
-- 这是镜像的安全设计而不是故障，所以单独建一个权限收敛在 opsai 库内的账号。
--
-- 连接参数： 主机 127.0.0.1   端口 3307   用户 opsai   密码 opsai123   库 opsai
-- ============================================================
CREATE USER IF NOT EXISTS 'opsai'@'%' IDENTIFIED WITH caching_sha2_password BY 'opsai123';
GRANT ALL PRIVILEGES ON opsai.* TO 'opsai'@'%';
FLUSH PRIVILEGES;
