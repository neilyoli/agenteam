package com.stonewu.agenteam.service.meeting;

import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.meeting.MeetingBindingMapper;
import com.stonewu.agenteam.model.meeting.entity.MeetingBindingRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 绑定的持久化写入与企业锁，与网络 schema 校验分离。 */
@Service
public class MeetingBindingStore {
    private final MeetingBindingMapper bindings;
    private final EnterpriseMapper enterprises;

    public MeetingBindingStore(MeetingBindingMapper bindings, EnterpriseMapper enterprises) {
        this.bindings = bindings;
        this.enterprises = enterprises;
    }

    @Transactional
    public void insert(MeetingBindingRow row) {
        enterprises.lockEnterprise(row.getEnterpriseId()).orElseThrow();
        bindings.insert(row);
    }

    @Transactional
    public void save(MeetingBindingRow row) {
        enterprises.lockEnterprise(row.getEnterpriseId()).orElseThrow();
        bindings.updateById(row);
    }
}
