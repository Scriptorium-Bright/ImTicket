package org.example.ticket.reservation.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.ticket.member.model.Member;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "reservation_idempotency",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_reservation_idempotency_member_key",
                        columnNames = {"member_id", "idempotency_key"}
                ),
                @UniqueConstraint(
                        name = "uk_reservation_idempotency_reservation",
                        columnNames = "reservation_id"
                )
        },
        indexes = @Index(
                name = "idx_reservation_idempotency_status_lease",
                columnList = "status, lease_expires_at"
        )
)
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReservationIdempotency {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(name = "idempotency_key", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64, columnDefinition = "CHAR(64)")
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ReservationIdempotencyStatus status;

    @Column(name = "attempt_token", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private String attemptToken;

    @Column(name = "lease_expires_at", nullable = false)
    private LocalDateTime leaseExpiresAt;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id")
    private Reservation reservation;

    @Column(name = "response_schema_version")
    private Integer responseSchemaVersion;

    @Column(name = "response_payload", columnDefinition = "LONGTEXT")
    private String responsePayload;

    @Column(name = "last_error_code", length = 64)
    private String lastErrorCode;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 현재 claim이 특정 사용자의 특정 요청 처리 시도에 의해 소유됐는지 확인한다. */
    public boolean isOwnedProcessingAttempt(
            Long memberId,
            String expectedRequestHash,
            String expectedAttemptToken
    ) {
        return status == ReservationIdempotencyStatus.PROCESSING
                && member != null
                && member.getId().equals(memberId)
                && requestHash.equals(expectedRequestHash)
                && attemptToken.equals(expectedAttemptToken);
    }

    /** 처리 중인 claim에 예약 결과와 response snapshot을 기록하고 성공 상태로 확정한다. */
    public void markSucceeded(
            Reservation reservation,
            int responseSchemaVersion,
            String responsePayload
    ) {
        if (status != ReservationIdempotencyStatus.PROCESSING) {
            throw new IllegalStateException("처리 중인 예약 멱등성 claim만 성공 처리할 수 있습니다.");
        }
        this.status = ReservationIdempotencyStatus.SUCCEEDED;
        this.reservation = reservation;
        this.responseSchemaVersion = responseSchemaVersion;
        this.responsePayload = responsePayload;
        this.lastErrorCode = null;
    }
}
