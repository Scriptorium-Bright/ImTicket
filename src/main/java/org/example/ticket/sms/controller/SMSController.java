package org.example.ticket.sms.controller;

import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletRequest;
import org.example.ticket.sms.request.SmsRequest;
import org.example.ticket.sms.service.SMSService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.naming.AuthenticationException;
import java.util.HashMap;
import java.util.Map;
import org.example.ticket.common.response.ApiResponse;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/sms")
public class SMSController {

    private final SMSService smsService;

    @PostMapping("/certificate")
    public ResponseEntity<ApiResponse<String>> certificate(
            HttpServletRequest httpServletRequest,
            @RequestBody SmsRequest request) throws AuthenticationException {
        boolean smsCode = smsService.sendMessage(request);
        if(!smsCode) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.fail(org.example.ticket.common.response.ErrorResponse.of("INTERNAL_SERVER_ERROR", "not Initialized Sms Code")));
        }
        return ResponseEntity.ok(ApiResponse.success("send Succeed"));
    }

    @PostMapping("/verify")
    public ResponseEntity<ApiResponse<Boolean>> verify(
            HttpServletRequest httpServletRequest,
            @RequestBody SmsRequest request) {
        boolean isValid = smsService.verifiedCode(request.getTo(), request.getCode());
        if (!isValid) {
            throw new IllegalArgumentException("Invalid verification code");
        }
        return ResponseEntity.ok(ApiResponse.success(true));
    }

}
