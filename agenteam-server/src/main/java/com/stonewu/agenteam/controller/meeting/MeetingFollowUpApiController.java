package com.stonewu.agenteam.controller.meeting;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.meeting.response.MeetingFollowUpScanView;
import com.stonewu.agenteam.model.meeting.response.MeetingFollowUpView;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.meeting.MeetingFollowUpApiService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 会议督办台账与扫描结果的只读查询；角色看板仍由飞书 Base 视图承担，这里仅做限频与证据核对。 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/meeting/follow-up")
public class MeetingFollowUpApiController {

    private final MeetingFollowUpApiService service;
    private final ApiResponses responses;

    public MeetingFollowUpApiController(MeetingFollowUpApiService service, ApiResponses responses) {
        this.service = service;
        this.responses = responses;
    }

    @GetMapping
    public ApiResponse<List<MeetingFollowUpView>> list(@PathVariable String enterpriseId,
                                                       @RequestParam(required = false) String bindingId,
                                                       HttpServletRequest request) {
        return responses.success(service.list(enterpriseId, bindingId, request), request);
    }

    @GetMapping("/scans")
    public ApiResponse<List<MeetingFollowUpScanView>> scans(@PathVariable String enterpriseId,
                                                            @RequestParam(required = false) String bindingId,
                                                            HttpServletRequest request) {
        return responses.success(service.scans(enterpriseId, bindingId, request), request);
    }
}
