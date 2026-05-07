package org.example.ticket.sms.controller;

import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletRequest;
import org.example.ticket.sms.request.SmsRequest;
import org.example.ticket.sms.service.SMSService;
import org.example.ticket.util.ratelimit.BusinessRateLimitGuard;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.naming.AuthenticationException;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/sms")
public class SMSController {

    private final SMSService smsService;
    private final BusinessRateLimitGuard businessRateLimitGuard;

    @PostMapping("/certificate")
    public ResponseEntity<String> certificate(
            HttpServletRequest httpServletRequest,
            @RequestBody SmsRequest request) throws AuthenticationException {
        businessRateLimitGuard.checkSmsCertificate(
                request.getTo(),
                businessRateLimitGuard.resolveClientIp(httpServletRequest)
        );
        boolean smsCode = smsService.sendMessage(request);
        if(!smsCode) return ResponseEntity.internalServerError().body("not Initialized Sms Code");
        return ResponseEntity.ok("send Succeed");
    }

    @PostMapping("/verify")
    public ResponseEntity<Map<String, Object>> verify(
            HttpServletRequest httpServletRequest,
            @RequestBody SmsRequest request) {
        String clientIp = businessRateLimitGuard.resolveClientIp(httpServletRequest);
        businessRateLimitGuard.checkSmsVerifyRequest(request.getTo(), clientIp);
        boolean isValid = smsService.verifiedCode(request.getTo(), request.getCode());
        businessRateLimitGuard.recordSmsVerifyResult(request.getTo(), clientIp, isValid);
        Map<String, Object> response = new HashMap<>();
        response.put("success", isValid);
        if (!isValid) {
            response.put("message", "Invalid verification code");
            return ResponseEntity.badRequest().body(response);
        }
        return ResponseEntity.ok(response);
    }

}
