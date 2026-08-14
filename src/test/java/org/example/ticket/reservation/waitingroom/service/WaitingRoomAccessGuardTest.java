package org.example.ticket.reservation.waitingroom.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.reservation.waitingroom.config.WaitingRoomProperties;
import org.example.ticket.reservation.waitingroom.constant.WaitingRoomErrorCode;
import org.example.ticket.reservation.waitingroom.domain.WaitingRoomTicketStatus;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomTicketSnapshot;
import org.example.ticket.reservation.waitingroom.exception.WaitingRoomStorageException;
import org.example.ticket.reservation.waitingroom.pass.HmacWaitingRoomPassCodec;
import org.example.ticket.reservation.waitingroom.pass.WaitingRoomPassClaims;
import org.example.ticket.reservation.waitingroom.repository.WaitingRoomStore;
import org.example.ticket.reservation.waitingroom.util.WaitingRoomTimePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WaitingRoomAccessGuardTest {

    private static final Instant NOW = Instant.parse("2026-08-15T00:00:00Z");
    private static final UUID TICKET_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final long MEMBER_ID = 12L;
    private static final long PERFORMANCE_TIME_ID = 7L;
    private WaitingRoomStore store;
    private WaitingRoomAccessGuard guard;
    private HmacWaitingRoomPassCodec passCodec;

    @BeforeEach
    void setUp() {
        WaitingRoomProperties properties = new WaitingRoomProperties();
        properties.setEnabled(true);
        properties.setEnabledPerformanceTimeIds(Set.of(PERFORMANCE_TIME_ID));
        properties.setWaitingTicketTtl(Duration.ofMinutes(30));
        properties.setEntryLease(Duration.ofMinutes(5));
        properties.setTerminalRetention(Duration.ofHours(1));
        properties.setStatusPollAfter(Duration.ofSeconds(2));
        properties.setPassSecret("test-secret");
        WaitingRoomTimePolicy timePolicy = new WaitingRoomTimePolicy(
                Clock.fixed(NOW, ZoneOffset.UTC),
                properties
        );
        store = mock(WaitingRoomStore.class);
        passCodec = new HmacWaitingRoomPassCodec("test-secret");
        guard = new WaitingRoomAccessGuard(
                performanceTimeId -> performanceTimeId == PERFORMANCE_TIME_ID,
                store,
                passCodec,
                timePolicy,
                new SimpleMeterRegistry()
        );
    }

    @Test
    void bypassesAllPassAndRedisChecksForNonWaitingRoomPerformance() {
        WaitingRoomAccess access = guard.authorize(99L, null, null);

        assertThat(access.decision()).isEqualTo(WaitingRoomAccessDecision.BYPASS);
        verifyNoInteractions(store);
    }

    @Test
    void requiresAuthenticationAndPassForProtectedPerformance() {
        assertThatThrownBy(() -> guard.authorize(PERFORMANCE_TIME_ID, null, null))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(WaitingRoomErrorCode.WAITING_ROOM_PASS_REQUIRED));
        verifyNoInteractions(store);
    }

    @Test
    void admitsMatchingLiveTicket() {
        when(store.find(PERFORMANCE_TIME_ID, TICKET_ID)).thenReturn(Optional.of(snapshot(
                WaitingRoomTicketStatus.ADMITTED,
                NOW.plusSeconds(300)
        )));

        WaitingRoomAccess access = guard.authorize(PERFORMANCE_TIME_ID, MEMBER_ID, livePass());

        assertThat(access.decision()).isEqualTo(WaitingRoomAccessDecision.ACTIVE);
        assertThat(access.claims().ticketId()).isEqualTo(TICKET_ID);
        verify(store).find(PERFORMANCE_TIME_ID, TICKET_ID);
    }

    @Test
    void rejectsTamperedPassBeforeRedisLookup() {
        String valid = livePass();
        String tampered = valid.substring(0, valid.length() - 1)
                + (valid.endsWith("a") ? "b" : "a");

        assertThatThrownBy(() -> guard.authorize(PERFORMANCE_TIME_ID, MEMBER_ID, tampered))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(WaitingRoomErrorCode.WAITING_ROOM_PASS_INVALID));
        verifyNoInteractions(store);
    }

    @Test
    void rejectsPassOwnedByAnotherMemberBeforeReservationAccess() {
        when(store.find(PERFORMANCE_TIME_ID, TICKET_ID)).thenReturn(Optional.of(snapshot(
                WaitingRoomTicketStatus.ADMITTED,
                NOW.plusSeconds(300)
        )));

        assertThatThrownBy(() -> guard.authorize(PERFORMANCE_TIME_ID, 99L, livePass()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(WaitingRoomErrorCode.WAITING_ROOM_PASS_INVALID));
        verifyNoInteractions(store);
    }

    @Test
    void returnsReplayOnlyForCompletedTicket() {
        when(store.find(PERFORMANCE_TIME_ID, TICKET_ID)).thenReturn(Optional.of(snapshot(
                WaitingRoomTicketStatus.COMPLETED,
                NOW.plusSeconds(300)
        )));

        WaitingRoomAccess access = guard.authorize(PERFORMANCE_TIME_ID, MEMBER_ID, livePass());

        assertThat(access.decision()).isEqualTo(WaitingRoomAccessDecision.REPLAY_ONLY);
    }

    @Test
    void returnsReplayOnlyWhenAdmittedLeaseHasExpired() {
        when(store.find(PERFORMANCE_TIME_ID, TICKET_ID)).thenReturn(Optional.of(snapshot(
                WaitingRoomTicketStatus.ADMITTED,
                NOW.minusSeconds(1)
        )));

        WaitingRoomAccess access = guard.authorize(PERFORMANCE_TIME_ID, MEMBER_ID, expiredPass());

        assertThat(access.decision()).isEqualTo(WaitingRoomAccessDecision.REPLAY_ONLY);
    }

    @Test
    void rejectsWaitingTicketEvenWhenClaimIsSigned() {
        when(store.find(PERFORMANCE_TIME_ID, TICKET_ID)).thenReturn(Optional.of(snapshot(
                WaitingRoomTicketStatus.WAITING,
                null
        )));

        assertThatThrownBy(() -> guard.authorize(PERFORMANCE_TIME_ID, MEMBER_ID, livePass()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(WaitingRoomErrorCode.WAITING_ROOM_PASS_INVALID));
    }

    @Test
    void mapsRedisFailureWithoutExposingInfrastructureException() {
        when(store.find(eq(PERFORMANCE_TIME_ID), eq(TICKET_ID)))
                .thenThrow(new WaitingRoomStorageException("redis unavailable"));

        assertThatThrownBy(() -> guard.authorize(PERFORMANCE_TIME_ID, MEMBER_ID, livePass()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(WaitingRoomErrorCode.WAITING_ROOM_REDIS_FAILURE));
    }

    private WaitingRoomTicketSnapshot snapshot(WaitingRoomTicketStatus status, Instant entryExpiresAt) {
        return new WaitingRoomTicketSnapshot(
                TICKET_ID,
                MEMBER_ID,
                PERFORMANCE_TIME_ID,
                status,
                1L,
                NOW.minusSeconds(30),
                NOW.plusSeconds(1_800),
                entryExpiresAt
        );
    }

    private String livePass() {
        return passCodec.issue(new WaitingRoomPassClaims(
                TICKET_ID,
                MEMBER_ID,
                PERFORMANCE_TIME_ID,
                NOW.minusSeconds(30),
                NOW.plusSeconds(300)
        ));
    }

    private String expiredPass() {
        return passCodec.issue(new WaitingRoomPassClaims(
                TICKET_ID,
                MEMBER_ID,
                PERFORMANCE_TIME_ID,
                NOW.minusSeconds(600),
                NOW.minusSeconds(1)
        ));
    }
}
