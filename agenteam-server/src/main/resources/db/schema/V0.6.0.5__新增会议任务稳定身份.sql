-- 会议任务稳定身份：宿主首次创建时分配并持久化，不随标题、负责人或描述变化。
-- 本表与来源表只保存技术身份、跨会议关系与审计快照；任务业务状态（确认、执行、完成）
-- 仍以飞书督办宽表为唯一事实来源，不构成第二套可独立编辑的任务状态系统。

CREATE TABLE meeting_task_identity (
  `id` varchar(100) NOT NULL COMMENT '任务身份键（task_key），宿主分配后永久不变',
  `enterprise_id` varchar(100) NOT NULL,
  `binding_id` varchar(100) NOT NULL COMMENT '创建时的任务督办宽表绑定',
  `revision` bigint NOT NULL DEFAULT 1,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `meeting_id` varchar(191) COLLATE utf8mb4_bin NOT NULL COMMENT '首次创建来源会议编号',
  `title_current` varchar(500) NOT NULL COMMENT '当前标题快照，仅用于检索与展示',
  `title_normalized` varchar(500) NOT NULL COMMENT '规范化标题（小写、折叠空白），用于重复候选匹配，不做自动合并',
  `owner_open_id` varchar(191) DEFAULT NULL COMMENT '当前负责人 openId 快照',
  `owner_user_id` varchar(100) DEFAULT NULL COMMENT '负责人解析出的企业成员，审计用',
  `owner_name` varchar(100) DEFAULT NULL,
  `source_count` int NOT NULL DEFAULT 1 COMMENT '被多少场会议提及',
  `status` varchar(32) NOT NULL DEFAULT 'active' COMMENT 'active/merged/review',
  `created_run_id` varchar(100) DEFAULT NULL COMMENT '创建任务的分析 Run',
  `created_command_id` varchar(100) DEFAULT NULL COMMENT '创建任务的业务命令',
  PRIMARY KEY (`id`),
  UNIQUE KEY uk_meeting_task_identity_scope (enterprise_id, id),
  KEY idx_meeting_task_identity_0 (enterprise_id, meeting_id, id),
  KEY idx_meeting_task_identity_1 (enterprise_id, owner_open_id, id),
  KEY idx_meeting_task_identity_2 (enterprise_id, status, updated_at, id),
  CONSTRAINT fk_meeting_task_identity_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprise(id),
  CONSTRAINT fk_meeting_task_identity_binding FOREIGN KEY (enterprise_id, binding_id) REFERENCES meeting_binding(enterprise_id, id),
  CONSTRAINT fk_meeting_task_identity_run FOREIGN KEY (enterprise_id, created_run_id) REFERENCES agent_run(enterprise_id, id),
  CONSTRAINT ck_meeting_task_identity_revision CHECK (revision > 0),
  CONSTRAINT ck_meeting_task_identity_status CHECK (status IN ('active','merged','review')),
  CONSTRAINT ck_meeting_task_identity_source CHECK (source_count > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会议任务稳定身份索引；业务状态以飞书为准，本表不保存确认或执行状态';

CREATE TABLE meeting_task_source (
  `id` varchar(100) NOT NULL,
  `enterprise_id` varchar(100) NOT NULL,
  `task_key` varchar(100) NOT NULL COMMENT '所属任务身份键',
  `revision` bigint NOT NULL DEFAULT 1,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `meeting_id` varchar(191) COLLATE utf8mb4_bin NOT NULL COMMENT '提及该任务的会议编号',
  `meeting_title` varchar(300) DEFAULT NULL,
  `run_id` varchar(100) DEFAULT NULL COMMENT '本次提及的分析 Run',
  `command_id` varchar(100) DEFAULT NULL COMMENT '本次提及的业务命令',
  `evidence_json` json NOT NULL DEFAULT (JSON_OBJECT()) COMMENT '本次提及的证据定位快照',
  PRIMARY KEY (`id`),
  UNIQUE KEY uk_meeting_task_source_scope (enterprise_id, id),
  UNIQUE KEY uk_meeting_task_source_0 (enterprise_id, task_key, meeting_id),
  KEY idx_meeting_task_source_0 (enterprise_id, meeting_id, id),
  CONSTRAINT fk_meeting_task_source_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprise(id),
  CONSTRAINT fk_meeting_task_source_task FOREIGN KEY (enterprise_id, task_key) REFERENCES meeting_task_identity(enterprise_id, id),
  CONSTRAINT fk_meeting_task_source_run FOREIGN KEY (enterprise_id, run_id) REFERENCES agent_run(enterprise_id, id),
  CONSTRAINT ck_meeting_task_source_revision CHECK (revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='任务与会议的来源关系；同一任务跨会议再次提及时追加来源记录';
