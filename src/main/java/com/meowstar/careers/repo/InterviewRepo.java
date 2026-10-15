package com.meowstar.careers.repo;

import com.meowstar.careers.db.Rows;
import com.meowstar.careers.error.ApiException;
import com.meowstar.careers.model.application.ApplicationView;
import com.meowstar.careers.model.interview.InterviewView;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 线上面试数据访问（对齐 Rust 版 {@code repositories::interview}）。
 * 每个查询都写全列清单，别名保持 snake_case 以便映射到 {@link InterviewView} 记录组件。
 */
@Repository
public class InterviewRepo {

    /** 面试视图的公共联表 SELECT（职位/企业/双方姓名），与 Rust {@code get_view} 一致。 */
    private static final String VIEW_SELECT =
            "SELECT i.id, i.application_id, "
                    + "j.id AS job_id, j.title AS job_title, "
                    + "c.id AS company_id, c.name AS company_name, "
                    + "hr.id AS interviewer_id, hr.name AS interviewer_name, "
                    + "sk.id AS interviewee_id, sk.name AS interviewee_name, "
                    + "i.status, i.scheduled_at, i.started_at, i.ended_at, "
                    + "i.created_at, i.updated_at "
                    + "FROM interviews i "
                    + "JOIN applications a ON a.id = i.application_id "
                    + "JOIN jobs j ON j.id = a.job_id "
                    + "JOIN companies c ON c.id = j.company_id "
                    + "JOIN users hr ON hr.id = i.interviewer_id "
                    + "JOIN users sk ON sk.id = i.interviewee_id ";

    /** 投递视图 PUBLIC 联表 SELECT（与 Rust {@code repositories::application::get_view} 逐列一致）。 */
    private static final String APPLICATION_SELECT =
            "SELECT a.id, a.job_id, j.title AS job_title, c.id AS company_id, c.name AS company_name, "
                    + "a.seeker_id, u.name AS seeker_name, u.email AS seeker_email, u.phone AS seeker_phone, "
                    + "a.resume_id, a.cover_letter, a.status, a.created_at, a.updated_at "
                    + "FROM applications a "
                    + "JOIN jobs j ON j.id = a.job_id "
                    + "JOIN companies c ON c.id = j.company_id "
                    + "JOIN users u ON u.id = a.seeker_id ";

    private final JdbcTemplate jdbc;

    public InterviewRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 某用户在该面试中的对方（信令转发用）；非参与者返回空。
     *
     * <p>WebSocket 层契约方法（PORTING.md §6）。
     */
    public Optional<UUID> peerOf(UUID interviewId, UUID userId) {
        List<UUID[]> rows = jdbc.query(
                "SELECT interviewer_id, interviewee_id FROM interviews WHERE id = ?",
                (rs, rowNum) -> new UUID[] {
                        rs.getObject("interviewer_id", UUID.class),
                        rs.getObject("interviewee_id", UUID.class)},
                interviewId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        UUID[] row = rows.get(0);
        if (row[0].equals(userId)) {
            return Optional.of(row[1]);
        }
        if (row[1].equals(userId)) {
            return Optional.of(row[0]);
        }
        return Optional.empty();
    }

    /** 单个面试视图。 */
    public Optional<InterviewView> get(UUID id) {
        return jdbc.query(VIEW_SELECT + "WHERE i.id = ?", Rows.of(InterviewView.class), id)
                .stream().findFirst();
    }

    /** 我参与的面试（招聘者或求职者），最新优先。 */
    public List<InterviewView> mine(UUID userId) {
        return jdbc.query(VIEW_SELECT + "WHERE hr.id = ? OR sk.id = ? ORDER BY i.updated_at DESC",
                Rows.of(InterviewView.class), userId, userId);
    }

    /**
     * 发起面试（招聘者对其企业收到的投递）；同一投递活跃面试唯一。
     * 先查活跃面试（409），再由部分唯一索引兜底（并发下同样转 409）。
     */
    @Transactional
    public InterviewView create(ApplicationView application, UUID interviewerId, OffsetDateTime scheduledAt) {
        Boolean active = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM interviews "
                        + "WHERE application_id = ? AND status IN ('invited','in_progress'))",
                Boolean.class, application.id());
        if (Boolean.TRUE.equals(active)) {
            throw ApiException.conflict("该投递已有一个进行中的面试");
        }

        UUID id;
        try {
            id = jdbc.queryForObject(
                    "INSERT INTO interviews "
                            + "(application_id, job_id, company_id, interviewer_id, interviewee_id, "
                            + " status, scheduled_at, created_by) "
                            + "VALUES (?, ?, ?, ?, ?, 'invited', ?, ?) RETURNING id",
                    UUID.class,
                    application.id(),
                    application.jobId(),
                    application.companyId(),
                    interviewerId,
                    application.seekerId(),
                    scheduledAt,
                    interviewerId);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("该投递已有一个进行中的面试");
        }

        return get(id).orElseThrow(() -> ApiException.internal("面试创建成功但读取失败"));
    }

    /** 状态 → in_progress；未参与者 403，状态不允许/未到预约时间 400，不存在返回空（404）。 */
    @Transactional
    public Optional<InterviewView> start(UUID id, UUID userId) {
        InterviewView view = get(id).orElse(null);
        if (view == null) {
            return Optional.empty();
        }
        memberGuard(view, userId, List.of("invited"), "开始面试");
        if (view.scheduledAt() != null
                && view.scheduledAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1))) {
            throw ApiException.badRequest("未到预约时间，暂不能进入面试");
        }
        return updateAndReload(
                "UPDATE interviews SET status = 'in_progress', started_at = now(), updated_at = now() "
                        + "WHERE id = ? RETURNING id",
                id);
    }

    /** 状态 → finished（仅 in_progress 生效，invited 直接结束保持原状态但刷新 updated_at）。 */
    @Transactional
    public Optional<InterviewView> finish(UUID id, UUID userId) {
        InterviewView view = get(id).orElse(null);
        if (view == null) {
            return Optional.empty();
        }
        memberGuard(view, userId, List.of("invited", "in_progress"), "结束面试");
        return updateAndReload(
                "UPDATE interviews SET "
                        + "status = CASE WHEN status = 'in_progress' THEN 'finished' ELSE status END, "
                        + "ended_at = CASE WHEN status = 'in_progress' THEN now() ELSE ended_at END, "
                        + "updated_at = now() "
                        + "WHERE id = ? RETURNING id",
                id);
    }

    /** 状态 → cancelled。 */
    @Transactional
    public Optional<InterviewView> cancel(UUID id, UUID userId) {
        InterviewView view = get(id).orElse(null);
        if (view == null) {
            return Optional.empty();
        }
        memberGuard(view, userId, List.of("invited"), "取消面试");
        return updateAndReload(
                "UPDATE interviews SET status = 'cancelled', ended_at = now(), updated_at = now() "
                        + "WHERE id = ? RETURNING id",
                id);
    }

    /**
     * 单个投递视图：SQL 与字段与 Rust {@code repositories::application::get_view} 完全一致。
     * （内联于此以避免对并行开发中的 ApplicationRepo 产生编译期依赖。）
     */
    public Optional<ApplicationView> findApplicationView(UUID applicationId) {
        return jdbc.query(APPLICATION_SELECT + "WHERE a.id = ?",
                Rows.of(ApplicationView.class), applicationId).stream().findFirst();
    }

    /** 参与者校验 + 状态机校验（文案与 Rust {@code member_guard} 逐字一致）。 */
    private static void memberGuard(InterviewView view, UUID userId, List<String> allowed, String action) {
        boolean isMember = view.interviewerId().equals(userId) || view.intervieweeId().equals(userId);
        if (!isMember) {
            throw ApiException.forbidden("非面试参与者");
        }
        if (!allowed.contains(view.status())) {
            throw ApiException.badRequest("当前状态（" + view.status() + "）不允许" + action);
        }
    }

    /** 执行 UPDATE ... RETURNING id；无更新行时返回空，否则重新读取视图（对齐 Rust {@code reload_after_update}）。 */
    private Optional<InterviewView> updateAndReload(String sql, UUID id) {
        Optional<UUID> updated = jdbc.query(sql, (rs, rowNum) -> rs.getObject("id", UUID.class), id)
                .stream().findFirst();
        return updated.isEmpty() ? Optional.empty() : get(id);
    }
}
