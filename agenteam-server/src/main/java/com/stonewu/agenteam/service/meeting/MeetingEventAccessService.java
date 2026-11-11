package com.stonewu.agenteam.service.meeting;

import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.meeting.entity.MeetingSubscriptionRow;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 事件触发也使用订阅配置者当前资格，不能继承配置时的浏览器权限。
 */
@Service
public class MeetingEventAccessService {
    private final AuthMapper users;
    private final EnterpriseMapper enterprises;
    private final PermissionMapper permissions;

    public MeetingEventAccessService(AuthMapper users, EnterpriseMapper enterprises, PermissionMapper permissions) {
        this.users = users;
        this.enterprises = enterprises;
        this.permissions = permissions;
    }

    public AuthContext requireOwner(MeetingSubscriptionRow subscription) {
        String user = subscription.getOwnerUserId();
        var account = users.findById(user).orElse(null);
        if (account == null || !"active".equals(account.status()) || !enterprises.activeMemberExists(subscription.getEnterpriseId(),
            user) || enterprises.findById(subscription.getEnterpriseId())
            .filter(value -> "active".equals(value.status())).isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "MEETING_OWNER_UNAVAILABLE", "订阅配置者的账号或成员资格已不可用。");
        }
        if (permissions.operationScope(user, subscription.getEnterpriseId(), "agent.run").isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "MEETING_PERMISSION_DENIED", "订阅配置者已失去会议分析的执行权限。");
        }
        return new AuthContext(account, subscription.getEnterpriseId(),
            Set.copyOf(permissions.listPermissionCodes(account.id(), subscription.getEnterpriseId())));
    }
}
