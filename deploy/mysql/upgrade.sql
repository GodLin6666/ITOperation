-- ============================================================
-- 数据库升级脚本（给「已经存在的老库」补结构）
--
-- 背景：
--   deploy/mysql/init.sql 只在 MySQL **首次初始化**时执行
--   （docker-entrypoint-initdb.d 的行为）。
--   如果你早就跑过 compose，之后代码新增了表 / 字段，
--   老库是拿不到这些改动的，后端一启动就会报：
--       Unknown column 'xxx' in 'field list'
--   并进入无限重启。
--
-- 用法（每次拉新代码后跑一次，幂等，可重复执行）：
--   docker cp deploy/mysql/upgrade.sql opsai-mysql:/tmp/upgrade.sql
--   docker exec opsai-mysql sh -c "mysql -uroot -p密码 --default-character-set=utf8mb4 opsai < /tmp/upgrade.sql"
--
-- 或者直接：
--   docker exec -i opsai-mysql sh -c "mysql -uroot -p密码 opsai" < deploy/mysql/upgrade.sql
-- ============================================================

-- ------------------------------------------------------------
-- 1. 企业域认证：sys_user 增加 auth_source 字段
--    区分本地账号（LOCAL）和域账号（LDAP）
-- ------------------------------------------------------------
SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sys_user'
      AND COLUMN_NAME = 'auth_source'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE sys_user ADD COLUMN auth_source VARCHAR(20) NOT NULL DEFAULT ''LOCAL'' COMMENT ''LOCAL/LDAP'' AFTER status',
    'SELECT ''auth_source 已存在，跳过'' AS msg');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 已有的本地账号统一标记为 LOCAL（幂等）
UPDATE sys_user SET auth_source = 'LOCAL' WHERE auth_source IS NULL OR auth_source = '';

-- ------------------------------------------------------------
-- 2. 自愈功能：self_heal_event 表
--    CREATE TABLE IF NOT EXISTS 本身幂等，老库没有就会建
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS self_heal_event (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  rule_key      VARCHAR(50)  NOT NULL COMMENT '触发的巡检规则，如 DISK_HIGH',
  title         VARCHAR(200) NOT NULL COMMENT '事件标题',
  severity      VARCHAR(20)  NOT NULL DEFAULT 'MEDIUM' COMMENT 'LOW/MEDIUM/HIGH',
  metric_name   VARCHAR(50)            COMMENT '指标名，如 disk_usage',
  metric_value  DOUBLE                 COMMENT '实测值',
  threshold     DOUBLE                 COMMENT '阈值',
  status        VARCHAR(20)  NOT NULL DEFAULT 'DETECTED' COMMENT 'DETECTED/DIAGNOSED/PROPOSED/EXECUTED/VERIFIED/FAILED/IGNORED',
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

-- ------------------------------------------------------------
-- 3. 结果自检
-- ------------------------------------------------------------
SELECT '升级完成' AS msg;
SELECT COLUMN_NAME, COLUMN_TYPE, COLUMN_DEFAULT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'auth_source';
SELECT COUNT(*) AS self_heal_event_ready
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'self_heal_event';

-- ------------------------------------------------------------
-- 3. 双平台支持：知识条目与故障单增加 platform
--    WINDOWS 桌面 / LINUX 服务器 / ANY 通用
-- ------------------------------------------------------------
SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'kb_document'
      AND COLUMN_NAME = 'platform'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE kb_document ADD COLUMN platform VARCHAR(16) NOT NULL DEFAULT ''ANY'' COMMENT ''WINDOWS/LINUX/ANY'' AFTER doc_type',
    'SELECT ''kb_document.platform 已存在，跳过'' AS msg');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'fault_ticket'
      AND COLUMN_NAME = 'platform'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE fault_ticket ADD COLUMN platform VARCHAR(16) COMMENT ''WINDOWS/LINUX/ANY'' AFTER fault_category',
    'SELECT ''fault_ticket.platform 已存在，跳过'' AS msg');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 老数据的平台统一按通用处理，避免被平台过滤掉（幂等）
UPDATE kb_document SET platform = 'ANY' WHERE platform IS NULL OR platform = '';
