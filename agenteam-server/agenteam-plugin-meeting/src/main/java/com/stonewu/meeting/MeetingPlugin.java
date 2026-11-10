package com.stonewu.meeting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.meeting.api.MeetingApiClient;
import com.stonewu.meeting.api.MeetingApiException;
import com.stonewu.meeting.domain.Meeting;
import com.stonewu.meeting.domain.MeetingArtifact;
import com.stonewu.meeting.domain.MeetingParticipant;
import com.stonewu.meeting.domain.OwnerCandidate;
import com.stonewu.meeting.domain.TaskClassification;
import com.stonewu.meeting.domain.TaskEvidence;
import com.stonewu.meeting.domain.TaskProposal;
import com.stonewu.meeting.service.MeetingEvidenceNormalizer;
import com.stonewu.meeting.service.MeetingPeopleResolver;
import com.stonewu.meeting.service.MeetingReadService;
import com.stonewu.meeting.service.MeetingResultValidator;
import com.stonewu.plugin.api.JavaPluginContext;
import com.stonewu.plugin.api.JavaToolPlugin;
import com.stonewu.tool.api.ToolSpec;
import org.pf4j.Extension;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会议智能分析插件：读取飞书会议资料、解析负责人、校验结构化任务候选。
 * 仅暴露只读操作；任务发布、通知与确认回调等写入由宿主受控命令承担。
 * 实例按调用创建，不保留跨调用的可变状态；凭据只在本次调用中注入，禁止落入结果或日志。
 */
@Extension
public final class MeetingPlugin implements JavaToolPlugin {
    private static final Logger LOG = System.getLogger(MeetingPlugin.class.getName());

    private final ObjectMapper json = new ObjectMapper();
    private final MeetingPeopleResolver peopleResolver = new MeetingPeopleResolver();
    private final MeetingEvidenceNormalizer evidenceNormalizer = new MeetingEvidenceNormalizer();
    private final MeetingResultValidator validator = new MeetingResultValidator();

    @Override
    public List<ToolSpec> tools() {
        return MeetingToolDefinitions.tools();
    }

    @Override
    public Map<String, Object> invoke(String tool, Map<String, Object> arguments,
                                      Map<String, Object> configuration, JavaPluginContext context) {
        context.cancellation().requireActive();
        try {
            return switch (tool) {
                case "meeting_search" -> search(arguments, configuration, context);
                case "meeting_get" -> get(arguments, configuration, context);
                case "meeting_artifact_status" -> artifactStatus(arguments, configuration, context);
                case "minutes_get" -> minutes(arguments, configuration, context);
                case "transcript_get" -> transcript(arguments, configuration, context);
                case "meeting_people_resolve" -> peopleResolve(arguments);
                case "task_validate" -> taskValidate(arguments);
                default -> error("UNKNOWN_TOOL", "此插件不支持请求的工具。");
            };
        } catch (MeetingError invalid) {
            LOG.log(Level.WARNING, "会议工具调用失败，执行编号 " + context.runId(), sanitizedFailure(invalid));
            return error(invalid.code(), invalid.getMessage());
        } catch (MeetingApiException api) {
            LOG.log(Level.WARNING, "飞书接口调用失败，执行编号 " + context.runId(), sanitizedFailure(api));
            return error(api.kind().name(), api.getMessage());
        } catch (IllegalArgumentException bad) {
            LOG.log(Level.WARNING, "会议工具参数错误，执行编号 " + context.runId(), sanitizedFailure(bad));
            return error("INVALID_ARGUMENT", bad.getMessage());
        }
    }

    private Map<String, Object> search(Map<String, Object> args, Map<String, Object> config, JavaPluginContext ctx) {
        var read = readService(config);
        List<Meeting> meetings = read.search(string(args, "startTime"), string(args, "endTime"),
            string(args, "meetingNo"), string(args, "pageToken"), integer(args, "pageSize", 20, 1, 100), ctx);
        var result = new LinkedHashMap<String, Object>();
        result.put("content", "共找到 " + meetings.size() + " 场会议。");
        result.put("isError", false);
        result.put("meetings", meetings.stream().map(MeetingPlugin::meetingMap).toList());
        return result;
    }

    private Map<String, Object> get(Map<String, Object> args, Map<String, Object> config, JavaPluginContext ctx) {
        var read = readService(config);
        String id = requiredString(args, "meetingId");
        Meeting meeting = read.get(id, ctx);
        List<MeetingParticipant> participants = read.participants(id, null, 100, ctx);
        var result = new LinkedHashMap<String, Object>();
        result.put("content", "已读取会议及其参会人。");
        result.put("isError", false);
        result.put("meeting", meetingMap(meeting));
        result.put("participants", participants.stream().map(MeetingPlugin::participantMap).toList());
        return result;
    }

    private Map<String, Object> artifactStatus(Map<String, Object> args, Map<String, Object> config, JavaPluginContext ctx) {
        var read = readService(config);
        MeetingArtifact artifact = read.artifact(string(args, "meetingId"), string(args, "minuteToken"), ctx);
        var result = new LinkedHashMap<String, Object>();
        result.put("content", artifact.anyReady() ? "会议资料已就绪。" : "会议资料尚未就绪，请稍后重试。");
        result.put("isError", false);
        result.put("artifact", artifactMap(artifact));
        return result;
    }

    private Map<String, Object> minutes(Map<String, Object> args, Map<String, Object> config, JavaPluginContext ctx) {
        var read = readService(config);
        var result = new LinkedHashMap<String, Object>();
        result.put("content", "已读取纪要分段。");
        result.put("isError", false);
        result.putAll(read.minutes(requiredString(args, "minuteToken"), string(args, "pageToken"),
            integer(args, "pageSize", 20, 1, 50), ctx));
        return result;
    }

    private Map<String, Object> transcript(Map<String, Object> args, Map<String, Object> config, JavaPluginContext ctx) {
        var read = readService(config);
        var result = new LinkedHashMap<String, Object>();
        result.put("content", "已读取逐字稿分段。");
        result.put("isError", false);
        result.putAll(read.transcript(requiredString(args, "minuteToken"), string(args, "pageToken"),
            integer(args, "pageSize", 20, 1, 50), ctx));
        return result;
    }

    private Map<String, Object> peopleResolve(Map<String, Object> args) {
        List<MeetingParticipant> participants = participants(args.get("participants"));
        if (participants.isEmpty()) {
            throw MeetingError.invalidArgument("请提供参会人列表 participants");
        }
        OwnerCandidate resolved = peopleResolver.resolve(participants, string(args, "nameHint"),
            string(args, "openIdHint"), string(args, "userIdHint"));
        var result = new LinkedHashMap<String, Object>();
        result.put("content", resolved.ambiguous() ? "负责人存在歧义，需人工确认。" : "已解析负责人。");
        result.put("isError", false);
        result.put("resolved", ownerMap(resolved));
        result.put("candidates", peopleResolver.candidates(participants).stream()
            .map(MeetingPlugin::ownerMap).toList());
        return result;
    }

    private Map<String, Object> taskValidate(Map<String, Object> args) {
        String title = requiredString(args, "title");
        TaskClassification classification = TaskClassification.fromCode(string(args, "classification"));
        OwnerCandidate owner = owner(args.get("owner"));
        LocalDate dueDate = date(args.get("suggestedDueDate"));
        boolean needsReview = bool(args, "needsReview");
        List<String> reviewReasons = strings(args, "reviewReasons");
        List<TaskEvidence> evidence = evidence(args.get("evidence"));

        TaskProposal proposal = new TaskProposal(title, null, classification, null, null, owner,
            List.of(), null, dueDate, null, null, evidence, List.of(), needsReview, reviewReasons);
        var outcome = validator.validate(proposal);

        var result = new LinkedHashMap<String, Object>();
        result.put("content", outcome.valid() ? "任务候选通过校验。"
            : "任务候选未通过校验：" + String.join("；", outcome.violations()));
        result.put("isError", false);
        result.put("valid", outcome.valid());
        result.put("violations", outcome.violations());
        result.put("needsReview", outcome.needsReview());
        return result;
    }

    private MeetingReadService readService(Map<String, Object> config) {
        int timeoutSeconds = integer(config, "timeoutSeconds", 20, 5, 60);
        var client = new MeetingApiClient(json, MeetingApiClient.defaultBaseUrl(), Duration.ofSeconds(timeoutSeconds));
        return new MeetingReadService(client);
    }

    private List<TaskEvidence> evidence(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<TaskEvidence> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            result.add(evidenceNormalizer.normalize(
                str(map, "sourceType"), str(map, "sourceId"), str(map, "anchor"), str(map, "quote"),
                intOrNull(map, "paragraphIndex"), intOrNull(map, "startOffset"), intOrNull(map, "endOffset")));
        }
        return result;
    }

    private OwnerCandidate owner(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        return new OwnerCandidate(str(map, "resolvedOpenId"), str(map, "resolvedUserId"),
            str(map, "displayName"), bool(map, "ambiguous"), strings(map, "reasons"));
    }

    private List<MeetingParticipant> participants(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<MeetingParticipant> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            result.add(new MeetingParticipant(str(map, "openId"), str(map, "unionId"), str(map, "name"),
                str(map, "userId"), boolOrNull(map, "isHost"), str(map, "role")));
        }
        return result;
    }

    private static Map<String, Object> meetingMap(Meeting m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("meetingId", m.meetingId());
        map.put("meetingNo", m.meetingNo());
        map.put("topic", m.topic());
        map.put("hostOpenId", m.hostOpenId());
        map.put("hostName", m.hostName());
        map.put("startTime", m.startTime() == null ? null : m.startTime().toString());
        map.put("endTime", m.endTime() == null ? null : m.endTime().toString());
        map.put("calendarType", m.calendarType());
        map.put("status", m.status());
        return map;
    }

    private static Map<String, Object> participantMap(MeetingParticipant p) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("openId", p.openId());
        map.put("unionId", p.unionId());
        map.put("name", p.name());
        map.put("userId", p.userId());
        map.put("isHost", p.isHost());
        map.put("role", p.role());
        return map;
    }

    private static Map<String, Object> ownerMap(OwnerCandidate o) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("resolvedOpenId", o.resolvedOpenId());
        map.put("resolvedUserId", o.resolvedUserId());
        map.put("displayName", o.displayName());
        map.put("ambiguous", o.ambiguous());
        map.put("reasons", o.reasons());
        return map;
    }

    private static Map<String, Object> artifactMap(MeetingArtifact a) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("minuteToken", a.minuteToken());
        map.put("minutesReady", a.minutesReady());
        map.put("transcriptReady", a.transcriptReady());
        map.put("noteReady", a.noteReady());
        map.put("noteUrl", a.noteUrl());
        map.put("minutesWordCount", a.minutesWordCount());
        map.put("transcriptWordCount", a.transcriptWordCount());
        map.put("anyReady", a.anyReady());
        return map;
    }

    private static Map<String, Object> error(String code, String message) {
        var map = new LinkedHashMap<String, Object>();
        map.put("content", message);
        map.put("isError", true);
        map.put("code", code);
        return map;
    }

    private static String requiredString(Map<String, Object> args, String key) {
        String value = string(args, key);
        if (value == null || value.isBlank()) {
            throw MeetingError.invalidArgument("缺少参数 " + key);
        }
        return value;
    }

    private static String string(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value instanceof String s ? s : null;
    }

    private static int integer(Map<String, Object> args, String key, int fallback, int min, int max) {
        Object raw = args.getOrDefault(key, fallback);
        if (!(raw instanceof Number number) || !Double.isFinite(number.doubleValue())
            || number.doubleValue() != number.intValue() || number.intValue() < min || number.intValue() > max) {
            throw MeetingError.invalidArgument("参数 " + key + " 超出允许范围");
        }
        return number.intValue();
    }

    private static boolean bool(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value instanceof Boolean b && b;
    }

    private static List<String> strings(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof String s) {
                result.add(s);
            }
        }
        return result;
    }

    private static LocalDate date(Object raw) {
        if (!(raw instanceof String text) || text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text.strip());
        } catch (DateTimeParseException e) {
            throw MeetingError.invalidArgument("截止日期必须为 YYYY-MM-DD");
        }
    }

    private static Integer intOrNull(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Boolean boolOrNull(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value instanceof Boolean b ? b : null;
    }

    private static String str(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value instanceof String s ? s : null;
    }

    private static Throwable sanitizedFailure(Throwable source) {
        String detail = source instanceof MeetingError || source instanceof MeetingApiException
            ? source.getMessage() : source.getClass().getSimpleName();
        var safe = new IllegalArgumentException(detail);
        safe.setStackTrace(source.getStackTrace());
        if (source.getCause() != null) {
            safe.initCause(sanitizedFailure(source.getCause()));
        }
        for (Throwable suppressed : source.getSuppressed()) {
            safe.addSuppressed(sanitizedFailure(suppressed));
        }
        return safe;
    }
}
