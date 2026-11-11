package com.stonewu.agenteam.service.meeting;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.meeting.MeetingFollowUpMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingFollowUpScanMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.meeting.entity.MeetingFollowUpRow;
import com.stonewu.agenteam.model.meeting.entity.MeetingFollowUpScanRow;
import com.stonewu.agenteam.model.meeting.response.MeetingFollowUpScanView;
import com.stonewu.agenteam.model.meeting.response.MeetingFollowUpView;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.http.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/** 管理员查看督办台账与扫描结果；只读，不修改飞书业务数据。 */
@Service
public class MeetingFollowUpApiService {
    private final AuthContextService identity;
    private final MeetingFollowUpMapper ledgers;
    private final MeetingFollowUpScanMapper scans;

    public MeetingFollowUpApiService(AuthContextService identity, MeetingFollowUpMapper ledgers,
                                     MeetingFollowUpScanMapper scans) {
        this.identity = identity;
        this.ledgers = ledgers;
        this.scans = scans;
    }

    public List<MeetingFollowUpView> list(String enterprise, String bindingId, HttpServletRequest request) {
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        requireManage(actor);
        var query = new LambdaQueryWrapper<MeetingFollowUpRow>()
            .eq(MeetingFollowUpRow::getEnterpriseId, enterprise)
            .eq(bindingId != null && !bindingId.isBlank(), MeetingFollowUpRow::getBindingId, bindingId)
            .orderByDesc(MeetingFollowUpRow::getUpdatedAt, MeetingFollowUpRow::getId)
            .last("LIMIT 200");
        return ledgers.selectList(query).stream().map(this::view).toList();
    }

    public List<MeetingFollowUpScanView> scans(String enterprise, String bindingId, HttpServletRequest request) {
        var actor = identity.requireEnterprise(request.getSession(false), enterprise);
        requireManage(actor);
        var query = new LambdaQueryWrapper<MeetingFollowUpScanRow>()
            .eq(MeetingFollowUpScanRow::getEnterpriseId, enterprise)
            .eq(bindingId != null && !bindingId.isBlank(), MeetingFollowUpScanRow::getBindingId, bindingId)
            .orderByDesc(MeetingFollowUpScanRow::getCreatedAt, MeetingFollowUpScanRow::getId)
            .last("LIMIT 200");
        return scans.selectList(query).stream().map(this::scanView).toList();
    }

    private void requireManage(AuthContext actor) {
        if (!actor.permissions().contains("agent.run")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "没有执行此操作的权限");
        }
    }

    private MeetingFollowUpView view(MeetingFollowUpRow row) {
        return new MeetingFollowUpView(row.getId(), row.getEnterpriseId(), row.getBindingId(), row.getBusinessKey(),
            row.getOwnerSubject(), time(row.getLastReminderAt()), time(row.getNextReminderAt()),
            row.getReminderCount() == null ? 0 : row.getReminderCount(), row.getLastEvidenceJson(),
            row.getStatus(), time(row.getCreatedAt()), time(row.getUpdatedAt()));
    }

    private MeetingFollowUpScanView scanView(MeetingFollowUpScanRow row) {
        return new MeetingFollowUpScanView(row.getId(), row.getEnterpriseId(), row.getBindingId(),
            row.getScheduleId(), row.getOccurrenceId(), row.getStatus(),
            row.getAttemptCount() == null ? 0 : row.getAttemptCount(),
            row.getMaxAttempts() == null ? 0 : row.getMaxAttempts(), time(row.getNextAttemptAt()),
            row.getLastErrorCode(), row.getResultJson(), time(row.getCreatedAt()), time(row.getUpdatedAt()));
    }

    private String time(Instant value) {
        return value == null ? null : value.toString();
    }
}
