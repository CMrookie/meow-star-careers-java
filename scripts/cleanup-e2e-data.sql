-- 清理 scripts/e2e.sh 在共享库（appdb）里留下的测试数据。
-- 只按 e2e 固定使用的名称/邮箱模式精确匹配，不会碰到 Rust 版的联调数据：
--   users:     求职者甲/乙/丙、HR-A/B/C、审核员甲/乙/丙、maker-*@example.com（管理员创建的邮箱账号）
--   companies: 公司A-*、公司B-*、公司C-*
-- 用法：docker exec -i pgdb psql -U postgres -d appdb -v ON_ERROR_STOP=1 < scripts/cleanup-e2e-data.sql
BEGIN;

CREATE TEMP TABLE e2e_users ON COMMIT DROP AS
SELECT id FROM users
 WHERE name LIKE '求职者甲%' OR name LIKE '求职者乙%' OR name LIKE '求职者丙%'
    OR name IN ('HR-A', 'HR-B', 'HR-C')
    OR name IN ('审核员甲', '审核员乙', '审核员丙')
    OR email LIKE 'maker-%@example.com';

CREATE TEMP TABLE e2e_companies ON COMMIT DROP AS
SELECT id FROM companies
 WHERE name LIKE '公司A-%' OR name LIKE '公司B-%' OR name LIKE '公司C-%';

DELETE FROM complaints
 WHERE company_id IN (SELECT id FROM e2e_companies)
    OR complainant_id IN (SELECT id FROM e2e_users);

DELETE FROM interviews
 WHERE application_id IN (SELECT id FROM applications
                           WHERE job_id IN (SELECT id FROM jobs WHERE company_id IN (SELECT id FROM e2e_companies))
                              OR seeker_id IN (SELECT id FROM e2e_users))
    OR interviewer_id IN (SELECT id FROM e2e_users)
    OR interviewee_id IN (SELECT id FROM e2e_users);

DELETE FROM applications
 WHERE job_id IN (SELECT id FROM jobs WHERE company_id IN (SELECT id FROM e2e_companies))
    OR seeker_id IN (SELECT id FROM e2e_users);

DELETE FROM messages
 WHERE conversation_id IN (SELECT id FROM conversations
                            WHERE user_lo IN (SELECT id FROM e2e_users)
                               OR user_hi IN (SELECT id FROM e2e_users));

DELETE FROM conversations
 WHERE user_lo IN (SELECT id FROM e2e_users) OR user_hi IN (SELECT id FROM e2e_users);

DELETE FROM saved_jobs
 WHERE user_id IN (SELECT id FROM e2e_users)
    OR job_id IN (SELECT id FROM jobs WHERE company_id IN (SELECT id FROM e2e_companies));

DELETE FROM resumes WHERE user_id IN (SELECT id FROM e2e_users);
DELETE FROM device_push_tokens WHERE user_id IN (SELECT id FROM e2e_users);
DELETE FROM jobs WHERE company_id IN (SELECT id FROM e2e_companies)
                   OR created_by IN (SELECT id FROM e2e_users);
DELETE FROM companies WHERE id IN (SELECT id FROM e2e_companies);
DELETE FROM auth_tokens WHERE user_id IN (SELECT id FROM e2e_users);
DELETE FROM users WHERE id IN (SELECT id FROM e2e_users);

COMMIT;
