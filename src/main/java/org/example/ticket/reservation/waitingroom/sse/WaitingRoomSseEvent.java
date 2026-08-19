package org.example.ticket.reservation.waitingroom.sse;

import java.time.Instant;

/** 한 stream connection에 직렬 전달할 SSE event와 종료 여부를 표현한다. */
record WaitingRoomSseEvent(String type, Object data, boolean closeAfterDelivery, Instant occurredAt) {
}
