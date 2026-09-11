SET @suffix = REPLACE(UUID(), '-', '');
SET @wallet_address = '0xLoadTestUser';
SET @seat_count = COALESCE(@seat_count, 30);

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
    CONCAT('Multi Hot Seat Venue ', @suffix),
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
    'Multi Hot Seat Hall',
    @seat_count
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
    CONCAT('Multi Hot Seat Test ', @suffix),
    'Distributed hot-seat contention fixture',
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

SET @first_seat_id = (SELECT COALESCE(MAX(id), 0) + 1 FROM Seat);

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
    seat_price,
    version
)
WITH RECURSIVE seat_numbers AS (
    SELECT 1 AS seat_number
    UNION ALL
    SELECT seat_number + 1
    FROM seat_numbers
    WHERE seat_number < @seat_count
)
SELECT
    @first_seat_id + seat_number - 1,
    @performance_time_id,
    1,
    'A',
    FLOOR((seat_number - 1) / 10) + 1,
    MOD(seat_number - 1, 10) + 1,
    'AVAILABLE',
    'A',
    FALSE,
    100000,
    0
FROM seat_numbers;

SET @last_seat_id = @first_seat_id + @seat_count - 1;

UPDATE Seat_SEQ
SET next_val = GREATEST(next_val, @last_seat_id + 1);

SELECT CONCAT(
    @wallet_address,
    ' ',
    @performance_time_id,
    ' ',
    GROUP_CONCAT(id ORDER BY id SEPARATOR ' ')
) AS fixture
FROM Seat
WHERE id BETWEEN @first_seat_id AND @last_seat_id;
