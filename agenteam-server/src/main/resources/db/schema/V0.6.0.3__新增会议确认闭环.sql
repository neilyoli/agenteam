-- 会议确认闭环：任务负责人确认卡片与可信回调。
-- 确认状态以飞书任务宽表为唯一事实来源；本表只是版本与身份校验的技术索引，
-- 以及卡片投递与回调去重的执行快照，不构成第二套可独立编辑的任务状态。

CREATE TABLE meeting_confirmation (
  `id` varchar(100) NOT NULL,
  `enterprise_id` varchar(100) NOT NULL,
  `revision` bigint NOT NULL DEFAULT 1,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `binding_id` varchar(100) NOT NULL COMMENT '会议督办宽表绑定',
  `business_key` varchar(191) NOT NULL COMMENT '稳定 task_key，等于宽表唯一键值',
  `task_version` bigint NOT NULL DEFAULT 1 COMMENT '当前确认轮对应的任务版本，旧卡片版本不匹配即拒绝',
  `owner_user_id` varchar(100) DEFAULT NULL COMMENT '解析出的负责人成员；歧义或未绑定则不发卡',
  `owner_subject` varchar(191) DEFAULT NULL COMMENT '负责人飞书 open_id，卡片投递目标与回调身份校验',
  `status` varchar(32) NOT NULL DEFAULT 'pending' COMMENT 'pending/confirmed/needs_revision/rejected/unresolved',
  `confirm_comment` varchar(1000) DEFAULT NULL COMMENT '负责人意见（需调整或拒绝）',
  `card_message_id` varchar(191) DEFAULT NULL COMMENT '已发送卡片的平台消息编号，用于校验回调来源',
  `card_command_id` varchar(100) DEFAULT NULL COMMENT '卡片投递去重命令编号',
  `last_event_id` varchar(191) DEFAULT NULL COMMENT '最近一次已处理回调事件编号，用于重复回调幂等',
  `run_id` varchar(100) DEFAULT NULL COMMENT '关联分析 Run，用于审计与找回',
  `config_json` json NOT NULL DEFAULT (JSON_OBJECT()) COMMENT '预留（确认卡片标题/描述快照）',
  PRIMARY KEY (`id`),
  UNIQUE KEY uk_meeting_confirmation_scope (enterprise_id, id),
  UNIQUE KEY uk_meeting_confirmation_0 (enterprise_id, binding_id, business_key),
  KEY idx_meeting_confirmation_0 (status, updated_at, id),
  CONSTRAINT fk_meeting_confirmation_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprise(id),
  CONSTRAINT fk_meeting_confirmation_binding FOREIGN KEY (enterprise_id, binding_id) REFERENCES meeting_binding(enterprise_id, id),
  CONSTRAINT fk_meeting_confirmation_run FOREIGN KEY (enterprise_id, run_id) REFERENCES agent_run(enterprise_id, id),
  CONSTRAINT ck_meeting_confirmation_revision CHECK (revision > 0),
  CONSTRAINT ck_meeting_confirmation_status CHECK (status IN ('pending','confirmed','needs_revision','rejected','unresolved'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会议任务确认的版本与身份技术索引，确认状态以飞书为准';
