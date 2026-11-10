package com.stonewu.agenteam.controller.meeting;

import com.stonewu.agenteam.model.http.response.ApiResponse;
import com.stonewu.agenteam.model.meeting.request.MeetingBindingEnabledRequest;
import com.stonewu.agenteam.model.meeting.request.MeetingBindingWriteRequest;
import com.stonewu.agenteam.model.meeting.response.MeetingBindingView;
import com.stonewu.agenteam.service.http.ApiResponses;
import com.stonewu.agenteam.service.meeting.MeetingBindingApiService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 企业统一飞书 Base 的字段绑定管理；schema 校验与凭据由对应服务处理。
 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/meeting/bindings")
public class MeetingBindingApiController {

    private final MeetingBindingApiService service;

    private final ApiResponses responses;

    public MeetingBindingApiController(MeetingBindingApiService service, ApiResponses responses) {
        this.service = service;
        this.responses = responses;
    }

    @GetMapping
    public ApiResponse<List<MeetingBindingView>> list(@PathVariable String enterpriseId, HttpServletRequest request) {
        return responses.success(service.list(enterpriseId, request), request);
    }

    @PostMapping
    public ResponseEntity<?> create(@PathVariable String enterpriseId, @RequestBody MeetingBindingWriteRequest input,
                                    HttpServletRequest request) {
        return responses.operation(service.create(enterpriseId, input, request), request);
    }

    @PatchMapping("/{bindingId}")
    public ResponseEntity<?> update(@PathVariable String enterpriseId, @PathVariable String bindingId,
                                    @RequestBody MeetingBindingWriteRequest input, HttpServletRequest request) {
        return responses.operation(service.update(enterpriseId, bindingId, input, request), request);
    }

    @PatchMapping("/{bindingId}/enabled")
    public ResponseEntity<?> enabled(@PathVariable String enterpriseId, @PathVariable String bindingId,
                                     @RequestBody MeetingBindingEnabledRequest input, HttpServletRequest request) {
        return responses.operation(service.enabled(enterpriseId, bindingId, input, request), request);
    }

    @DeleteMapping("/{bindingId}")
    public ResponseEntity<?> delete(@PathVariable String enterpriseId, @PathVariable String bindingId,
                                    HttpServletRequest request) {
        return responses.operation(service.delete(enterpriseId, bindingId, request), request);
    }
}
