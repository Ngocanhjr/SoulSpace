SELECT
    (SELECT COUNT(*) FROM users) AS total_users,
    (
        SELECT COUNT(*)
        FROM external_identities
        WHERE provider = 'GOOGLE'
    ) AS total_google_identities;


SELECT *
FROM external_identities

-- Kiểm tra tài khoản cụ thể
SELECT
    u.id,
    u.username,
    u.fullname,
    u.password_hash IS NOT NULL AS password_enabled,
    e.user_id IS NOT NULL AS google_linked,
    u.created_at,
    u.updated_at
FROM users u
         LEFT JOIN external_identities e
                   ON e.user_id = u.id
                       AND e.provider = 'GOOGLE'
WHERE u.username = 'username_can_kiem_tra';


-- Kiểm tra có user thiếu cả hai phương thức đăng nhập không
SELECT
    u.id,
    u.username
FROM users u
WHERE u.password_hash IS NULL
  AND NOT EXISTS (
    SELECT 1
    FROM external_identities e
    WHERE e.user_id = u.id
      AND e.provider = 'GOOGLE'
);
