-- 会议事件驱动：订阅声明与持久收件箱。
-- 长期连接、事件路由与重试由宿主承担；插件只提供会议分析与业务校验。

-- 后台任务类型纳入会议事件队列（仅扩展允许值，不改变既有任务语义）。
-- 迁移中途失败时约束可能已被删除，按存在性保护以便修复后重跑。
SET @ck_background_job_1_exists = (
  SELECT COUNT(*) FROM information_schema.table_constraints
  WHERE constraint_schema = DATABASE()
    AND table_name = 'background_job'
    AND constraint_name = 'ck_background_job_1'
    AND constraint_type = 'CHECK'
);
SET @drop_ck_background_job_1 = IF(@ck_background_job_1_exists > 0,
  'ALTER TABLE background_job DROP CHECK ck_background_job_1', 'DO 0');
PREPARE drop_ck_background_job_1_stmt FROM @drop_ck_background_job_1;
EXECUTE drop_ck_background_job_1_stmt;
DEALLOCATE PREPARE drop_ck_background_job_1_stmt;
ALTER TABLE background_job ADD CONSTRAINT ck_background_job_1 CHECK (kind IN ('run','document_parse','file_scan','mail','notification','event_publish','export','cleanup','scheduled_action','channel_delivery','conversation_title','meeting_event'));

CREATE TABLE meeting_subscription (
  `id` varchar(100) NOT NULL,
  `enterprise_id` varchar(100) NOT NULL,
  `revision` bigint NOT NULL DEFAULT 1,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `integration_id` varchar(100) NOT NULL COMMENT '飞书应用接入，隔离企业数据与凭据',
  `event_type` varchar(80) NOT NULL COMMENT '订阅的飞书会议事件类型',
  `status` varchar(32) NOT NULL DEFAULT 'disabled' COMMENT '启用、停用或删除',
  `hire_id` varchar(100) NOT NULL COMMENT '会议分析智能体雇佣',
  `agent_version_id` varchar(100) DEFAULT NULL COMMENT '固定版本，空表示按发布策略选择',
  `plugin_version` varchar(32) NOT NULL COMMENT '订阅所针对的插件版本，升级或卸载后停用',
  `owner_user_id` varchar(100) NOT NULL COMMENT '配置者，作为受控服务身份',
  `config_json` json NOT NULL DEFAULT (JSON_OBJECT()) COMMENT '预留绑定等扩展配置',
  PRIMARY KEY (`id`),
  UNIQUE KEY uk_meeting_subscription_scope (enterprise_id, id),
  UNIQUE KEY uk_meeting_subscription_0 (enterprise_id, integration_id, event_type),
  CONSTRAINT fk_meeting_subscription_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprise(id),
  CONSTRAINT fk_meeting_subscription_1 FOREIGN KEY (enterprise_id, integration_id) REFERENCES enterprise_integration(enterprise_id, id),
  CONSTRAINT fk_meeting_subscription_member FOREIGN KEY (enterprise_id, owner_user_id) REFERENCES enterprise_member(enterprise_id, user_id),
  CONSTRAINT fk_meeting_subscription_hire FOREIGN KEY (enterprise_id, owner_user_id, hire_id) REFERENCES agent_hire(enterprise_id, user_id, id),
  CONSTRAINT fk_meeting_subscription_version FOREIGN KEY (enterprise_id, agent_version_id) REFERENCES resource_version(enterprise_id, id),
  CONSTRAINT ck_meeting_subscription_revision CHECK (revision > 0),
  CONSTRAINT ck_meeting_subscription_status CHECK (status IN ('enabled','disabled','deleted'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会议事件到会议分析智能体的受控订阅声明';

CREATE TABLE meeting_event (
  `id` varchar(100) NOT NULL,
  `enterprise_id` varchar(100) NOT NULL,
  `revision` bigint NOT NULL DEFAULT 1,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `integration_id` varchar(100) NOT NULL COMMENT '事件来源飞书应用接入',
  `event_id` varchar(191) COLLATE utf8mb4_bin NOT NULL COMMENT '平台事件编号，跨企业按接入去重',
  `event_type` varchar(80) NOT NULL,
  `meeting_id` varchar(191) COLLATE utf8mb4_bin DEFAULT NULL,
  `minute_token` varchar(191) COLLATE utf8mb4_bin DEFAULT NULL,
  `payload_json` json NOT NULL DEFAULT (JSON_OBJECT()) COMMENT '仅事件标识与元数据，不保存纪要正文',
  `status` varchar(32) NOT NULL DEFAULT 'received' COMMENT '收件箱处理状态',
  `reason_code` varchar(64) DEFAULT NULL COMMENT '跳过或失败原因',
  `run_id` varchar(100) DEFAULT NULL COMMENT '关联分析 Run，用于崩溃后找回',
  `conversation_id` varchar(100) DEFAULT NULL,
  `job_id` varchar(100) DEFAULT NULL COMMENT '后台工作编号',
  `error_code` varchar(64) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY uk_meeting_event_scope (enterprise_id, id),
  UNIQUE KEY uk_meeting_event_0 (enterprise_id, integration_id, event_id),
  KEY idx_meeting_event_0 (status, created_at, id),
  CONSTRAINT fk_meeting_event_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprise(id),
  CONSTRAINT fk_meeting_event_1 FOREIGN KEY (enterprise_id, integration_id) REFERENCES enterprise_integration(enterprise_id, id),
  CONSTRAINT fk_meeting_event_run FOREIGN KEY (enterprise_id, run_id) REFERENCES agent_run(enterprise_id, id),
  CONSTRAINT fk_meeting_event_conversation FOREIGN KEY (enterprise_id, conversation_id) REFERENCES agent_conversation(enterprise_id, id),
  CONSTRAINT ck_meeting_event_revision CHECK (revision > 0),
  CONSTRAINT ck_meeting_event_status CHECK (status IN ('received','routed','ignored','failed'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='先保存后确认的会议事件收件箱，去重与崩溃恢复关联 Run';
