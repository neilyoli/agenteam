package com.stonewu.agenteam.service.meeting;

/**
 * 会议同步的业务字段白名单。AI 只能输出此处登记的业务字段编码，由 Binding 映射到真实飞书字段；
 * 映射目标必须覆盖业务唯一键，人工字段绝不进入投递 payload。
 */
public enum MeetingFieldCode {
    // 身份与技术字段（宿主管理）
    MEETING_KEY("meeting_key", MeetingFieldWritePolicy.TECHNICAL, true),
    TASK_KEY("task_key", MeetingFieldWritePolicy.TECHNICAL, true),
    MEETING_ID("meeting_id", MeetingFieldWritePolicy.TECHNICAL, false),
    TASK_ID("task_id", MeetingFieldWritePolicy.TECHNICAL, false),
    SOURCE_APP_ID("source_app_id", MeetingFieldWritePolicy.TECHNICAL, false),
    ANALYSIS_STATUS("analysis_status", MeetingFieldWritePolicy.TECHNICAL, false),
    TASK_COUNT("task_count", MeetingFieldWritePolicy.TECHNICAL, false),
    L1_COUNT("l1_count", MeetingFieldWritePolicy.TECHNICAL, false),
    L2_COUNT("l2_count", MeetingFieldWritePolicy.TECHNICAL, false),
    L3_COUNT("l3_count", MeetingFieldWritePolicy.TECHNICAL, false),
    LAST_ANALYSIS_TIME("last_analysis_time", MeetingFieldWritePolicy.TECHNICAL, false),
    LAST_RUN_ID("last_run_id", MeetingFieldWritePolicy.TECHNICAL, false),
    SYNC_VERSION("sync_version", MeetingFieldWritePolicy.TECHNICAL, false),
    SYNC_STATUS("sync_status", MeetingFieldWritePolicy.TECHNICAL, false),
    LAST_UPDATED_AT("last_updated_at", MeetingFieldWritePolicy.TECHNICAL, false),
    TASK_VERSION("task_version", MeetingFieldWritePolicy.TECHNICAL, false),

    // 会议主表分析提议字段
    TITLE("title", MeetingFieldWritePolicy.AI_PROPOSED, false),
    TYPE("type", MeetingFieldWritePolicy.AI_PROPOSED, false),
    MEETING_CODE("meeting_code", MeetingFieldWritePolicy.AI_PROPOSED, false),
    DEPARTMENT_CODE("department_code", MeetingFieldWritePolicy.AI_PROPOSED, false),
    HOST("host", MeetingFieldWritePolicy.AI_PROPOSED, false),
    PARTICIPANTS("participants", MeetingFieldWritePolicy.AI_PROPOSED, false),
    START_TIME("start_time", MeetingFieldWritePolicy.AI_PROPOSED, false),
    END_TIME("end_time", MeetingFieldWritePolicy.AI_PROPOSED, false),
    MINUTES_URL("minutes_url", MeetingFieldWritePolicy.AI_PROPOSED, false),
    SUMMARY("summary", MeetingFieldWritePolicy.AI_PROPOSED, false),
    RECORDING_URL("recording_url", MeetingFieldWritePolicy.AI_PROPOSED, false),
    SKIP_REASON("skip_reason", MeetingFieldWritePolicy.AI_PROPOSED, false),

    // 会议督办宽表分析提议字段
    MEETING_TITLE("meeting_title", MeetingFieldWritePolicy.AI_PROPOSED, false),
    MEETING_TYPE("meeting_type", MeetingFieldWritePolicy.AI_PROPOSED, false),
    MEETING_TIME("meeting_time", MeetingFieldWritePolicy.AI_PROPOSED, false),
    SOURCE_MATERIAL("source_material", MeetingFieldWritePolicy.AI_PROPOSED, false),
    EVIDENCE_LOCATION("evidence_location", MeetingFieldWritePolicy.AI_PROPOSED, false),
    DESCRIPTION("description", MeetingFieldWritePolicy.AI_PROPOSED, false),
    L1("l1", MeetingFieldWritePolicy.AI_PROPOSED, false),
    L2("l2", MeetingFieldWritePolicy.AI_PROPOSED, false),
    DELIVERABLE("deliverable", MeetingFieldWritePolicy.AI_PROPOSED, false),
    ACCEPTANCE_CRITERIA("acceptance_criteria", MeetingFieldWritePolicy.AI_PROPOSED, false),
    OWNER("owner", MeetingFieldWritePolicy.AI_PROPOSED, false),
    OWNER_NAME("owner_name", MeetingFieldWritePolicy.AI_PROPOSED, false),
    COLLABORATORS("collaborators", MeetingFieldWritePolicy.AI_PROPOSED, false),
    OWNER_DEPARTMENT("owner_department", MeetingFieldWritePolicy.AI_PROPOSED, false),
    DUE_DATE("due_date", MeetingFieldWritePolicy.AI_PROPOSED, false),
    PRIORITY("priority", MeetingFieldWritePolicy.AI_PROPOSED, false),
    RISK_LEVEL("risk_level", MeetingFieldWritePolicy.AI_PROPOSED, false),
    UPSTREAM_TASK_REF("upstream_task_ref", MeetingFieldWritePolicy.AI_PROPOSED, false),
    UPSTREAM_COVERED("upstream_covered", MeetingFieldWritePolicy.AI_PROPOSED, false),
    UNCOVERED_UPSTREAM("uncovered_upstream", MeetingFieldWritePolicy.AI_PROPOSED, false),

    // 人工业务字段（飞书唯一事实来源，系统只读不写）
    TASK_STATUS("task_status", MeetingFieldWritePolicy.MANUAL, false);

    private final String code;
    private final MeetingFieldWritePolicy writePolicy;
    private final boolean uniqueKey;

    MeetingFieldCode(String code, MeetingFieldWritePolicy writePolicy, boolean uniqueKey) {
        this.code = code;
        this.writePolicy = writePolicy;
        this.uniqueKey = uniqueKey;
    }

    public String code() {
        return code;
    }

    public MeetingFieldWritePolicy writePolicy() {
        return writePolicy;
    }

    public boolean uniqueKey() {
        return uniqueKey;
    }

    public static MeetingFieldCode of(String code) {
        for (MeetingFieldCode value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }

    /** 各业务类型必须覆盖的唯一键业务字段编码。 */
    public static MeetingFieldCode uniqueKeyFor(MeetingBusinessType type) {
        return type == MeetingBusinessType.MEETING ? MEETING_KEY : TASK_KEY;
    }
}
