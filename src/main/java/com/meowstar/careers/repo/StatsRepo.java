package com.meowstar.careers.repo;

import com.meowstar.careers.db.Rows;
import com.meowstar.careers.model.stats.BucketCount;
import com.meowstar.careers.model.stats.CompanyQuality;
import com.meowstar.careers.model.stats.MonthlyCount;
import com.meowstar.careers.model.stats.ReviewQueue;
import com.meowstar.careers.model.stats.ReviewerWorkload;
import com.meowstar.careers.model.stats.SeekerOverview;
import com.meowstar.careers.model.stats.SeekerStats;
import com.meowstar.careers.model.stats.StatusCount;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 统计查询（只读聚合）：审核工作量/及时性、用人单位优劣、求职用户分析。
 *
 * <p>对齐 Rust 版 {@code repositories::stats}。所有查询都只返回<b>聚合值</b>，不返回用户明细，
 * 避免统计接口变成批量导出通道。
 *
 * <p>注：Rust 侧这些方法不在事务内（各自一次查询），故这里也不加 {@code @Transactional}。
 *
 * <p><b>别名约定</b>：{@link Rows#of} 的 {@code DataClassRowMapper} 只认两种列名 ——
 * 记录组件名全小写、或「仅在每个大写字母前插下划线」的下划线形式。因此组件名里带数字的字段
 * （如 {@code reviewedLast7d}）不能照抄 Rust 的 {@code reviewed_last_7d} 别名，必须写成
 * {@code reviewed_last7d}（{@code complaintsLast30d} / {@code newLast7d} / {@code newLast30d} 同理），
 * 否则运行时 {@code rs.findColumn} 会失败并抛 500。这不影响对外 JSON 字段名。
 */
@Repository
public class StatsRepo {

    /**
     * 及时线：24 小时。改这一个常量即可让后端与前端展示同步（响应里也会回传 {@code slaSeconds}）。
     * 对齐 Rust {@code models::stats::REVIEW_SLA_SECONDS}。
     */
    public static final long REVIEW_SLA_SECONDS = 24 * 60 * 60;

    private final JdbcTemplate jdbc;

    public StatsRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 待审队列概览（{@code me} 用于算出「我自己持有几把锁」）。
     *
     * <p>绑定顺序 = Rust 占位符编号顺序（{@code $1}=me、{@code $2}=slaSeconds）。
     */
    public ReviewQueue reviewQueue(UUID me, long slaSeconds) {
        return jdbc.queryForObject(
                "SELECT count(*) FILTER (WHERE status = 'pending') AS pending_total, "
                        + "count(*) FILTER (WHERE status = 'pending' AND locked_by IS NOT NULL "
                        + "AND lock_expires_at > now()) AS locked_active, "
                        + "count(*) FILTER (WHERE status = 'pending' AND locked_by IS NOT NULL "
                        + "AND lock_expires_at <= now()) AS locked_expired, "
                        + "count(*) FILTER (WHERE status = 'pending' AND locked_by = ?) AS locked_by_me, "
                        + "count(*) FILTER (WHERE status = 'pending' "
                        + "AND created_at <= now() - (?::double precision * interval '1 second')) "
                        + "AS overdue_total, "
                        + "EXTRACT(EPOCH FROM (now() - min(created_at) FILTER (WHERE status = 'pending')))::float8 "
                        + "AS oldest_pending_seconds "
                        + "FROM complaints",
                Rows.of(ReviewQueue.class), me, (double) slaSeconds);
    }

    /**
     * 审核账号工作量与及时性；{@code only} 非空时只返回该账号（审核账号看自己，管理员传 {@code null} 看全部）。
     *
     * <p>注意：这里<b>刻意不返回</b>通过/驳回数量与结论分布——审核工作只按及时性衡量。
     *
     * <p>绑定顺序 = SQL 文本中 {@code ?} 出现的顺序：Rust 的 {@code $2}（及时线）先出现，
     * 之后才是 {@code $1}（账号过滤，出现两次）。
     */
    public List<ReviewerWorkload> reviewerWorkloads(UUID only, long slaSeconds) {
        return jdbc.query(
                "SELECT u.id AS reviewer_id, "
                        + "u.name AS reviewer_name, "
                        + "u.phone, "
                        + "u.is_active, "
                        + "COALESCE(l.active_locks, 0) AS active_locks, "
                        + "COALESCE(s.reviewed_total, 0) AS reviewed_total, "
                        + "COALESCE(s.reviewed_last7d, 0) AS reviewed_last7d, "
                        + "s.avg_total_seconds, "
                        + "s.median_total_seconds, "
                        + "s.p90_total_seconds, "
                        + "s.avg_handle_seconds, "
                        + "COALESCE(s.handle_sample, 0) AS handle_sample, "
                        + "COALESCE(s.on_time_total, 0) AS on_time_total, "
                        + "s.on_time_rate, "
                        + "s.last_reviewed_at "
                        + "FROM users u "
                        + "LEFT JOIN ( "
                        + "      SELECT cp.reviewed_by, "
                        + "             count(*) AS reviewed_total, "
                        + "             count(*) FILTER (WHERE cp.reviewed_at >= now() - interval '7 days') "
                        + "                 AS reviewed_last7d, "
                        + "             avg(EXTRACT(EPOCH FROM (cp.reviewed_at - cp.created_at)))::float8 "
                        + "                 AS avg_total_seconds, "
                        + "             (percentile_cont(0.5) WITHIN GROUP ( "
                        + "                 ORDER BY EXTRACT(EPOCH FROM (cp.reviewed_at - cp.created_at))::float8 "
                        + "             ))::float8 AS median_total_seconds, "
                        + "             (percentile_cont(0.9) WITHIN GROUP ( "
                        + "                 ORDER BY EXTRACT(EPOCH FROM (cp.reviewed_at - cp.created_at))::float8 "
                        + "             ))::float8 AS p90_total_seconds, "
                        + "             (avg(EXTRACT(EPOCH FROM (cp.reviewed_at - cp.review_started_at))) "
                        + "                 FILTER (WHERE cp.review_started_at IS NOT NULL))::float8 "
                        + "                 AS avg_handle_seconds, "
                        + "             count(*) FILTER (WHERE cp.review_started_at IS NOT NULL) AS handle_sample, "
                        + "             count(*) FILTER ( "
                        + "                 WHERE cp.reviewed_at - cp.created_at "
                        + "                       <= (?::double precision * interval '1 second') "
                        + "             ) AS on_time_total, "
                        + "             (count(*) FILTER ( "
                        + "                 WHERE cp.reviewed_at - cp.created_at "
                        + "                       <= (?::double precision * interval '1 second') "
                        + "             ))::float8 / NULLIF(count(*), 0) AS on_time_rate, "
                        + "             max(cp.reviewed_at) AS last_reviewed_at "
                        + "        FROM complaints cp "
                        + "       WHERE cp.reviewed_by IS NOT NULL AND cp.reviewed_at IS NOT NULL "
                        + "       GROUP BY cp.reviewed_by "
                        + "  ) s ON s.reviewed_by = u.id "
                        + "LEFT JOIN ( "
                        + "      SELECT cp.locked_by, count(*) AS active_locks "
                        + "        FROM complaints cp "
                        + "       WHERE cp.status = 'pending' "
                        + "         AND cp.locked_by IS NOT NULL "
                        + "         AND cp.lock_expires_at > now() "
                        + "       GROUP BY cp.locked_by "
                        + "  ) l ON l.locked_by = u.id "
                        + "WHERE u.role = 'reviewer' "
                        + "  AND (?::uuid IS NULL OR u.id = ?) "
                        + "ORDER BY s.avg_total_seconds ASC NULLS LAST, u.name ASC",
                Rows.of(ReviewerWorkload.class),
                (double) slaSeconds, (double) slaSeconds, only, only);
    }

    /** 用人单位质量画像：投诉结构 + 职位规模 + 等级序（按最差在前排序）。 */
    public List<CompanyQuality> companyQuality(long limit) {
        return jdbc.query(
                "SELECT c.id AS company_id, "
                        + "c.name, "
                        + "c.industry, "
                        + "c.location, "
                        + "COALESCE(cp.total, 0) AS complaints_total, "
                        + "COALESCE(cp.pending, 0) AS complaints_pending, "
                        + "COALESCE(cp.approved, 0) AS complaints_approved, "
                        + "COALESCE(cp.rejected, 0) AS complaints_rejected, "
                        + "COALESCE(cp.last_30d, 0) AS complaints_last30d, "
                        + "COALESCE(j.total, 0) AS jobs_total, "
                        + "COALESCE(j.active, 0) AS jobs_active, "
                        + "complaint_level_rank(c.complaints_count) AS level_rank, "
                        + "CASE WHEN COALESCE(j.active, 0) > 0 "
                        + "     THEN COALESCE(cp.approved, 0)::float8 / j.active::float8 "
                        + "     ELSE NULL END AS complaint_rate, "
                        // 质量分与等级同源：都用 companies.complaints_count（审核事务维护的权威计数），
                        // 避免出现「等级=严重 而 分数=100」这类自相矛盾的分析结论
                        + "GREATEST( "
                        + "    0, "
                        + "    100 - c.complaints_count * 10 - COALESCE(cp.pending, 0)::int * 2 "
                        + ") AS quality_score, "
                        + "cp.first_at AS first_complaint_at, "
                        + "cp.last_at AS last_complaint_at "
                        + "FROM companies c "
                        + "LEFT JOIN ( "
                        + "      SELECT company_id, "
                        + "             count(*) AS total, "
                        + "             count(*) FILTER (WHERE status = 'pending') AS pending, "
                        + "             count(*) FILTER (WHERE status = 'approved') AS approved, "
                        + "             count(*) FILTER (WHERE status = 'rejected') AS rejected, "
                        + "             count(*) FILTER (WHERE created_at >= now() - interval '30 days') AS last_30d, "
                        + "             min(created_at) AS first_at, "
                        + "             max(created_at) AS last_at "
                        + "        FROM complaints "
                        + "       GROUP BY company_id "
                        + "  ) cp ON cp.company_id = c.id "
                        + "LEFT JOIN ( "
                        + "      SELECT company_id, "
                        + "             count(*) AS total, "
                        + "             count(*) FILTER (WHERE is_active) AS active "
                        + "        FROM jobs "
                        + "       GROUP BY company_id "
                        + "  ) j ON j.company_id = c.id "
                        + "ORDER BY complaint_level_rank(c.complaints_count) DESC, "
                        + "         COALESCE(cp.approved, 0) DESC, "
                        + "         COALESCE(cp.total, 0) DESC, "
                        + "         c.name ASC "
                        + "LIMIT ?",
                Rows.of(CompanyQuality.class), limit);
    }

    /** 求职用户分析（账号 + 参与度 + 注册趋势 + 投递结构）。 */
    public SeekerStats seekerStats() {
        SeekerOverview overview = jdbc.queryForObject(
                "SELECT count(*) AS total, "
                        + "count(*) FILTER (WHERE is_active) AS active, "
                        + "count(*) FILTER (WHERE NOT is_active) AS disabled, "
                        + "count(*) FILTER (WHERE created_at >= now() - interval '7 days') AS new_last7d, "
                        + "count(*) FILTER (WHERE created_at >= now() - interval '30 days') AS new_last30d, "
                        + "(SELECT count(DISTINCT r.user_id) FROM resumes r) AS with_resume, "
                        + "(SELECT count(*) FROM resumes) AS resumes_total, "
                        + "(SELECT count(DISTINCT a.seeker_id) FROM applications a) AS with_application, "
                        + "(SELECT count(*) FROM applications) AS applications_total, "
                        + "(SELECT count(*) FROM interviews) AS interviews_total, "
                        + "(SELECT count(DISTINCT cp.complainant_id) FROM complaints cp) AS complainants, "
                        + "(SELECT count(*) FROM complaints) AS complaints_total "
                        + "FROM users "
                        + "WHERE role = 'seeker'",
                Rows.of(SeekerOverview.class));

        List<MonthlyCount> registrationsByMonth = jdbc.query(
                "SELECT to_char(date_trunc('month', created_at), 'YYYY-MM') AS month, "
                        + "count(*) AS count "
                        + "FROM users "
                        + "WHERE role = 'seeker' "
                        + "  AND created_at >= date_trunc('month', now()) - interval '11 months' "
                        + "GROUP BY 1 "
                        + "ORDER BY 1 ASC",
                Rows.of(MonthlyCount.class));

        List<StatusCount> applicationsByStatus = jdbc.query(
                "SELECT status, count(*) AS count "
                        + "FROM applications "
                        + "GROUP BY status "
                        + "ORDER BY count DESC, status ASC",
                Rows.of(StatusCount.class));

        List<BucketCount> applicationBuckets = jdbc.query(
                "SELECT CASE WHEN cnt = 0 THEN '0' "
                        + "             WHEN cnt <= 2 THEN '1-2' "
                        + "             WHEN cnt <= 5 THEN '3-5' "
                        + "             ELSE '6+' END AS bucket, "
                        + "       count(*) AS count "
                        + "FROM ( "
                        + "     SELECT u.id, count(a.id) AS cnt "
                        + "       FROM users u "
                        + "       LEFT JOIN applications a ON a.seeker_id = u.id "
                        + "      WHERE u.role = 'seeker' "
                        + "      GROUP BY u.id "
                        + "     ) t "
                        + "GROUP BY 1 "
                        + "ORDER BY 1 ASC",
                Rows.of(BucketCount.class));

        // Rust 用 `.unwrap_or(None)`：查询失败/无行时静默降级为 None，这里同样降级为 null。
        Double engagementRate;
        try {
            engagementRate = jdbc.queryForObject(
                    "SELECT (count(*) FILTER (WHERE r.user_id IS NOT NULL OR a.seeker_id IS NOT NULL))::float8 "
                            + "/ NULLIF(count(*), 0) "
                            + "FROM users u "
                            + "LEFT JOIN (SELECT DISTINCT user_id FROM resumes) r ON r.user_id = u.id "
                            + "LEFT JOIN (SELECT DISTINCT seeker_id FROM applications) a ON a.seeker_id = u.id "
                            + "WHERE u.role = 'seeker'",
                    Double.class);
        } catch (DataAccessException ex) {
            engagementRate = null;
        }

        Double complaintsPerComplainant = overview.complainants() > 0
                ? (double) overview.complaintsTotal() / (double) overview.complainants()
                : null;

        return new SeekerStats(overview, registrationsByMonth, applicationsByStatus, applicationBuckets,
                engagementRate, complaintsPerComplainant);
    }
}
