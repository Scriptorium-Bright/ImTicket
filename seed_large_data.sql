-- MySQL의 재귀 한도를 늘려서 10,000번 반복이 가능하도록 설정합니다.
SET SESSION cte_max_recursion_depth = 100000;

-- 1. 더미 유저 생성 (존재하지 않으면 삽입)
INSERT IGNORE INTO Member (wallet_address, wallet_verified, user_role)
VALUES ('0xTestUser_Dummy', 1, 'ROLE_USER');

-- 2. Venue 생성
INSERT INTO Venue (performance_venue_name, performance_place_address, phoneNumber)
VALUES (CONCAT('Load Test Venue ', UUID()), 'Seoul', '02-123-4567');
SET @venue_id = LAST_INSERT_ID();

-- 3. VenueHall 생성
INSERT INTO VenueHall (venue_id, venuehall_name, venuehall_total_seats)
VALUES (@venue_id, 'Load Test Hall', 10000);
SET @venuehall_id = LAST_INSERT_ID();

-- 4. Performance 생성
INSERT INTO Performance (performance_title, description, venue_type)
VALUES ('Load Test Performance', 'Load Test', 'CONCERT');
SET @performance_id = LAST_INSERT_ID();

-- 5. PerformanceTime 생성
INSERT INTO PerformanceTime (performance_id, venuehall_id, performance_start_date, performance_start_time)
VALUES (@performance_id, @venuehall_id, CURDATE() + INTERVAL 1 DAY, '19:00:00');
SET @performance_time_id = LAST_INSERT_ID();

-- 6. SeatPrice 생성
-- 'VIP'의 MySQL ENUM index는 6인데, Hibernate가 CHECK(seat_type between 0 and 5)를 걸어버려서 에러가 발생합니다.
-- 이를 우회하기 위해 인덱스가 1~5 사이에 속하는 'A' 등급(인덱스 1)을 사용합니다.
INSERT INTO SeatPrice (performance_id, seat_type, seat_price)
VALUES (@performance_id, 'A', 150000);

-- 7. Seat 10,000개 일괄 생성 (JPA 시퀀스 테이블 직접 조작)
SELECT @next_seat_id := next_val FROM Seat_SEQ FOR UPDATE;
UPDATE Seat_SEQ SET next_val = next_val + 10000;

INSERT INTO Seat (id, performance_time_id, seat_floor, seat_section, seat_row, seat_number, seat_status, seat_type, is_reservation, seat_price)
WITH RECURSIVE seq AS (
  SELECT 0 AS n
  UNION ALL
  SELECT n + 1 FROM seq WHERE n < 9999
)
SELECT @next_seat_id + n, @performance_time_id, 1, 'A', 1, n+1, 'AVAILABLE', 'A', 0, 150000
FROM seq;

-- 터미널에 생성된 performance_time_id를 출력 (k6 스크립트에서 사용)
SELECT @performance_time_id AS target_performance_time_id;
