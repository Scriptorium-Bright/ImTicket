-- Run before deploying code that writes ReservationStatus.EXPIRED.
ALTER TABLE `Reservation`
    MODIFY COLUMN `reservation_status`
        ENUM('EXPIRED', 'LOCKED', 'PENDING_PAYMENT', 'SUCCESS') NOT NULL;
