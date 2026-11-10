package com.stonewu.meeting.service;

import com.stonewu.meeting.domain.MeetingParticipant;
import com.stonewu.meeting.domain.OwnerCandidate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 负责人解析：把 Agent 提议的姓名/身份映射到参会人中的稳定 openId。
 * 身份只以 openId / unionId 判断，姓名仅作匹配线索；匹配不到或多重匹配时保留歧义，
 * 由宿主引导人工确认，绝不自动指派或冒名发送确认卡片。
 */
public final class MeetingPeopleResolver {

    public OwnerCandidate resolve(List<MeetingParticipant> participants, String nameHint,
                                  String openIdHint, String userIdHint) {
        List<MeetingParticipant> source = participants == null ? List.of() : participants;

        // 明确给出稳定标识时直接采用，但仍校验其确实出现在参会人中。
        if (notBlank(openIdHint)) {
            return byStableId(source, openIdHint, userIdHint, nameHint, true);
        }
        if (notBlank(userIdHint)) {
            return byStableId(source, null, userIdHint, nameHint, false);
        }

        // 仅凭姓名匹配：命中且唯一时解析成功，否则保持歧义。
        List<MeetingParticipant> matched = new ArrayList<>();
        for (MeetingParticipant p : source) {
            if (sameName(p.name(), nameHint)) {
                matched.add(p);
            }
        }
        if (matched.size() == 1) {
            MeetingParticipant p = matched.get(0);
            if (notBlank(p.openId()) || notBlank(p.userId())) {
                return OwnerCandidate.resolved(p.openId(), p.userId(), p.name());
            }
            return OwnerCandidate.ambiguous(nameHint, List.of("参会人缺少稳定身份标识"));
        }
        if (matched.size() > 1) {
            return OwnerCandidate.ambiguous(nameHint, List.of("同名参会人 " + matched.size() + " 人，需人工确认"));
        }
        return OwnerCandidate.ambiguous(nameHint, List.of("未在参会人列表中找到该负责人"));
    }

    /** 把会议中所有参会人规范为可用于展示/复选的身份候选。 */
    public List<OwnerCandidate> candidates(List<MeetingParticipant> participants) {
        if (participants == null) {
            return List.of();
        }
        List<OwnerCandidate> result = new ArrayList<>(participants.size());
        for (MeetingParticipant p : participants) {
            if (notBlank(p.openId()) || notBlank(p.userId())) {
                result.add(OwnerCandidate.resolved(p.openId(), p.userId(), p.name()));
            }
        }
        return result;
    }

    private OwnerCandidate byStableId(List<MeetingParticipant> source, String openId, String userId,
                                      String nameHint, boolean openIdPreferred) {
        for (MeetingParticipant p : source) {
            if (openIdPreferred) {
                if (Objects.equals(p.openId(), openId)) {
                    return OwnerCandidate.resolved(p.openId(), p.userId(), p.name());
                }
            } else if (Objects.equals(p.userId(), userId)) {
                return OwnerCandidate.resolved(p.openId(), p.userId(), p.name());
            }
        }
        // 稳定标识不在参会人中：疑似越权或伪造身份，阻断而非静默接受。
        return OwnerCandidate.ambiguous(nameHint, List.of("提供的负责人身份不在参会人列表中"));
    }

    private static boolean sameName(String actual, String hint) {
        if (actual == null || hint == null) {
            return false;
        }
        return actual.strip().equalsIgnoreCase(hint.strip());
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
