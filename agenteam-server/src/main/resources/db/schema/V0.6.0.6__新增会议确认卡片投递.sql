-- 会议确认卡片投递：任务 Outbox 确认 delivered 后才创建卡片投递任务，
-- 卡片发送拥有独立的状态、租约、退避与结果恢复，卡片失败不回滚任务同步。
-- 对账扫描按投递箱 delivered_version 推进确认轮次，进程重启后自动恢复。

ALTER TABLE meeting_confirmation
  ADD COLUMN `card_status` varchar(32) DEFAULT NULL COMMENT 'pending/sending/sent/failed/blocked/unknown/unresolved；空表示本轮无需卡片' AFTER `card_command_id`,
  ADD COLUMN `card_title` varchar(200) DEFAULT NULL COMMENT '卡片标题快照' AFTER `card_status`,
  ADD COLUMN `card_description` varchar(500) DEFAULT NULL COMMENT '卡片描述快照' AFTER `card_title`,
  ADD COLUMN `card_attempt_count` int NOT NULL DEFAULT 0 AFTER `card_description`,
  ADD COLUMN `card_max_attempts` int NOT NULL DEFAULT 6 AFTER `card_attempt_count`,
  ADD COLUMN `card_next_attempt_at` datetime(3) DEFAULT NULL AFTER `card_max_attempts`,
  ADD COLUMN `card_claim_token` varchar(100) DEFAULT NULL AFTER `card_next_attempt_at`,
  ADD COLUMN `card_lease_until` datetime(3) DEFAULT NULL AFTER `card_claim_token`,
  ADD COLUMN `card_last_error` varchar(200) DEFAULT NULL AFTER `card_lease_until`,
  ADD KEY `idx_meeting_confirmation_card` (card_status, card_next_attempt_at, id),
  ADD CONSTRAINT ck_meeting_confirmation_card CHECK (card_status IS NULL OR card_status IN
    ('pending','sending','sent','failed','blocked','unknown','unresolved')),
  ADD CONSTRAINT ck_meeting_confirmation_card_attempts CHECK (card_attempt_count >= 0 AND card_max_attempts > 0);
