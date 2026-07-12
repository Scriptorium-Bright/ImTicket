SET @suffix = REPLACE(UUID(), '-', '');
SET @wallet_address = '0xLoadTestUser';

INSERT INTO Member (
    wallet_address,
    phone_number,
    user_role,
    sms_verified,
    wallet_verified,
    identify_name
)
VALUES (
    @wallet_address,
    '01000009999',
    'ROLE_USER',
    TRUE,
    TRUE,
    'load-test-user'
)
ON DUPLICATE KEY UPDATE
    sms_verified = TRUE,
    wallet_verified = TRUE,
    user_role = 'ROLE_USER';

INSERT INTO Venue (
    performance_venue_name,
    performance_place_address,
    phoneNumber
)
VALUES (
    CONCAT('Lock Test Venue ', @suffix),
    'Seoul',
    '02-0000-9999'
);
SET @venue_id = LAST_INSERT_ID();

INSERT INTO VenueHall (
    venue_id,
    venuehall_name,
    venuehall_total_seats
)
VALUES (
    @venue_id,
    'Lock Test Hall',
    3
);
SET @venuehall_id = LAST_INSERT_ID();

INSERT INTO Performance (
    performance_title,
    description,
    venue_type,
    performance_start_date,
    performance_end_date
)
VALUES (
    CONCAT('Pessimistic Lock Test ', @suffix),
    'Hot-seat contention fixture',
    'CONCERT',
    CURDATE(),
    CURDATE() + INTERVAL 1 DAY
);
SET @performance_id = LAST_INSERT_ID();

INSERT INTO PerformanceTime (
    performance_id,
    venuehall_id,
    performance_start_date,
    performance_start_time
)
VALUES (
    @performance_id,
    @venuehall_id,
    CURDATE() + INTERVAL 1 DAY,
    '19:00:00'
);
SET @performance_time_id = LAST_INSERT_ID();

SET @seat_id_1 = (SELECT COALESCE(MAX(id), 0) + 1 FROM Seat);
SET @seat_id_2 = @seat_id_1 + 1;
SET @seat_id_3 = @seat_id_1 + 2;

INSERT INTO Seat (
    id,
    performance_time_id,
    seat_floor,
    seat_section,
    seat_row,
    seat_number,
    seat_status,
    seat_type,
    is_reservation,
    seat_price
)
VALUES
    (@seat_id_1, @performance_time_id, 1, 'A', 1, 1, 'AVAILABLE', 'A', FALSE, 100000),
    (@seat_id_2, @performance_time_id, 1, 'A', 1, 2, 'AVAILABLE', 'A', FALSE, 100000),
    (@seat_id_3, @performance_time_id, 1, 'A', 1, 3, 'AVAILABLE', 'A', FALSE, 100000);

UPDATE Seat_SEQ
SET next_val = GREATEST(next_val, @seat_id_3 + 1);

SELECT
    @wallet_address AS wallet_address,
    @performance_time_id AS performance_time_id,
    @seat_id_1 AS grade_1_seat_id,
    @seat_id_2 AS grade_2_seat_id,
    @seat_id_3 AS grade_3_seat_id;
