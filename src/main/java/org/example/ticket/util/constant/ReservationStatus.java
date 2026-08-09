package org.example.ticket.util.constant;

import lombok.Getter;

@Getter
public enum ReservationStatus {

    SUCCESS,
    LOCKED,
    PENDING_PAYMENT,
    EXPIRED;

}
