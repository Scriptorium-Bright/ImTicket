package org.example.ticket.reservation.waitingroom.service;

import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.reservation.waitingroom.config.WaitingRoomProperties;
import org.example.ticket.reservation.waitingroom.constant.WaitingRoomErrorCode;
import org.example.ticket.reservation.waitingroom.domain.WaitingRoomTicketStatus;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomJoinResult;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomStatusResponse;
import org.example.ticket.reservation.waitingroom.dto.WaitingRoomTicketSnapshot;
import org.example.ticket.reservation.waitingroom.pass.HmacWaitingRoomPassCodec;
import org.example.ticket.reservation.waitingroom.repository.WaitingRoomStore;
import org.example.ticket.reservation.waitingroom.util.WaitingRoomTimePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WaitingRoomServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");
    private static final UUID TICKET_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private WaitingRoomStore store;
    private WaitingRoomService service;

    /** 고정 clock과 enabled policy를 사용해 service unit fixture를 구성한다. */
    @BeforeEach
    void setUp() {
        WaitingRoomProperties properties = new WaitingRoomProperties();
        properties.setEnabled(true);
        properties.setEntryLease(Duration.ofMinutes(5));
        properties.setWaitingTicketTtl(Duration.ofMinutes(30));
        properties.setTerminalRetention(Duration.ofHours(1));
        properties.setStatusPollAfter(Duration.ofSeconds(2));
        WaitingRoomTimePolicy timePolicy = new WaitingRoomTimePolicy(
                Clock.fixed(NOW, ZoneOffset.UTC),
                properties
        );
        store = mock(WaitingRoomStore.class);
        service = new WaitingRoomService(
                store,
                properties,
                timePolicy,
                performanceTimeId -> true,
                new HmacWaitingRoomPassCodec("test-secret")
        );
    }

    /** join 결과가 1-based 순번과 대기 상태로 변환되는지 검증한다. */
    @Test
    void joinsAndReturnsOneBasedWaitingPosition() {
        WaitingRoomTicketSnapshot snapshot = waitingSnapshot();
        when(store.join(eq(7L), eq(12L), any(UUID.class), any(), any(), any()))
                .thenReturn(new WaitingRoomJoinResult(true, TICKET_ID, 3L));
        when(store.find(7L, TICKET_ID)).thenReturn(Optional.of(snapshot));
        when(store.waitingRank(7L, TICKET_ID)).thenReturn(OptionalLong.of(2L));

        WaitingRoomStatusResponse response = service.join(7L, 12L);

        assertThat(response.ticketId()).isEqualTo(TICKET_ID);
        assertThat(response.status()).isEqualTo(WaitingRoomTicketStatus.WAITING);
        assertThat(response.position()).isEqualTo(3L);
        assertThat(response.entryPass()).isNull();
    }

    /** admitted 상태가 서명 pass와 lease 만료 시각으로 응답되는지 검증한다. */
    @Test
    void returnsEntryPassForAdmittedTicket() {
        WaitingRoomTicketSnapshot snapshot = new WaitingRoomTicketSnapshot(
                TICKET_ID,
                12L,
                7L,
                WaitingRoomTicketStatus.ADMITTED,
                3L,
                NOW,
                NOW.plus(Duration.ofMinutes(30)),
                NOW.plus(Duration.ofMinutes(5))
        );
        when(store.find(7L, TICKET_ID)).thenReturn(Optional.of(snapshot));
        when(store.waitingRank(7L, TICKET_ID)).thenReturn(OptionalLong.empty());

        WaitingRoomStatusResponse response = service.status(7L, 12L, TICKET_ID);

        assertThat(response.position()).isNull();
        assertThat(response.entryPass()).isNotBlank();
        assertThat(new HmacWaitingRoomPassCodec("test-secret").parse(response.entryPass()))
                .satisfies(claims -> {
                    assertThat(claims.ticketId()).isEqualTo(TICKET_ID);
                    assertThat(claims.memberId()).isEqualTo(12L);
                    assertThat(claims.performanceTimeId()).isEqualTo(7L);
                });
    }

    /** 비활성 회차의 join 요청이 Waiting Room 전용 오류로 거절되는지 검증한다. */
    @Test
    void rejectsJoinWhenFeatureIsDisabled() {
        WaitingRoomProperties properties = new WaitingRoomProperties();
        properties.setEnabled(false);
        WaitingRoomTimePolicy timePolicy = new WaitingRoomTimePolicy(
                Clock.fixed(NOW, ZoneOffset.UTC),
                properties
        );
        service = new WaitingRoomService(
                store,
                properties,
                timePolicy,
                performanceTimeId -> false,
                new HmacWaitingRoomPassCodec("test-secret")
        );

        assertThatThrownBy(() -> service.join(7L, 12L))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(WaitingRoomErrorCode.WAITING_ROOM_DISABLED);
    }

    /** 다른 회원이 ticket status를 조회할 수 없는지 검증한다. */
    @Test
    void rejectsStatusForDifferentOwner() {
        when(store.find(7L, TICKET_ID)).thenReturn(Optional.of(waitingSnapshot()));

        assertThatThrownBy(() -> service.status(7L, 99L, TICKET_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(WaitingRoomErrorCode.WAITING_ROOM_TICKET_NOT_OWNER);
    }

    /** 테스트에서 사용할 WAITING snapshot을 생성한다. */
    private WaitingRoomTicketSnapshot waitingSnapshot() {
        return new WaitingRoomTicketSnapshot(
                TICKET_ID,
                12L,
                7L,
                WaitingRoomTicketStatus.WAITING,
                3L,
                NOW,
                NOW.plus(Duration.ofMinutes(30)),
                null
        );
    }
}
