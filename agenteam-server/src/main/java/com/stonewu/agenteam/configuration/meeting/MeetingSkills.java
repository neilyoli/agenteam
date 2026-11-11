package com.stonewu.agenteam.configuration.meeting;

import com.stonewu.agenteam.service.skill.BuiltinSkillAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 会议闭环的内置技能：作为平台原生资源自动导入、发布并绑定到会议智能体。
 */
@Configuration
public class MeetingSkills {

    @Bean
    public BuiltinSkillAdapter meetingIntelligenceSkill() {
        return new BuiltinSkillAdapter("meeting-intelligence", "会议智能分析",
            "从会议纪要/妙记/逐字稿中提炼结构化任务候选，经校验后由宿主受控发布。", "Lightbulb", "teal",
            "一场会议结束后，需要把会议中形成的行动项、承诺与跨部门交付事项沉淀为可督办的任务，并给出负责人、证据、分类与建议截止日期。",
            "meetingId（必填）：飞书会议编号；可选上下文：已通过 meeting_search / meeting_get 获得的会议与参会人信息。",
            """
                1. 用 meeting_get 读取会议详情与参会人；用 meeting_artifact_status 确认纪要、妙记或逐字稿是否就绪。资料未就绪时停止并报告等待条件，不反复轮询。
                2. 用 minutes_get / transcript_get 分段读取资料；hasMore 为 true 时用返回的 pageToken 继续读取，直到覆盖全部内容或达到单次预算上限。
                3. 从资料中逐条识别候选任务，只提炼会议中确有依据的行动项，不凭空补造。
                4. 对每条候选任务执行：分类（L1/L2 进入督办，L3 仅记录）、证据（引用可定位来源）、负责人（用 meeting_people_resolve 映射到 openId，歧义交人工）、去重（同会议同标题同负责人合并）、上下游覆盖（标注 upstreamTaskRefs）、发布策略（L1/L2 才进入督办）。
                5. 为每条任务构造结构化候选，调用 task_validate 校验；valid 为 false 时按 violations 修正，无法修正的转人工复核（needsReview 置真）。
                6. 通过校验的 L1/L2 任务，交由宿主受控命令发布并通知负责人；本技能不直接写库、不发消息、不调用可冒充确认的回调参数。""",
            "对每条任务输出 title、description、classification、deliverable、acceptanceCriteria、owner、collaborators、departmentCode、suggestedDueDate、priority、riskLevel、evidence、upstreamTaskRefs、needsReview、reviewReasons。",
            """
                {
                  "title": "11 月底前完成对账口径统一",
                  "classification": "L2",
                  "owner": { "resolvedOpenId": "ou_abc123", "displayName": "张三", "ambiguous": false },
                  "suggestedDueDate": "2026-11-30",
                  "evidence": [
                    { "sourceType": "minutes", "sourceId": "ob_xyz", "anchor": "minutes#7",
                      "quote": "张三：11 月底前把对账口径统一到财务标准。" }
                  ],
                  "needsReview": false
                }""",
            true, List.of("meeting_management"));
    }

    @Bean
    public BuiltinSkillAdapter meetingFollowUpSkill() {
        return new BuiltinSkillAdapter("meeting-follow-up", "会议督办跟进",
            "对已进入督办的任务做周期性跟进判断，只产生建议，不直接改写任务状态或发送消息。", "Feather", "amber",
            "任务发布并完成负责人确认后，由宿主 Schedule 定时触发，判断任务是否需要跟进、催办、升级或收尾。",
            "taskId 或 taskRef（必填）：已发布任务的稳定标识；任务当前任务状态与最近一次会议资料（由宿主受控命令从飞书 Base 读取后传入）。",
            """
                1. 仅依据传入的任务状态与授权范围内的最新会议资料做判断，不自行调用写操作。
                2. 逐项评估：任务状态（待确认超时建议提醒，临近或超过截止日期仍未完成建议催办并标注风险等级）、资料变化（后续会议更新交付物或口径时建议修订并交人工复核）、收尾条件（交付物与验收标准均已满足时建议标记完成待确认）。
                3. 对每项建议标注依据来源与风险，不伪造进度、不臆断状态。""",
            "输出结构化跟进建议：followUpNeeded（布尔）、suggestions（每条含 type、reason、riskLevel、dueDate）。",
            """
                {
                  "followUpNeeded": true,
                  "suggestions": [
                    { "type": "remind", "reason": "负责人超时未确认", "riskLevel": "medium" }
                  ]
                }""",
            true, List.of("meeting_management"));
    }
}
