package com.stonewu.agenteam.model.meeting.request;

import java.util.List;

/** 会议督办操作（meeting.follow_up）的平铺配置；经校验后固化为 MeetingFollowUpPolicy 快照。 */
public record MeetingFollowUpActionRequest(String bindingId, Long cooldownMinutes,
                                           Integer windowStartHour, Integer windowEndHour,
                                           List<String> conditions) {
}
