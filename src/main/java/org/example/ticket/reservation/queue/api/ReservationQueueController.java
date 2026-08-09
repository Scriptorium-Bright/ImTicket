package org.example.ticket.reservation.queue.api;

import org.example.ticket.reservation.queue.application.ReservationQueueApiRequest;
import org.example.ticket.reservation.queue.application.ReservationQueueEnqueueResponse;
import org.example.ticket.reservation.queue.application.ReservationQueueErrorCode;
import org.example.ticket.reservation.queue.application.ReservationQueueService;
import org.example.ticket.reservation.queue.application.ReservationQueueStatusResponse;
import org.example.ticket.security.principal.MetamaskUserDetails;

import jakarta.validation.Valid;
import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.common.response.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Feature flag가 켜진 instance에서 queue enqueue와 status endpoint를 제공한다. */
@RestController
@RequestMapping("/api/reservation/pre-reserve/queue")
@ConditionalOnProperty(prefix = "reservation.queue", name = "enabled", havingValue = "true")
public final class ReservationQueueController {

    private final ReservationQueueService queueService;

    public ReservationQueueController(ReservationQueueService queueService) {
        this.queueService = queueService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ReservationQueueEnqueueResponse>> enqueue(
            Authentication authentication,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ReservationQueueApiRequest request
    ) {
        MetamaskUserDetails userDetails = authenticatedUser(authentication);
        ReservationQueueEnqueueResponse response = queueService.enqueue(
                userDetails.getMemberId(),
                userDetails.getAddress(),
                idempotencyKey,
                request
        );
        return ResponseEntity.accepted().body(ApiResponse.success(response));
    }

    @GetMapping("/{performanceTimeId}/{ticketId}")
    public ResponseEntity<ApiResponse<ReservationQueueStatusResponse>> status(
            Authentication authentication,
            @PathVariable long performanceTimeId,
            @PathVariable UUID ticketId
    ) {
        MetamaskUserDetails userDetails = authenticatedUser(authentication);
        ReservationQueueStatusResponse response = queueService.status(
                userDetails.getAddress(),
                performanceTimeId,
                ticketId
        );
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    private MetamaskUserDetails authenticatedUser(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof MetamaskUserDetails userDetails)) {
            throw new BusinessException(ReservationQueueErrorCode.AUTHENTICATION_REQUIRED);
        }
        try {
            if (userDetails.getAddress() == null || userDetails.getAddress().isBlank()) {
                throw new IllegalStateException("walletAddress must not be blank");
            }
            userDetails.getMemberId();
            return userDetails;
        } catch (RuntimeException exception) {
            throw new BusinessException(ReservationQueueErrorCode.AUTHENTICATION_REQUIRED, exception);
        }
    }
}
