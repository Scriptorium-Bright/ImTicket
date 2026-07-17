-- ImTicket DB operations portfolio benchmark data seed.
--
-- Intended usage:
--   scripts/test/seed_mysql_benchmark_data.sh
--
-- This script inserts synthetic rows into the real domain tables used by the
-- reservation flow, so EXPLAIN ANALYZE and lock-wait screenshots can be taken
-- against the same table names used by the application.
--
-- Expected table naming:
--   Member, Performance, PerformanceTime, Seat, Reservation, ReservedSeat
--
-- The shell wrapper prepends these user variables:
--   @base_id
--   @member_count
--   @seat_count
--   @reservation_count
--   @reserved_seat_per_reservation
--   @performance_time_count
--   @expired_ratio

SET @base_id := COALESCE(@base_id, 900000000);
SET @member_count := COALESCE(@member_count, 100000);
SET @seat_count := COALESCE(@seat_count, 100000);
SET @reservation_count := COALESCE(@reservation_count, 300000);
SET @reserved_seat_per_reservation := COALESCE(@reserved_seat_per_reservation, 2);
SET @performance_time_count := COALESCE(@performance_time_count, 10);
SET @expired_ratio := COALESCE(@expired_ratio, 50);

SET @reserved_seat_count := @reservation_count * @reserved_seat_per_reservation;
SET @max_count := GREATEST(@member_count, @seat_count, @reservation_count, @reserved_seat_count);

SELECT
    @base_id AS benchmark_base_id,
    @member_count AS member_count,
    @seat_count AS seat_count,
    @reservation_count AS reservation_count,
    @reserved_seat_count AS reserved_seat_count,
    @performance_time_count AS performance_time_count,
    @expired_ratio AS expired_ratio_percent,
    @max_count AS generated_number_count;

DROP TEMPORARY TABLE IF EXISTS benchmark_numbers;
CREATE TEMPORARY TABLE benchmark_numbers (
    n INT NOT NULL PRIMARY KEY
) ENGINE = InnoDB;

INSERT INTO benchmark_numbers (n)
SELECT
    d0.i
    + d1.i * 10
    + d2.i * 100
    + d3.i * 1000
    + d4.i * 10000
    + d5.i * 100000
    + 1 AS n
FROM (
    SELECT 0 AS i UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
) d0
CROSS JOIN (
    SELECT 0 AS i UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
) d1
CROSS JOIN (
    SELECT 0 AS i UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
) d2
CROSS JOIN (
    SELECT 0 AS i UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
) d3
CROSS JOIN (
    SELECT 0 AS i UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
) d4
CROSS JOIN (
    SELECT 0 AS i UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
) d5
WHERE
    d0.i
    + d1.i * 10
    + d2.i * 100
    + d3.i * 1000
    + d4.i * 10000
    + d5.i * 100000
    + 1 <= @max_count;

SELECT COUNT(*) AS benchmark_numbers_ready FROM benchmark_numbers;

SET FOREIGN_KEY_CHECKS = 0;

DELETE FROM `ReservedSeat`
WHERE
    `id` BETWEEN @base_id + 1 AND @base_id + @reserved_seat_count
    OR `reservation_id` BETWEEN @base_id + 1 AND @base_id + @reservation_count
    OR `seat_id` BETWEEN @base_id + 1 AND @base_id + @seat_count;

DELETE FROM `Reservation`
WHERE `id` BETWEEN @base_id + 1 AND @base_id + @reservation_count;

DELETE FROM `Seat`
WHERE `id` BETWEEN @base_id + 1 AND @base_id + @seat_count;

DELETE FROM `PerformanceTime`
WHERE `id` BETWEEN @base_id + 1 AND @base_id + @performance_time_count;

DELETE FROM `Performance`
WHERE `id` = @base_id + 1;

DELETE FROM `Member`
WHERE `id` BETWEEN @base_id + 1 AND @base_id + @member_count;

SET FOREIGN_KEY_CHECKS = 1;

INSERT INTO `Member` (
    `id`,
    `wallet_address`,
    `phone_number`,
    `user_role`,
    `sms_verified`,
    `wallet_verified`,
    `nonce`,
    `identify_name`
)
SELECT
    @base_id + n AS id,
    CONCAT('0xbench', LPAD(n, 34, '0')) AS wallet_address,
    CONCAT('010', LPAD(n, 8, '0')) AS phone_number,
    'ROLE_USER' AS user_role,
    1 AS sms_verified,
    1 AS wallet_verified,
    n AS nonce,
    CONCAT('bench_user_', n) AS identify_name
FROM benchmark_numbers
WHERE n <= @member_count;

INSERT INTO `Performance` (
    `id`,
    `visible_age`,
    `description`,
    `image_url`,
    `performance_title`,
    `venue_type`,
    `performance_start_date`,
    `performance_end_date`,
    `organizer_id`
)
VALUES (
    @base_id + 1,
    15,
    'Benchmark performance for MySQL EXPLAIN and lock-wait experiments',
    'benchmark-performance.jpg',
    'ImTicket Benchmark Concert',
    NULL,
    CURDATE(),
    DATE_ADD(CURDATE(), INTERVAL 30 DAY),
    NULL
);

INSERT INTO `PerformanceTime` (
    `id`,
    `performance_start_date`,
    `performance_start_time`,
    `performance_id`,
    `venuehall_id`
)
SELECT
    @base_id + n AS id,
    DATE_ADD(CURDATE(), INTERVAL n DAY) AS performance_start_date,
    MAKETIME(18 + MOD(n, 4), 0, 0) AS performance_start_time,
    @base_id + 1 AS performance_id,
    NULL AS venuehall_id
FROM benchmark_numbers
WHERE n <= @performance_time_count;

INSERT INTO `Seat` (
    `id`,
    `seat_floor`,
    `seat_section`,
    `seat_row`,
    `seat_number`,
    `seat_type`,
    `seat_price`,
    `is_reservation`,
    `seat_status`,
    `performance_time_id`
)
SELECT
    @base_id + n AS id,
    1 + MOD(n, 3) AS seat_floor,
    CHAR(65 + MOD(n, 6)) AS seat_section,
    1 + MOD(FLOOR((n - 1) / 100), 50) AS seat_row,
    1 + MOD(n - 1, 100) AS seat_number,
    CASE MOD(n, 6)
        WHEN 0 THEN 'VIP'
        WHEN 1 THEN 'R'
        WHEN 2 THEN 'S'
        WHEN 3 THEN 'A'
        WHEN 4 THEN 'B'
        ELSE 'C'
    END AS seat_type,
    CASE MOD(n, 6)
        WHEN 0 THEN 150000
        WHEN 1 THEN 120000
        WHEN 2 THEN 90000
        WHEN 3 THEN 70000
        WHEN 4 THEN 50000
        ELSE 30000
    END AS seat_price,
    IF(MOD(n, 10) IN (0, 1, 2), 1, 0) AS is_reservation,
    CASE
        WHEN MOD(n, 10) IN (0, 1, 2) THEN 'LOCKED'
        ELSE 'AVAILABLE'
    END AS seat_status,
    @base_id + 1 + MOD(n - 1, @performance_time_count) AS performance_time_id
FROM benchmark_numbers
WHERE n <= @seat_count;

INSERT INTO `Reservation` (
    `id`,
    `reservation_code`,
    `total_price`,
    `member_id`,
    `reservation_status`,
    `reservation_date`,
    `reservation_expired_time`
)
SELECT
    @base_id + n AS id,
    CONCAT('BENCH-', LPAD(n, 12, '0')) AS reservation_code,
    150000 + MOD(n, 5) * 10000 AS total_price,
    @base_id + 1 + MOD(n - 1, @member_count) AS member_id,
    CASE
        WHEN MOD(n, 100) < @expired_ratio THEN 'PENDING_PAYMENT'
        WHEN MOD(n, 10) = 0 THEN 'SUCCESS'
        ELSE 'PENDING_PAYMENT'
    END AS reservation_status,
    DATE_SUB(NOW(), INTERVAL MOD(n, 1440) MINUTE) AS reservation_date,
    CASE
        WHEN MOD(n, 100) < @expired_ratio THEN DATE_SUB(NOW(), INTERVAL 1 + MOD(n, 10080) MINUTE)
        WHEN MOD(n, 10) = 0 THEN NULL
        ELSE DATE_ADD(NOW(), INTERVAL 1 + MOD(n, 10080) MINUTE)
    END AS reservation_expired_time
FROM benchmark_numbers
WHERE n <= @reservation_count;

INSERT INTO `ReservedSeat` (
    `id`,
    `seat_id`,
    `reservation_id`
)
SELECT
    @base_id + n AS id,
    @base_id + 1 + MOD(n - 1, @seat_count) AS seat_id,
    @base_id + 1 + FLOOR((n - 1) / @reserved_seat_per_reservation) AS reservation_id
FROM benchmark_numbers
WHERE n <= @reserved_seat_count;

SELECT
    'Member' AS table_name,
    COUNT(*) AS benchmark_rows
FROM `Member`
WHERE `id` BETWEEN @base_id + 1 AND @base_id + @member_count
UNION ALL
SELECT
    'PerformanceTime' AS table_name,
    COUNT(*) AS benchmark_rows
FROM `PerformanceTime`
WHERE `id` BETWEEN @base_id + 1 AND @base_id + @performance_time_count
UNION ALL
SELECT
    'Seat' AS table_name,
    COUNT(*) AS benchmark_rows
FROM `Seat`
WHERE `id` BETWEEN @base_id + 1 AND @base_id + @seat_count
UNION ALL
SELECT
    'Reservation' AS table_name,
    COUNT(*) AS benchmark_rows
FROM `Reservation`
WHERE `id` BETWEEN @base_id + 1 AND @base_id + @reservation_count
UNION ALL
SELECT
    'ReservedSeat' AS table_name,
    COUNT(*) AS benchmark_rows
FROM `ReservedSeat`
WHERE `id` BETWEEN @base_id + 1 AND @base_id + @reserved_seat_count;

SELECT
    COUNT(*) AS expired_reservation_rows
FROM `Reservation`
WHERE
    `id` BETWEEN @base_id + 1 AND @base_id + @reservation_count
    AND `reservation_expired_time` < NOW();

SELECT
    'Next: run SHOW INDEX FROM Reservation and EXPLAIN ANALYZE on reservation_expired_time query.' AS next_step;
