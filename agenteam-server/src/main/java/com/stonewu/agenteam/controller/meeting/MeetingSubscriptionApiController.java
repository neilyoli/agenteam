package com.stonewu.agenteam.controller.meeting;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.meeting.request.MeetingSubscriptionEnabledRequest;
import com.stonewu.agenteam.model.meeting.request.MeetingSubscriptionWriteRequest;
import com.stonewu.agenteam.model.meeting.response.MeetingSubscriptionView;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.meeting.MeetingSubscriptionApiService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 接收本人的会议事件订阅声明；固定版本与授权由对应服务处理。
 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/meeting/subscriptions")
public class MeetingSubscriptionApiController {

    private final MeetingSubscriptionApiService service;

    private final ApiResponses responses;

    public MeetingSubscriptionApiController(MeetingSubscriptionApiService service, ApiResponses responses) {
        this.service = service;
        this.responses = responses;
    }

    @GetMapping
    public ApiResponse<List<MeetingSubscriptionView>> list(@PathVariable String enterpriseId, HttpServletRequest request) {
        return responses.success(service.list(enterpriseId, request), request);
    }

    @PostMapping
    public ResponseEntity<?> create(@PathVariable String enterpriseId, @RequestBody MeetingSubscriptionWriteRequest input,
                                    HttpServletRequest request) {
        return responses.operation(service.create(enterpriseId, input, request), request);
    }

    @PatchMapping("/{subscriptionId}/enabled")
    public ResponseEntity<?> enabled(@PathVariable String enterpriseId, @PathVariable String subscriptionId,
                                     @RequestBody MeetingSubscriptionEnabledRequest input, HttpServletRequest request) {
        return responses.operation(service.enabled(enterpriseId, subscriptionId, input, request), request);
    }

    @DeleteMapping("/{subscriptionId}")
    public ResponseEntity<?> delete(@PathVariable String enterpriseId, @PathVariable String subscriptionId,
                                    HttpServletRequest request) {
        return responses.operation(service.delete(enterpriseId, subscriptionId, request), request);
    }
}
