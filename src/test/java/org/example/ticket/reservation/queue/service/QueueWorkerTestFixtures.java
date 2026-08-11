package org.example.ticket.reservation.queue.service;

import org.example.ticket.reservation.common.factory.ReservationIntentFingerprintFactory;
import org.example.ticket.reservation.common.value.ReservationIdempotencyKey;
import org.example.ticket.reservation.queue.dto.ReservationQueuePayload;
import org.example.ticket.reservation.queue.dto.ReservationQueueWorkItem;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class QueueWorkerTestFixtures {

    static final UUID TICKET_ID = UUID.fromString("f76f5ac8-a475-4e04-906a-1f54765f9770");
    static final UUID OWNER_TOKEN = UUID.fromString("da64524f-ac82-45a8-9d38-4cd641b72343");

    private QueueWorkerTestFixtures() {
    }

    static ReservationQueueWorkItem item() {
        String requestHash = ReservationIntentFingerprintFactory.create(42L, List.of(1L)).requestHash();
        return new ReservationQueueWorkItem(
                TICKET_ID,
                42L,
                "1786356000000-0",
                "b".repeat(64),
                OWNER_TOKEN,
                ReservationQueuePayload.current(
                        7L,
                        ReservationIdempotencyKey.from("a0ebc4c9-8d82-47af-8127-1fc3d27e47a1"),
                        requestHash,
                        List.of(1L)
                ),
                9L,
                Instant.parse("2026-08-12T09:59:00Z")
        );
    }
}
