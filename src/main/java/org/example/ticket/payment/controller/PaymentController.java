package org.example.ticket.payment.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.ticket.common.response.ApiResponse;
import org.example.ticket.payment.request.PaymentPrepareRequest;
import org.example.ticket.payment.request.PaymentVerifyRequest;
import org.example.ticket.payment.response.PaymentPrepareResponse;
import org.example.ticket.payment.response.PaymentStatusResponse;
import org.example.ticket.payment.response.PaymentVerificationResponse;
import org.example.ticket.payment.service.PaymentPreparationService;
import org.example.ticket.payment.service.PaymentVerificationService;
import org.example.ticket.security.util.MetamaskUserDetails;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentPreparationService paymentPreparationService;
    private final PaymentVerificationService paymentVerificationService;

    @PostMapping("/prepare")
    public ResponseEntity<ApiResponse<PaymentPrepareResponse>> prepare(
            @AuthenticationPrincipal MetamaskUserDetails userDetails,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PaymentPrepareRequest request) {
        PaymentPrepareResponse response = paymentPreparationService.prepare(
                userDetails.getAddress(), request, idempotencyKey
        );
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/{paymentOrderId}/verify")
    public ResponseEntity<ApiResponse<PaymentVerificationResponse>> verify(
            @AuthenticationPrincipal MetamaskUserDetails userDetails,
            @PathVariable Long paymentOrderId,
            @Valid @RequestBody PaymentVerifyRequest request) {
        PaymentVerificationResponse response = paymentVerificationService.verify(
                userDetails.getAddress(), paymentOrderId, request
        );
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/{paymentOrderId}")
    public ResponseEntity<ApiResponse<PaymentStatusResponse>> status(
            @AuthenticationPrincipal MetamaskUserDetails userDetails,
            @PathVariable Long paymentOrderId) {
        PaymentStatusResponse response = paymentVerificationService.getStatus(
                userDetails.getAddress(), paymentOrderId
        );
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
