-- Seat read-model index experiment fixture.
-- Compares the current ImTicket index shape with a query-aligned candidate.
-- Dataset: one performance time with @seat_count seats (default 60,000).

SET @performance_time_id := COALESCE(@performance_time_id, 900000001);
SET @seat_count := COALESCE(@seat_count, 60000);

DROP TABLE IF EXISTS SeatCurrentIndex;
DROP TABLE IF EXISTS SeatPerfIndex;
DROP TEMPORARY TABLE IF EXISTS benchmark_numbers;

CREATE TEMPORARY TABLE benchmark_numbers (
    n INT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;

INSERT INTO benchmark_numbers (n)
SELECT
    d0.i + d1.i * 10 + d2.i * 100 + d3.i * 1000 + d4.i * 10000 + 1
FROM
    (SELECT 0 i UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
     UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d0
CROSS JOIN
    (SELECT 0 i UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
     UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d1
CROSS JOIN
    (SELECT 0 i UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
     UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d2
CROSS JOIN
    (SELECT 0 i UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
     UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d3
CROSS JOIN
    (SELECT 0 i UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
     UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d4
WHERE
    d0.i + d1.i * 10 + d2.i * 100 + d3.i * 1000 + d4.i * 10000 + 1 <= @seat_count;

CREATE TABLE SeatCurrentIndex (
    id BIGINT NOT NULL,
    seat_floor INT NOT NULL,
    seat_section VARCHAR(16) NOT NULL,
    seat_row INT NOT NULL,
    seat_number INT NOT NULL,
    seat_type VARCHAR(16) NOT NULL,
    seat_price INT NOT NULL,
    is_reservation BIT(1) NOT NULL,
    seat_status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL,
    performance_time_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    KEY idx_seat_perf_status (performance_time_id, seat_status)
) ENGINE=InnoDB;

CREATE TABLE SeatPerfIndex (
    id BIGINT NOT NULL,
    seat_floor INT NOT NULL,
    seat_section VARCHAR(16) NOT NULL,
    seat_row INT NOT NULL,
    seat_number INT NOT NULL,
    seat_type VARCHAR(16) NOT NULL,
    seat_price INT NOT NULL,
    is_reservation BIT(1) NOT NULL,
    seat_status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL,
    performance_time_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    KEY idx_seat_perf (performance_time_id)
) ENGINE=InnoDB;

INSERT INTO SeatCurrentIndex (
    id, seat_floor, seat_section, seat_row, seat_number,
    seat_type, seat_price, is_reservation, seat_status, version, performance_time_id
)
SELECT
    900000000 + n,
    1 + MOD(n, 3),
    CHAR(65 + MOD(n, 6)),
    1 + MOD(FLOOR((n - 1) / 100), 50),
    1 + MOD(n - 1, 100),
    CASE MOD(n, 6)
        WHEN 0 THEN 'VIP'
        WHEN 1 THEN 'R'
        WHEN 2 THEN 'S'
        WHEN 3 THEN 'A'
        WHEN 4 THEN 'B'
        ELSE 'C'
    END,
    30000 + MOD(n, 6) * 20000,
    IF(MOD(n, 10) < 3, b'1', b'0'),
    CASE WHEN MOD(n, 10) < 3 THEN 'LOCKED' ELSE 'AVAILABLE' END,
    0,
    @performance_time_id
FROM benchmark_numbers;

INSERT INTO SeatPerfIndex
SELECT * FROM SeatCurrentIndex;

ANALYZE TABLE SeatCurrentIndex, SeatPerfIndex;

SELECT 'fixture' AS section, COUNT(*) AS seat_count, MIN(id) AS min_id, MAX(id) AS max_id
FROM SeatCurrentIndex;

SELECT 'current-indexes' AS section;
SHOW INDEX FROM SeatCurrentIndex;

SELECT 'candidate-indexes' AS section;
SHOW INDEX FROM SeatPerfIndex;

SELECT 'current-layout-json-plan' AS section;
EXPLAIN FORMAT=JSON
SELECT id, seat_floor, seat_section, seat_row, seat_number, seat_type, seat_price, is_reservation
FROM SeatCurrentIndex
WHERE performance_time_id = @performance_time_id
ORDER BY id;

SELECT 'candidate-layout-json-plan' AS section;
EXPLAIN FORMAT=JSON
SELECT id, seat_floor, seat_section, seat_row, seat_number, seat_type, seat_price, is_reservation
FROM SeatPerfIndex
WHERE performance_time_id = @performance_time_id
ORDER BY id;

SELECT 'current-availability-json-plan' AS section;
EXPLAIN FORMAT=JSON
SELECT id, seat_status, version
FROM SeatCurrentIndex
WHERE performance_time_id = @performance_time_id
ORDER BY id;

SELECT 'candidate-availability-json-plan' AS section;
EXPLAIN FORMAT=JSON
SELECT id, seat_status, version
FROM SeatPerfIndex
WHERE performance_time_id = @performance_time_id
ORDER BY id;

SELECT 'current-layout-analyze' AS section;
EXPLAIN ANALYZE
SELECT id, seat_floor, seat_section, seat_row, seat_number, seat_type, seat_price, is_reservation
FROM SeatCurrentIndex
WHERE performance_time_id = @performance_time_id
ORDER BY id;

SELECT 'candidate-layout-analyze' AS section;
EXPLAIN ANALYZE
SELECT id, seat_floor, seat_section, seat_row, seat_number, seat_type, seat_price, is_reservation
FROM SeatPerfIndex
WHERE performance_time_id = @performance_time_id
ORDER BY id;

SELECT 'current-availability-analyze' AS section;
EXPLAIN ANALYZE
SELECT id, seat_status, version
FROM SeatCurrentIndex
WHERE performance_time_id = @performance_time_id
ORDER BY id;

SELECT 'candidate-availability-analyze' AS section;
EXPLAIN ANALYZE
SELECT id, seat_status, version
FROM SeatPerfIndex
WHERE performance_time_id = @performance_time_id
ORDER BY id;
