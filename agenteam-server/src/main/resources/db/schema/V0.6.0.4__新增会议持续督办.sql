-- 会议持续督办：复用宿主 Schedule 触发，扫描飞书任务宽表后对未确认/逾期/上游未覆盖任务催办。
-- 任务业务状态仍以飞书为唯一事实来源；meeting_follow_up 只保存提醒台账（限频/去重/证据快照），
-- meeting_follow_up_scan 只保存每次 Schedule 发生的扫描作业与结果，均不构成第二套可编辑任务状态。

CREATE TABLE meeting_follow_up (
  `id` varchar(100) NOT NULL,
  `enterprise_id` varchar(100) NOT NULL,
  `revision` bigint NOT NULL DEFAULT 1,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `binding_id` varchar(100) NOT NULL COMMENT '会议督办宽表绑定',
  `business_key` varchar(191) NOT NULL COMMENT '稳定 task_key，等于宽表唯一键值',
  `owner_user_id` varchar(100) DEFAULT NULL COMMENT '任务负责人成员；无法解析时为空',
  `owner_subject` varchar(191) DEFAULT NULL COMMENT '负责人飞书 open_id，催办卡片投递目标',
  `last_reminder_at` datetime(3) DEFAULT NULL COMMENT '最近一次实际催办成功时间',
  `next_reminder_at` datetime(3) DEFAULT NULL COMMENT '下次允许催办时间；null 表示尚未催办过，可立即催办',
  `reminder_count` int NOT NULL DEFAULT 0 COMMENT '累计成功催办次数',
  `last_evidence_json` json DEFAULT NULL COMMENT '最近一次催办携带的来源证据快照',
  `status` varchar(32) NOT NULL DEFAULT 'active' COMMENT 'active/disabled',
  PRIMARY KEY (`id`),
  UNIQUE KEY uk_meeting_follow_up_scope (enterprise_id, id),
  UNIQUE KEY uk_meeting_follow_up_key (enterprise_id, binding_id, business_key),
  KEY idx_meeting_follow_up_next (status, next_reminder_at, id),
  CONSTRAINT fk_meeting_follow_up_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprise(id),
  CONSTRAINT fk_meeting_follow_up_binding FOREIGN KEY (enterprise_id, binding_id) REFERENCES meeting_binding(enterprise_id, id),
  CONSTRAINT ck_meeting_follow_up_revision CHECK (revision > 0),
  CONSTRAINT ck_meeting_follow_up_count CHECK (reminder_count >= 0),
  CONSTRAINT ck_meeting_follow_up_status CHECK (status IN ('active','disabled'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会议任务催办台账，仅限频去重与证据快照，任务状态以飞书为准';

CREATE TABLE meeting_follow_up_scan (
  `id` varchar(100) NOT NULL,
  `enterprise_id` varchar(100) NOT NULL,
  `revision` bigint NOT NULL DEFAULT 1,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `binding_id` varchar(100) NOT NULL COMMENT '本次扫描的督办宽表绑定',
  `schedule_id` varchar(100) NOT NULL COMMENT '触发扫描的计划编号',
  `occurrence_id` varchar(100) NOT NULL COMMENT '触发扫描的发生记录编号，用于结果汇总',
  `policy_json` json NOT NULL COMMENT '扫描策略快照（绑定/频率/工作时段/跟进条件）',
  `status` varchar(32) NOT NULL DEFAULT 'queued' COMMENT 'queued/completed/failed/blocked',
  `attempt_count` int NOT NULL DEFAULT 0,
  `max_attempts` int NOT NULL DEFAULT 8,
  `next_attempt_at` datetime(3) DEFAULT NULL,
  `claim_token` varchar(100) DEFAULT NULL COMMENT '领取栅栏，过期 Worker 回写因 token 不匹配失效',
  `lease_until` datetime(3) DEFAULT NULL,
  `last_error_code` varchar(100) DEFAULT NULL,
  `result_json` json DEFAULT NULL COMMENT '扫描结果摘要（候选数/催办数/限频跳过数/失败数）',
  PRIMARY KEY (`id`),
  UNIQUE KEY uk_meeting_follow_up_scan_scope (enterprise_id, id),
  KEY idx_meeting_follow_up_scan_occurrence (enterprise_id, occurrence_id),
  KEY idx_meeting_follow_up_scan_due (status, next_attempt_at, id),
  CONSTRAINT fk_meeting_follow_up_scan_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprise(id),
  CONSTRAINT fk_meeting_follow_up_scan_binding FOREIGN KEY (enterprise_id, binding_id) REFERENCES meeting_binding(enterprise_id, id),
  CONSTRAINT ck_meeting_follow_up_scan_revision CHECK (revision > 0),
  CONSTRAINT ck_meeting_follow_up_scan_attempts CHECK (attempt_count >= 0),
  CONSTRAINT ck_meeting_follow_up_scan_status CHECK (status IN ('queued','completed','failed','blocked'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会议督办扫描作业与结果，任务业务状态以飞书为准';
