package org.example.ticket.reservation.waitingroom.sse;

import java.util.UUID;

/** application instance의 local stream registry에서 ticket을 식별하는 key다. */
record WaitingRoomSseTicketKey(long performanceTimeId, UUID ticketId) {
}
