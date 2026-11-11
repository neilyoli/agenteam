package com.stonewu.agenteam.service.meeting;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.meeting.MeetingBindingMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.integration.entity.UserChannelBindingRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingBindingRow;
import com.stonewu.agenteam.model.meeting.request.MeetingSyncCommand;
import com.stonewu.agenteam.model.plugin.entity.BuiltinExecutionContext;
import com.stonewu.agenteam.service.chat.ChatIdentityService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 会议任务发布的宿主二次校验与受控入队。不信任模型或插件先前的校验结果：
 * 身份、Run 关联、订阅快照、任务绑定、负责人绑定、参会人归属、证据与截止日期
 * 全部重新校验；技术字段由宿主构造，模型参数不透传任意字段。返回 queued 只表示
 * 已进入飞书同步投递箱，不声称飞书已写入。
 */
@Service
public class MeetingTaskPublicationService {
    private static final DateTimeFormatter FEISHU_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> EVIDENCE_SOURCE_TYPES = Set.of("minutes", "transcript");
    private static final int EVIDENCE_LOCATION_MAX = 500;

    private final MeetingBindingMapper bindings;
    private final MeetingSyncCommandService commands;
    private final MeetingRunExecutionPolicy runPolicy;
    private final MeetingTaskIdentityService taskKeys;
    private final ChatIdentityService channelIdentity;
    private final ResourceJson json;

    public MeetingTaskPublicationService(MeetingBindingMapper bindings, MeetingSyncCommandService commands,
                                         MeetingRunExecutionPolicy runPolicy,
                                         MeetingTaskIdentityService taskKeys, ChatIdentityService channelIdentity,
                                         ResourceJson json) {
        this.bindings = bindings;
        this.commands = commands;
        this.runPolicy = runPolicy;
        this.taskKeys = taskKeys;
        this.channelIdentity = channelIdentity;
        this.json = json;
    }

    public JsonNode publish(BuiltinExecutionContext context, JsonNode args) {
        var run = context.run();
        MeetingBindingRow taskBinding;
        String integrationId;
        String fixedMeetingId = null;
        boolean revision = MeetingRunExecutionPolicy.REVISION_MODE.equals(run.mode());
        if (MeetingRunExecutionPolicy.MODE.equals(run.mode()) || revision) {
            // 自动路径：Run、事件/修订关联、订阅、插件版本、绑定与执行身份全部重新校验。
            var authorization = runPolicy.requireAutoPublish(run);
            taskBinding = authorization.taskBinding();
            integrationId = authorization.event() != null ? authorization.event().getIntegrationId()
                : taskBinding.getIntegrationId();
            fixedMeetingId = run.executionConfig().path("meetingId").asText(null);
        } else if ("interactive".equals(run.mode())) {
            // 交互路径：写入前已经过原有工具审批；绑定仍由宿主解析，不从模型参数选择。
            taskBinding = requireTaskBinding(run.enterpriseId());
            integrationId = taskBinding.getIntegrationId();
        } else {
            throw new ApiException(HttpStatus.CONFLICT, "MEETING_PUBLISH_MODE_UNSUPPORTED",
                "只有普通对话、会议事件分析与任务修订可以发布会议任务。");
        }

        Request request = parse(args);
        if ("L3".equals(request.classification())) {
            return skipped("L3 为部门内部日常事项，不进入督办宽表，本次未发布。");
        }
        if (request.taskTitle() == null || request.taskTitle().isBlank()) {
            throw ApiException.invalidField("taskTitle", "任务标题不能为空。");
        }
        if (revision && (request.existingTaskKey() == null || request.existingTaskKey().isBlank())) {
            throw ApiException.invalidField("existingTaskKey", "任务修订必须携带既有任务编号重新发布。");
        }
        if (MeetingRunExecutionPolicy.MODE.equals(run.mode())) {
            // 会议事件 Run 的会议编号由宿主固定的收件箱数据决定，不允许参数覆盖。
            if (fixedMeetingId == null || fixedMeetingId.isBlank()) {
                throw mismatch("会议事件缺少固定会议编号，不能自动发布。");
            }
            if (!fixedMeetingId.equals(request.meetingId())) {
                throw ApiException.invalidField("meetingId", "会议编号必须与当前分析的会议一致。");
            }
            if (request.participantOpenIds().isEmpty()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MEETING_OWNER_NOT_PARTICIPANT",
                    "自动发布必须提供会议参会人清单，用于校验负责人归属。");
            }
            if (!request.participantOpenIds().contains(request.ownerOpenId())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MEETING_OWNER_NOT_PARTICIPANT",
                    "负责人不属于该会议参会人，不能自动指派；请确认负责人或转人工复核。");
            }
        }
        LocalDate dueDate = parseDueDate(request.dueDate());
        validateEvidence(request.evidence());
        taskKeys.requireUpstreamReferences(run.enterpriseId(), request.upstreamTaskRefs());

        var owner = resolveOwner(run.enterpriseId(), integrationId, request.ownerOpenId());
        var identity = taskKeys.resolve(run.enterpriseId(), taskBinding.getId(),
            new MeetingTaskIdentityService.IdentityRequest(request.existingTaskKey(), request.taskTitle(),
                request.ownerOpenId(), owner.getUserId(), request.ownerName(), request.meetingId(),
                request.meetingTitle(), evidenceSnapshot(request.evidence()), run.id(), context.operationId(),
                !revision));
        // 修订发布沿用任务身份固定的首次来源会议编号，不采用模型参数覆盖。
        String taskKey = identity.getId();
        String meetingId = revision ? identity.getMeetingId() : request.meetingId();

        Map<String, Object> fields = buildFields(taskBinding, taskKey, request, dueDate, meetingId);
        var result = commands.publish(run.enterpriseId(),
            new MeetingSyncCommand(taskBinding.getId(), fields, context.operationId(), run.id()));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "queued");
        response.put("businessKey", result.businessKey());
        response.put("commandId", result.commandId());
        response.put("content", "任务已进入飞书同步队列，尚未确认飞书写入完成；负责人确认卡片将在任务写入成功后投递。");
        response.put("isError", false);
        return json.tree(response);
    }

    /** 解析并规整模型参数；只读取声明过的字段，不透传任意键。 */
    private Request parse(JsonNode args) {
        List<EvidenceItem> evidence = new ArrayList<>();
        for (JsonNode item : args.path("evidence")) {
            evidence.add(new EvidenceItem(text(item, "sourceType"), text(item, "sourceId"),
                text(item, "anchor"), text(item, "quote")));
        }
        Set<String> participants = new LinkedHashSet<>();
        for (JsonNode item : args.path("participantOpenIds")) {
            if (!item.asText("").isBlank()) {
                participants.add(item.asText().strip());
            }
        }
        List<String> collaborators = new ArrayList<>();
        for (JsonNode item : args.path("collaboratorOpenIds")) {
            if (!item.asText("").isBlank()) {
                collaborators.add(item.asText().strip());
            }
        }
        List<String> upstream = new ArrayList<>();
        for (JsonNode item : args.path("upstreamTaskRefs")) {
            if (!item.asText("").isBlank()) {
                upstream.add(item.asText().strip());
            }
        }
        return new Request(text(args, "meetingId"), text(args, "meetingTitle"), text(args, "meetingTime"),
            text(args, "taskTitle"), text(args, "description"), text(args, "classification"),
            text(args, "deliverable"), text(args, "acceptanceCriteria"), text(args, "ownerOpenId"),
            text(args, "ownerName"), text(args, "dueDate"), text(args, "existingTaskKey"), List.copyOf(evidence),
            Set.copyOf(participants), text(args, "departmentCode"), List.copyOf(collaborators),
            text(args, "priority"), text(args, "riskLevel"), List.copyOf(upstream), text(args, "sourceMaterial"));
    }

    private record Request(String meetingId, String meetingTitle, String meetingTime, String taskTitle,
                           String description, String classification, String deliverable,
                           String acceptanceCriteria, String ownerOpenId, String ownerName, String dueDate,
                           String existingTaskKey, List<EvidenceItem> evidence, Set<String> participantOpenIds,
                           String departmentCode, List<String> collaboratorOpenIds, String priority,
                           String riskLevel, List<String> upstreamTaskRefs, String sourceMaterial) {
    }

    private record EvidenceItem(String sourceType, String sourceId, String anchor, String quote) {
    }

    /** 证据精简快照，保存到任务来源记录用于跨会议审计。 */
    private List<Map<String, String>> evidenceSnapshot(List<EvidenceItem> evidence) {
        return evidence.stream().map(item -> {
            Map<String, String> snapshot = new LinkedHashMap<>();
            snapshot.put("sourceType", item.sourceType());
            if (item.sourceId() != null) {
                snapshot.put("sourceId", item.sourceId());
            }
            snapshot.put("anchor", item.anchor());
            if (item.quote() != null) {
                snapshot.put("quote", item.quote());
            }
            return snapshot;
        }).toList();
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? null : value.strip();
    }

    private void validateEvidence(List<EvidenceItem> evidence) {
        if (evidence.isEmpty()) {
            throw ApiException.invalidField("evidence", "进入督办的 L1/L2 任务必须提供可定位证据。");
        }
        for (EvidenceItem item : evidence) {
            if (item.sourceType() == null || !EVIDENCE_SOURCE_TYPES.contains(item.sourceType())) {
                throw ApiException.invalidField("evidence", "证据来源只能是纪要或逐字稿。");
            }
            if (item.anchor() == null) {
                throw ApiException.invalidField("evidence", "证据必须提供可定位锚点。");
            }
        }
    }

    private LocalDate parseDueDate(String value) {
        if (value == null) {
            throw ApiException.invalidField("dueDate", "进入督办的 L1/L2 任务必须提供截止日期。");
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException failure) {
            throw ApiException.invalidField("dueDate", "截止日期必须是 YYYY-MM-DD。");
        }
    }

    /** 负责人必须解析到当前企业、当前飞书接入的有效成员绑定；歧义时拒绝而不是猜测。 */
    private UserChannelBindingRow resolveOwner(String enterpriseId, String integrationId, String ownerOpenId) {
        if (ownerOpenId == null) {
            throw ApiException.invalidField("ownerOpenId", "进入督办的 L1/L2 任务必须有已解析负责人。");
        }
        var binding = channelIdentity.find(enterpriseId, integrationId, "feishu_open_id", ownerOpenId);
        if (binding.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MEETING_OWNER_UNBOUND",
                "负责人尚未绑定企业飞书账号，不能自动发布或发送确认卡片；请先完成绑定或转人工复核。");
        }
        return binding.get();
    }

    /**
     * 构造投递字段：技术字段由宿主生成，AI 提议字段按绑定映射过滤；
     * 未映射的可选字段不进入投递 payload，唯一键与标题必须映射。
     */
    private Map<String, Object> buildFields(MeetingBindingRow binding, String taskKey, Request request,
                                            LocalDate dueDate, String meetingId) {
        Map<String, String> mapping = commands.bindingMapping(binding);
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put(MeetingFieldCode.TASK_KEY.code(), taskKey);
        candidate.put(MeetingFieldCode.MEETING_ID.code(), meetingId);
        candidate.put(MeetingFieldCode.TITLE.code(), request.taskTitle());
        candidate.put(MeetingFieldCode.DESCRIPTION.code(), request.description());
        candidate.put(MeetingFieldCode.MEETING_TITLE.code(), request.meetingTitle());
        candidate.put(MeetingFieldCode.MEETING_TIME.code(), request.meetingTime());
        candidate.put(MeetingFieldCode.DUE_DATE.code(), dueDate.atStartOfDay().format(FEISHU_TIME));
        candidate.put(MeetingFieldCode.OWNER.code(), List.of(request.ownerOpenId()));
        candidate.put(MeetingFieldCode.OWNER_NAME.code(), request.ownerName());
        if ("L1".equals(request.classification())) {
            candidate.put(MeetingFieldCode.L1.code(), true);
        } else if ("L2".equals(request.classification())) {
            candidate.put(MeetingFieldCode.L2.code(), true);
        }
        candidate.put(MeetingFieldCode.DELIVERABLE.code(), request.deliverable());
        candidate.put(MeetingFieldCode.ACCEPTANCE_CRITERIA.code(), request.acceptanceCriteria());
        candidate.put(MeetingFieldCode.OWNER_DEPARTMENT.code(), request.departmentCode());
        if (!request.collaboratorOpenIds().isEmpty()) {
            candidate.put(MeetingFieldCode.COLLABORATORS.code(), request.collaboratorOpenIds());
        }
        candidate.put(MeetingFieldCode.PRIORITY.code(), request.priority());
        candidate.put(MeetingFieldCode.RISK_LEVEL.code(), request.riskLevel());
        if (!request.upstreamTaskRefs().isEmpty()) {
            candidate.put(MeetingFieldCode.UPSTREAM_TASK_REF.code(), String.join("；", request.upstreamTaskRefs()));
        }
        candidate.put(MeetingFieldCode.EVIDENCE_LOCATION.code(), evidenceLocation(request.evidence()));
        candidate.put(MeetingFieldCode.SOURCE_MATERIAL.code(), request.sourceMaterial());

        Map<String, Object> fields = new LinkedHashMap<>();
        for (var entry : candidate.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            boolean mapped = mapping.containsKey(entry.getKey());
            boolean required = entry.getKey().equals(MeetingFieldCode.TASK_KEY.code())
                || entry.getKey().equals(MeetingFieldCode.TITLE.code());
            if (!mapped && required) {
                throw new ApiException(HttpStatus.CONFLICT, "MEETING_FIELD_NOT_MAPPED",
                    "任务绑定未映射必需业务字段：" + entry.getKey() + "，请先修复绑定配置。");
            }
            if (mapped) {
                fields.put(entry.getKey(), entry.getValue());
            }
        }
        return fields;
    }

    private String evidenceLocation(List<EvidenceItem> evidence) {
        StringBuilder location = new StringBuilder();
        for (EvidenceItem item : evidence) {
            if (!location.isEmpty()) {
                location.append("；");
            }
            location.append("minutes".equals(item.sourceType()) ? "纪要" : "逐字稿");
            if (item.sourceId() != null) {
                location.append(item.sourceId());
            }
            location.append("#").append(item.anchor());
        }
        return location.length() > EVIDENCE_LOCATION_MAX ? location.substring(0, EVIDENCE_LOCATION_MAX)
            : location.toString();
    }

    private MeetingBindingRow requireTaskBinding(String enterpriseId) {
        MeetingBindingRow binding = bindings.selectOne(new LambdaQueryWrapper<MeetingBindingRow>()
            .eq(MeetingBindingRow::getEnterpriseId, enterpriseId)
            .eq(MeetingBindingRow::getBusinessType, MeetingBusinessType.TASK.code()));
        if (binding == null || !"enabled".equals(binding.getStatus())) {
            throw ResourceAuthorizationService.unavailable();
        }
        return binding;
    }

    private JsonNode skipped(String reason) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "skipped");
        response.put("content", reason);
        response.put("isError", false);
        return json.tree(response);
    }

    private static ApiException mismatch(String reason) {
        return new ApiException(HttpStatus.FORBIDDEN, "MEETING_RUN_AUTHORIZATION_INVALID", reason);
    }
}
