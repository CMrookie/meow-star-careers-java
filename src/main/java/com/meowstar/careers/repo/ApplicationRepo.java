package com.meowstar.careers.repo;

import com.meowstar.careers.db.Rows;
import com.meowstar.careers.error.ApiException;
import com.meowstar.careers.model.application.ApplicationPage;
import com.meowstar.careers.model.application.ApplicationQuery;
import com.meowstar.careers.model.application.ApplicationView;
import com.meowstar.careers.model.pagination.Paginate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 投递数据访问（对齐 Rust 版 {@code repositories::application}）。
 * 联表视图的列别名保持 snake_case，交给 {@link Rows} 的 DataClassRowMapper 自动映射。
 */
@Repository
public class ApplicationRepo {

    /** applications 联表视图的公共列（Rust 三个 SELECT 用的是同一份列清单）。 */
    private static final String VIEW_COLUMNS =
            "a.id, a.job_id, j.title AS job_title, c.id AS company_id, c.name AS company_name, "
                    + "a.seeker_id, u.name AS seeker_name, u.email AS seeker_email, u.phone AS seeker_phone, "
                    + "a.resume_id, a.cover_letter, a.status, a.created_at, a.updated_at";

    /** 联表视图的 FROM/JOIN 片段（投递 + 职位 + 企业 + 求职者）。 */
    private static final String VIEW_FROM =
            " FROM applications a"
                    + " JOIN jobs j ON j.id = a.job_id"
                    + " JOIN companies c ON c.id = j.company_id"
                    + " JOIN users u ON u.id = a.seeker_id";

    private final JdbcTemplate jdbc;

    public ApplicationRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 职位是否在招：等价 Rust 的 {@code job_repo::get_active(...).is_none()} 判断。
     * get_active 只多联了 companies（jobs.company_id 是 NOT NULL 外键，内联不改变结果集），
     * 为不跨模块依赖 JobRepo，这里在本仓储内实现同一过滤条件。
     */
    public boolean jobIsActive(UUID jobId) {
        Boolean active = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM jobs j JOIN companies c ON c.id = j.company_id "
                        + "WHERE j.id = ? AND j.is_active = true)",
                Boolean.class, jobId);
        return Boolean.TRUE.equals(active);
    }

    /** 该求职者是否已投递某职位（返回投递视图）。 */
    public Optional<ApplicationView> findByJobAndSeeker(UUID jobId, UUID seekerId) {
        return jdbc.query("SELECT " + VIEW_COLUMNS + VIEW_FROM + " WHERE a.job_id = ? AND a.seeker_id = ?",
                Rows.of(ApplicationView.class), jobId, seekerId).stream().findFirst();
    }

    /** 创建投递并返回联表视图：引用的简历必须属于本人。 */
    @Transactional
    public ApplicationView apply(UUID jobId, UUID seekerId, UUID resumeId, String coverLetter) {
        // 引用的简历必须属于本人
        if (resumeId != null) {
            Boolean owned = jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM resumes WHERE id = ? AND user_id = ?)",
                    Boolean.class, resumeId, seekerId);
            if (!Boolean.TRUE.equals(owned)) {
                throw ApiException.forbidden("简历不属于当前用户");
            }
        }

        UUID id = jdbc.queryForObject(
                "INSERT INTO applications (job_id, seeker_id, resume_id, cover_letter) "
                        + "VALUES (?, ?, ?, ?) RETURNING id",
                UUID.class, jobId, seekerId, resumeId, coverLetter);

        return getView(Objects.requireNonNull(id))
                .orElseThrow(() -> ApiException.internal("投递成功但读取失败"));
    }

    /** 单个投递视图。 */
    public Optional<ApplicationView> getView(UUID id) {
        return jdbc.query("SELECT " + VIEW_COLUMNS + VIEW_FROM + " WHERE a.id = ?",
                Rows.of(ApplicationView.class), id).stream().findFirst();
    }

    /** 求职者的投递列表（可按职位/状态过滤）。 */
    public ApplicationPage listForSeeker(UUID seekerId, ApplicationQuery query) {
        Paginate.Page page = Paginate.paginate(query.page(), query.pageSize());

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM applications"
                        + " WHERE seeker_id = ?"
                        + " AND (?::uuid IS NULL OR job_id = ?)"
                        + " AND (?::text IS NULL OR status = ?)",
                Long.class, seekerId, query.jobId(), query.jobId(), query.status(), query.status());

        List<ApplicationView> items = jdbc.query(
                "SELECT " + VIEW_COLUMNS + VIEW_FROM
                        + " WHERE a.seeker_id = ?"
                        + " AND (?::uuid IS NULL OR a.job_id = ?)"
                        + " AND (?::text IS NULL OR a.status = ?)"
                        + " ORDER BY a.created_at DESC LIMIT ? OFFSET ?",
                Rows.of(ApplicationView.class),
                seekerId, query.jobId(), query.jobId(), query.status(), query.status(),
                page.pageSize(), page.offset());

        return new ApplicationPage(items, total == null ? 0L : total, page.page(), page.pageSize());
    }

    /** 企业收到的投递列表（本企业职位下）。 */
    public ApplicationPage listForCompany(UUID companyId, ApplicationQuery query) {
        Paginate.Page page = Paginate.paginate(query.page(), query.pageSize());

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM applications a JOIN jobs j ON j.id = a.job_id"
                        + " WHERE j.company_id = ?"
                        + " AND (?::uuid IS NULL OR a.job_id = ?)"
                        + " AND (?::text IS NULL OR a.status = ?)",
                Long.class, companyId, query.jobId(), query.jobId(), query.status(), query.status());

        List<ApplicationView> items = jdbc.query(
                "SELECT " + VIEW_COLUMNS + VIEW_FROM
                        + " WHERE j.company_id = ?"
                        + " AND (?::uuid IS NULL OR a.job_id = ?)"
                        + " AND (?::text IS NULL OR a.status = ?)"
                        + " ORDER BY a.created_at DESC LIMIT ? OFFSET ?",
                Rows.of(ApplicationView.class),
                companyId, query.jobId(), query.jobId(), query.status(), query.status(),
                page.pageSize(), page.offset());

        return new ApplicationPage(items, total == null ? 0L : total, page.page(), page.pageSize());
    }

    /** 更新投递状态，返回更新后的视图（空表示投递不存在）。 */
    @Transactional
    public Optional<ApplicationView> updateStatus(UUID id, String status) {
        int updated = jdbc.update(
                "UPDATE applications SET status = ?, updated_at = now() WHERE id = ?", status, id);
        if (updated == 0) {
            return Optional.empty();
        }
        return getView(id);
    }
}
