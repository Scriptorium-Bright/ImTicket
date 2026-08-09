package org.example.ticket.reservation.queue.controller;

import org.example.ticket.reservation.queue.dto.request.ReservationQueueApiRequest;
import org.example.ticket.reservation.queue.dto.response.ReservationQueueEnqueueResponse;
import org.example.ticket.reservation.queue.constant.ReservationQueueErrorCode;
import org.example.ticket.reservation.queue.service.ReservationQueueService;
import org.example.ticket.reservation.queue.dto.response.ReservationQueueStatusResponse;
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

    /**
     * Queue HTTP 요청을 처리할 application 서비스를 주입한다.
     * Controller는 인증과 응답 변환만 수행하고 접수 규칙은 서비스에 위임한다.
     */
    public ReservationQueueController(ReservationQueueService queueService) {
        this.queueService = queueService;
    }

    /**
     * 인증 사용자와 멱등 키를 포함한 Queue 접수 요청을 받는다.
     * Redis 원자 접수 결과를 202 Accepted 응답으로 반환한다.
     */
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

    /**
     * 인증 사용자가 소유한 Queue ticket 상태를 조회한다.
     * 대기 순번과 다음 polling 간격을 포함한 현재 상태를 반환한다.
     */
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

    /**
     * Spring Security 인증 객체에서 검증된 Queue principal을 꺼낸다.
     * member ID나 wallet이 유효하지 않으면 인증 오류로 처리한다.
     */
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
