package com.stonewu.agenteam.service.meeting;

import com.stonewu.agenteam.mapper.meeting.MeetingTaskIdentityMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingTaskSourceMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.meeting.entity.MeetingTaskIdentityRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingTaskSourceRow;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会议任务稳定身份：宿主首次创建时分配并持久化，之后不随标题、负责人或描述变化。
 * 跨会议再次提及同一任务时复用已有身份并追加来源记录；同一规范化标题与负责人已存在
 * 活动身份而未显式引用时提示重复候选，由调用方显式关联或区分，绝不按相似度自动合并。
 * 身份与来源只保存技术信息；任务业务状态仍以飞书督办宽表为唯一事实来源。
 */
@Service
public class MeetingTaskIdentityService {
    private final MeetingTaskIdentityMapper identities;
    private final MeetingTaskSourceMapper sources;
    private final ResourceJson json;
    private final Clock clock;

    public MeetingTaskIdentityService(MeetingTaskIdentityMapper identities, MeetingTaskSourceMapper sources,
                                      ResourceJson json, Clock clock) {
        this.identities = identities;
        this.sources = sources;
        this.json = json;
        this.clock = clock;
    }

    /** 发布时的任务身份请求；evidence 为可定位证据的精简快照，recordSource 表示是否记录会议来源。 */
    public record IdentityRequest(String existingTaskKey, String title, String ownerOpenId, String ownerUserId,
                                  String ownerName, String meetingId, String meetingTitle,
                                  List<Map<String, String>> evidence, String runId, String commandId,
                                  boolean recordSource) {
    }

    /** 已解析的任务身份：复用或新建，均返回宿主分配的稳定身份记录。 */
    public MeetingTaskIdentityRow resolve(String enterpriseId, String bindingId, IdentityRequest request) {
        MeetingTaskIdentityRow identity;
        if (request.existingTaskKey() != null && !request.existingTaskKey().isBlank()) {
            identity = requireExisting(enterpriseId, request.existingTaskKey());
            refresh(identity, request);
        } else {
            identity = createNew(enterpriseId, bindingId, request);
        }
        if (request.recordSource()) {
            recordSource(enterpriseId, identity.getId(), request);
        }
        return identity;
    }

    /** 验证上游引用指向真实存在的活动任务身份。 */
    public void requireUpstreamReferences(String enterpriseId, List<String> upstreamTaskKeys) {
        for (String key : upstreamTaskKeys) {
            var identity = identities.find(enterpriseId, key);
            if (identity == null || !"active".equals(identity.getStatus())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MEETING_UPSTREAM_TASK_NOT_FOUND",
                    "上游任务引用不存在或已不再有效：" + key + "；请先用任务检索确认真实的任务编号。");
            }
        }
    }

    private MeetingTaskIdentityRow requireExisting(String enterpriseId, String taskKey) {
        var identity = identities.find(enterpriseId, taskKey);
        if (identity == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MEETING_TASK_NOT_FOUND",
                "引用的任务编号不存在：" + taskKey + "；请用任务检索获取真实编号后重试。");
        }
        if ("merged".equals(identity.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "MEETING_TASK_MERGED",
                "引用的任务已合并到其他任务，请检索最新任务编号后重试。");
        }
        return identity;
    }

    private MeetingTaskIdentityRow createNew(String enterpriseId, String bindingId, IdentityRequest request) {
        String normalized = normalize(request.title());
        var candidates = identities.findActiveByTitleAndOwner(enterpriseId, normalized, request.ownerOpenId());
        if (!candidates.isEmpty()) {
            // 不自动合并：要求显式引用既有任务，或调整标题说明这是不同任务。
            String keys = candidates.stream().map(MeetingTaskIdentityRow::getId)
                .reduce((left, right) -> left + "、" + right).orElse("");
            throw new ApiException(HttpStatus.CONFLICT, "MEETING_TASK_DUPLICATE_CANDIDATE",
                "已存在相同标题与负责人的任务：" + keys + "。若是同一任务请携带 existingTaskKey 重新发布；"
                    + "若是不同任务请把任务标题写得更具体。");
        }
        Instant now = clock.instant();
        var row = new MeetingTaskIdentityRow();
        row.setId(newTaskKey());
        row.setEnterpriseId(enterpriseId);
        row.setRevision(1L);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setBindingId(bindingId);
        row.setMeetingId(request.meetingId());
        row.setTitleCurrent(request.title());
        row.setTitleNormalized(normalized);
        row.setOwnerOpenId(request.ownerOpenId());
        row.setOwnerUserId(request.ownerUserId());
        row.setOwnerName(request.ownerName());
        row.setSourceCount(0);
        row.setStatus("active");
        row.setCreatedRunId(request.runId());
        row.setCreatedCommandId(request.commandId());
        identities.insert(row);
        return row;
    }

    /** 复用身份时同步展示快照；身份键永不改变。 */
    private void refresh(MeetingTaskIdentityRow identity, IdentityRequest request) {
        Instant now = clock.instant();
        identity.setTitleCurrent(request.title());
        identity.setTitleNormalized(normalize(request.title()));
        identity.setOwnerOpenId(request.ownerOpenId());
        identity.setOwnerUserId(request.ownerUserId());
        identity.setOwnerName(request.ownerName());
        identity.setUpdatedAt(now);
        identities.updateById(identity);
    }

    /** 记录本次会议来源；同一任务同一会议重复提及时保持一条来源并更新证据。 */
    private void recordSource(String enterpriseId, String taskKey, IdentityRequest request) {
        var existing = sources.find(enterpriseId, taskKey, request.meetingId());
        Instant now = clock.instant();
        if (existing == null) {
            var row = new MeetingTaskSourceRow();
            row.setId(UUID.randomUUID().toString());
            row.setEnterpriseId(enterpriseId);
            row.setTaskKey(taskKey);
            row.setRevision(1L);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            row.setMeetingId(request.meetingId());
            row.setMeetingTitle(request.meetingTitle());
            row.setRunId(request.runId());
            row.setCommandId(request.commandId());
            row.setEvidenceJson(json.write(json.tree(Map.of("items", request.evidence()))));
            sources.insert(row);
            var identity = identities.find(enterpriseId, taskKey);
            if (identity != null) {
                identity.setSourceCount(identity.getSourceCount() + 1);
                identity.setUpdatedAt(now);
                identities.updateById(identity);
            }
        } else {
            existing.setRunId(request.runId());
            existing.setCommandId(request.commandId());
            existing.setMeetingTitle(request.meetingTitle());
            existing.setEvidenceJson(json.write(json.tree(Map.of("items", request.evidence()))));
            existing.setUpdatedAt(now);
            sources.updateById(existing);
        }
    }

    /** 宿主分配的新任务身份键；随机分配，与标题、负责人、会议无关，首次创建后永久不变。 */
    private String newTaskKey() {
        return "T-" + UUID.randomUUID().toString().replace("-", "");
    }

    /** 标题规范化：去首尾空白、折叠空白、小写；用于消除等价写法差异，仅作候选提示不作合并依据。 */
    static String normalize(String title) {
        if (title == null) {
            return "";
        }
        return title.strip().toLowerCase().replaceAll("\\s+", " ");
    }
}
