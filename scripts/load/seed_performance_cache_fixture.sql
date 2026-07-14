SET SESSION group_concat_max_len = 8192;
SET @fixture_marker = 'IMT_CACHE_LOAD_FIXTURE_V1';
SET @fixture_image = 'imticket-cache-load-fixture-v1.jpg';
SET @fixture_title_prefix = 'Cache Load Fixture V1 ';
START TRANSACTION;

DELETE sp
FROM SeatPrice sp
JOIN Performance p ON p.id = sp.performance_id
WHERE p.description = @fixture_marker
  AND p.image_url = @fixture_image
  AND p.performance_title LIKE CONCAT(@fixture_title_prefix, '%')
  AND p.organizer_id IS NULL;

DELETE pt
FROM PerformanceTime pt
JOIN Performance p ON p.id = pt.performance_id
WHERE p.description = @fixture_marker
  AND p.image_url = @fixture_image
  AND p.performance_title LIKE CONCAT(@fixture_title_prefix, '%')
  AND p.organizer_id IS NULL;

DELETE FROM Performance
WHERE description = @fixture_marker
  AND image_url = @fixture_image
  AND performance_title LIKE CONCAT(@fixture_title_prefix, '%')
  AND organizer_id IS NULL;

INSERT INTO Performance (
    visible_age,
    description,
    image_url,
    performance_title,
    venue_type,
    performance_start_date,
    performance_end_date
)
SELECT
    12,
    @fixture_marker,
    @fixture_image,
    CONCAT(@fixture_title_prefix, LPAD(numbers.n, 3, '0')),
    'CONCERT',
    CURDATE(),
    CURDATE() + INTERVAL 30 DAY
FROM (
    SELECT ones.n + tens.n * 10 + 1 AS n
    FROM (
        SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
        UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
    ) ones
    CROSS JOIN (
        SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
        UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
    ) tens
) numbers
ORDER BY numbers.n;

INSERT INTO PerformanceTime (
    performance_start_date,
    performance_start_time,
    performance_id,
    venuehall_id
)
SELECT
    CURDATE() + INTERVAL 7 DAY,
    '19:00:00',
    p.id,
    NULL
FROM Performance p
WHERE p.description = @fixture_marker
  AND p.image_url = @fixture_image
  AND p.performance_title LIKE CONCAT(@fixture_title_prefix, '%')
  AND p.organizer_id IS NULL
ORDER BY p.id;

INSERT INTO SeatPrice (
    seat_type,
    seat_price,
    performance_id
)
SELECT
    'A',
    100000,
    p.id
FROM Performance p
WHERE p.description = @fixture_marker
  AND p.image_url = @fixture_image
  AND p.performance_title LIKE CONCAT(@fixture_title_prefix, '%')
  AND p.organizer_id IS NULL
ORDER BY p.id;

COMMIT;

SELECT
    COUNT(*) AS fixture_count,
    GROUP_CONCAT(p.id ORDER BY p.id SEPARATOR ',') AS performance_ids
FROM Performance p
WHERE p.description = @fixture_marker
  AND p.image_url = @fixture_image
  AND p.performance_title LIKE CONCAT(@fixture_title_prefix, '%')
  AND p.organizer_id IS NULL;
