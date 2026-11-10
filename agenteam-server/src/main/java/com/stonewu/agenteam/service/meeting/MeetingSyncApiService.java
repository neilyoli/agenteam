package com.stonewu.agenteam.service.meeting;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.meeting.MeetingSyncOutboxSqlMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.model.meeting.entity.MeetingSyncOutboxRow;
import com.stonewu.agenteam.model.meeting.response.MeetingSyncOutboxView;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;

/** 管理员对账与补推入口；只读列表与失败/阻断重置，不修改飞书业务数据。 */
@Service
public class MeetingSyncApiService {
    private final AuthContextService identity;
    private final MeetingSyncOutboxSqlMapper outbox;
    private final MeetingSyncOutbox sync;
    private final Clock clock;

    public MeetingSyncApiService(AuthContextService identity, MeetingSyncOutboxSqlMapper outbox,
                                 MeetingSyncOutbox sync, Clock clock) {
        this.identity = identity;
        this.outbox = outbox;
        this.sync = sync;
        this.clock = clock;
    }

    public List<MeetingSyncOutboxView> list(String enterprise, String status, String bindingId,
                                            HttpServletRequest request) {
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        requireManage(actor);
        var query = new LambdaQueryWrapper<MeetingSyncOutboxRow>()
            .eq(MeetingSyncOutboxRow::getEnterpriseId, enterprise)
            .eq(status != null && !status.isBlank(), MeetingSyncOutboxRow::getStatus, status)
            .eq(bindingId != null && !bindingId.isBlank(), MeetingSyncOutboxRow::getBindingId, bindingId)
            .orderByDesc(MeetingSyncOutboxRow::getUpdatedAt, MeetingSyncOutboxRow::getId)
            .last("LIMIT 200");
        return outbox.selectList(query).stream().map(this::view).toList();
    }

    public ApiOperationResult retry(String enterprise, String outboxId, HttpServletRequest request) {
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        requireManage(actor);
        MeetingSyncOutboxRow row = require(enterprise, outboxId);
        sync.retry(row.getBindingId(), row.getBusinessKey(), clock.instant());
        return ApiOperationResult.of(200, view(require(enterprise, outboxId)));
    }

    private MeetingSyncOutboxRow require(String enterprise, String outboxId) {
        var row = outbox.selectOne(new LambdaQueryWrapper<MeetingSyncOutboxRow>()
            .eq(MeetingSyncOutboxRow::getEnterpriseId, enterprise)
            .eq(MeetingSyncOutboxRow::getId, outboxId));
        if (row == null) {
            throw ResourceAuthorizationService.unavailable();
        }
        return row;
    }

    private void requireManage(AuthContext actor) {
        if (!actor.permissions().contains("agent.run")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "没有执行此操作的权限");
        }
    }

    private MeetingSyncOutboxView view(MeetingSyncOutboxRow row) {
        return new MeetingSyncOutboxView(row.getId(), row.getEnterpriseId(), row.getBindingId(),
            row.getBusinessKey(), row.getCommandId(), row.getDesiredVersion(), row.getDeliveredVersion(),
            row.getRecordId(), row.getAttemptCount(), row.getMaxAttempts(),
            row.getNextAttemptAt() == null ? null : row.getNextAttemptAt().toString(), row.getStatus(),
            row.getLastErrorCode(), row.getLastRequestId(), row.getRunId(),
            row.getCreatedAt() == null ? null : row.getCreatedAt().toString(),
            row.getUpdatedAt() == null ? null : row.getUpdatedAt().toString());
    }
}
