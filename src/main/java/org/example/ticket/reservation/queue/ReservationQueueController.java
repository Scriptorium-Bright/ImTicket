package org.example.ticket.reservation.queue;

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
        ReservationQueueEnqueueResponse response = queueService.enqueue(
                authenticatedName(authentication),
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
        ReservationQueueStatusResponse response = queueService.status(
                authenticatedName(authentication),
                performanceTimeId,
                ticketId
        );
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    private String authenticatedName(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication.getName() == null
                || authentication.getName().isBlank()) {
            throw new BusinessException(ReservationQueueErrorCode.AUTHENTICATION_REQUIRED);
        }
        return authentication.getName();
    }
}
