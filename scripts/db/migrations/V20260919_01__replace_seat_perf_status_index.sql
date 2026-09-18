-- Experimental Seat index change derived from the 60k EXPLAIN experiment.
--
-- Current read-model queries filter by performance_time_id and order by Seat.id.
-- No current SeatRepository hot path filters by seat_status.
--
-- Create the query-aligned index first so the performance_time_id FK/index prefix
-- remains available before dropping the previous composite index.

SET @schema_name := DATABASE();

SELECT COUNT(*) INTO @has_new_index
FROM information_schema.statistics
WHERE table_schema = @schema_name
  AND table_name = 'Seat'
  AND index_name = 'idx_seat_perf';

SET @create_sql := IF(
    @has_new_index = 0,
    'CREATE INDEX idx_seat_perf ON Seat(performance_time_id)',
    'DO 0'
);
PREPARE create_stmt FROM @create_sql;
EXECUTE create_stmt;
DEALLOCATE PREPARE create_stmt;

SELECT COUNT(*) INTO @has_old_index
FROM information_schema.statistics
WHERE table_schema = @schema_name
  AND table_name = 'Seat'
  AND index_name = 'idx_seat_perf_status';

SET @drop_sql := IF(
    @has_old_index > 0,
    'DROP INDEX idx_seat_perf_status ON Seat',
    'DO 0'
);
PREPARE drop_stmt FROM @drop_sql;
EXECUTE drop_stmt;
DEALLOCATE PREPARE drop_stmt;

SHOW INDEX FROM Seat;
