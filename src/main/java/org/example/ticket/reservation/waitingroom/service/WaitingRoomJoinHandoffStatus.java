package org.example.ticket.reservation.waitingroom.service;

/** 비동기 Waiting Room join 요청의 처리 상태다. */
public enum WaitingRoomJoinHandoffStatus {
    QUEUED,
    PROCESSING,
    COMPLETED,
    FAILED
}
