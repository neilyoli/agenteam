package com.stonewu.agenteam.controller.meeting;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.meeting.response.MeetingSyncOutboxView;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.meeting.MeetingSyncApiService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 会议飞书投递箱的对账与补推管理。 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/meeting/sync")
public class MeetingSyncApiController {

    private final MeetingSyncApiService service;
    private final ApiResponses responses;

    public MeetingSyncApiController(MeetingSyncApiService service, ApiResponses responses) {
        this.service = service;
        this.responses = responses;
    }

    @GetMapping("/outbox")
    public ApiResponse<List<MeetingSyncOutboxView>> list(@PathVariable String enterpriseId,
                                                          @RequestParam(required = false) String status,
                                                          @RequestParam(required = false) String bindingId,
                                                          HttpServletRequest request) {
        return responses.success(service.list(enterpriseId, status, bindingId, request), request);
    }

    @PostMapping("/outbox/{outboxId}/retry")
    public ResponseEntity<?> retry(@PathVariable String enterpriseId, @PathVariable String outboxId,
                                   HttpServletRequest request) {
        return responses.operation(service.retry(enterpriseId, outboxId, request), request);
    }
}
