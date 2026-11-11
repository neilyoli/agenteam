-- 会议资料延迟就绪的持久等待与恢复：收件箱新增 waiting 状态与 wait_until。
-- meeting.* 结束事件在纪要/妙记/逐字稿就绪前不创建分析 Run，落 waiting 持久等待，
-- 由资料就绪事件（minutes.* 携带 minute_token）或后台任务退避重试恢复，不在 Worker 内忙等。

-- 迁移中途失败时约束可能已被删除，按存在性保护以便修复后重跑。
SET @ck_meeting_event_status_exists = (
  SELECT COUNT(*) FROM information_schema.table_constraints
  WHERE constraint_schema = DATABASE()
    AND table_name = 'meeting_event'
    AND constraint_name = 'ck_meeting_event_status'
    AND constraint_type = 'CHECK'
);
SET @drop_ck_meeting_event_status = IF(@ck_meeting_event_status_exists > 0,
  'ALTER TABLE meeting_event DROP CHECK ck_meeting_event_status', 'DO 0');
PREPARE drop_ck_meeting_event_status_stmt FROM @drop_ck_meeting_event_status;
EXECUTE drop_ck_meeting_event_status_stmt;
DEALLOCATE PREPARE drop_ck_meeting_event_status_stmt;
ALTER TABLE meeting_event ADD CONSTRAINT ck_meeting_event_status CHECK (status IN ('received','routed','ignored','failed','waiting'));

ALTER TABLE meeting_event ADD COLUMN `wait_until` datetime(3) DEFAULT NULL COMMENT '资料就绪前等待截止时间，waiting 状态用于恢复' AFTER `error_code`;
