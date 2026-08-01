-- Lightweight, repeatable data for the public home Feed pressure test.
-- Run only after 00-init-linzhi-perf.sql against the isolated linzhi_perf database.
-- It inserts 20 authors and 500 published public posts. Existing seed IDs are ignored.

USE linzhi_perf;

-- Twenty distinct authors satisfy the Feed query's users inner join.
INSERT IGNORE INTO users (
    id, phone, account, password_hash, nickname, avatar, status, role, created_at, updated_at
)
SELECT
    900000000000000000 + n,
    CONCAT('139', LPAD(n, 8, '0')),
    CONCAT('perf_author_', n),
    '$2a$10$7EqJtq98hPqEX7fNZaFWoO8GI5L6H1qVp2XlB9m8yDZa4Rn3Rp7Gm',
    CONCAT('Perf Author ', n),
    CONCAT('https://perf.local/avatar/', n, '.png'),
    'active',
    'USER',
    DATE_SUB(NOW(), INTERVAL n DAY),
    NOW()
FROM (
    SELECT ones.n + tens.n * 10 + hundreds.n * 100 + 1 AS n
    FROM (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
          UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) ones
    CROSS JOIN (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
                UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) tens
    CROSS JOIN (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
                UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) hundreds
) numbers
WHERE n BETWEEN 1 AND 20;

-- Five hundred recent posts make page 1 a stable hot key while retaining realistic ordering.
INSERT IGNORE INTO know_posts (
    id, tag_id, tags, title, description,
    latitude, longitude, geo_hash, address,
    creator_id, is_top, type, visible, img_urls, status,
    created_at, updated_at, publish_time
)
SELECT
    900000000100000000 + n,
    1 + MOD(n, 5),
    '["performance", "feed"]',
    CONCAT('Feed pressure post ', n),
    CONCAT('Hot Feed sample record ', n),
    31.2304 + MOD(n, 20) * 0.001,
    121.4737 + MOD(n, 20) * 0.001,
    'wtw3sj',
    'Performance test data only',
    900000000000000000 + 1 + MOD(n - 1, 20),
    CASE WHEN n <= 5 THEN TRUE ELSE FALSE END,
    'image_text',
    'public',
    '["https://perf.local/image/feed.jpg"]',
    'published',
    DATE_SUB(NOW(), INTERVAL n MINUTE),
    DATE_SUB(NOW(), INTERVAL n MINUTE),
    DATE_SUB(NOW(), INTERVAL n MINUTE)
FROM (
    SELECT ones.n + tens.n * 10 + hundreds.n * 100 + 1 AS n
    FROM (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
          UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) ones
    CROSS JOIN (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
                UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) tens
    CROSS JOIN (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
                UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) hundreds
) numbers
WHERE n BETWEEN 1 AND 500;

SELECT COUNT(*) AS perf_author_count
FROM users
WHERE id BETWEEN 900000000000000001 AND 900000000000000020;

SELECT COUNT(*) AS published_feed_post_count
FROM know_posts
WHERE id BETWEEN 900000000100000001 AND 900000000100000500
  AND status = 'published'
  AND visible = 'public';
