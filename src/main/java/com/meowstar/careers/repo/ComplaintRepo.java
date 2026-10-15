package com.meowstar.careers.repo;

import com.meowstar.careers.db.Rows;
import com.meowstar.careers.error.ApiException;
import com.meowstar.careers.model.complaint.ComplaintRules;
import com.meowstar.careers.model.complaint.ComplaintView;
import java.sql.Array;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 投诉数据访问（对齐 Rust 版 repositories::complaint）。
 *
 * <p>查询统一走只读视图 complaint_views（联表企业/投诉人/审核人/持锁人并算出锁是否有效），
 * 认领、释放、审结都是带条件的 UPDATE，由数据库保证并发下只有一个审核能成功。
 */
@Repository
public class ComplaintRepo {

    /** 认领锁的租约（秒）：审核端在弹窗期间按此周期续约。 */
    public static final long LOCK_TTL_SECONDS = 10 * 60;

    /** 锁到期时间在冲突文案里的格式，与 Rust chrono 的 %Y-%m-%d %H:%M:%S UTC 一致。 */
    private static final DateTimeFormatter LOCK_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ComplaintRepo(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** 视图行映射（列名全部 snake_case；lock_active 由 DataClassRowMapper 映射到 lockActive）。 */
    private static RowMapper<ComplaintView> viewMapper() {
        return Rows.of(ComplaintView.class);
    }

    private Optional<ComplaintView> getView(UUID id) {
        return jdbc.query("SELECT * FROM complaint_views WHERE id = ?", viewMapper(), id)
                .stream().findFirst();
    }

    /** 企业是否存在。 */
    public boolean companyExists(UUID companyId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM companies WHERE id = ?)", Boolean.class, companyId);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * 是否与目标企业有过“实际交流”：存在会话且该会话里至少一条消息，
     * 会话对端为企业任一招聘者账号。
     */
    public boolean hasExchange(UUID complainant, UUID companyId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS ("
                        + "   SELECT 1"
                        + "     FROM conversations cv"
                        + "     JOIN messages m ON m.conversation_id = cv.id"
                        + "    WHERE (cv.user_lo = ? OR cv.user_hi = ?)"
                        + "      AND EXISTS ("
                        + "            SELECT 1 FROM users u"
                        + "             WHERE u.id IN (cv.user_lo, cv.user_hi)"
                        + "               AND u.id <> ?"
                        + "               AND u.company_id = ?)"
                        + " )",
                Boolean.class, complainant, complainant, complainant, companyId);
        return Boolean.TRUE.equals(exists);
    }

    /** 创建投诉并回读视图（evidence 由调用方 trim 后传入）。 */
    public ComplaintView create(UUID companyId, UUID complainantId, String evidence) {
        UUID id = jdbc.queryForObject(
                "INSERT INTO complaints (company_id, complainant_id, evidence) "
                        + "VALUES (?, ?, ?) RETURNING id",
                UUID.class, companyId, complainantId, evidence);
        return getView(id).orElseThrow(() -> ApiException.internal("投诉创建成功但读取失败"));
    }

    /** 我发起的投诉（按创建时间倒序）。 */
    public List<ComplaintView> mine(UUID complainant) {
        return jdbc.query("SELECT * FROM complaint_views WHERE complainant_id = ? "
                + "ORDER BY created_at DESC", viewMapper(), complainant);
    }

    /** 本企业的投诉（按创建时间倒序）。 */
    public List<ComplaintView> forCompany(UUID companyId) {
        return jdbc.query("SELECT * FROM complaint_views WHERE company_id = ? "
                + "ORDER BY created_at DESC", viewMapper(), companyId);
    }

    /** 全部投诉（可选 status 过滤；按创建时间倒序）。 */
    public List<ComplaintView> all(String status) {
        return jdbc.query("SELECT * FROM complaint_views "
                + "WHERE (?::text IS NULL OR status = ?) ORDER BY created_at DESC",
                viewMapper(), status, status);
    }

    /**
     * 认领（或续约）一条待审投诉。
     *
     * <p>原子性：UPDATE ... WHERE 空闲 OR 自己持有 OR 已过期，并发下只有一个请求能命中；
     * 未命中时再读一次当前状态，给出「已被谁占用 / 已审结」的准确报错，而不是笼统的 409。
     */
    public ComplaintView claim(UUID id, UUID reviewerId, long ttlSeconds) {
        List<UUID> claimed = jdbc.query(
                "UPDATE complaints"
                        + "   SET locked_by = ?,"
                        + "       locked_at = CASE WHEN locked_by = ? THEN locked_at ELSE now() END,"
                        + "       lock_expires_at = now() + (?::double precision * interval '1 second'),"
                        + "       review_started_at = COALESCE(review_started_at, now()),"
                        + "       updated_at = now()"
                        + " WHERE id = ?"
                        + "   AND status = 'pending'"
                        + "   AND (locked_by IS NULL OR locked_by = ? OR lock_expires_at <= now())"
                        + " RETURNING id",
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                reviewerId, reviewerId, (double) ttlSeconds, id, reviewerId);

        if (!claimed.isEmpty()) {
            return getView(id).orElseThrow(() -> ApiException.internal("认领成功但读取失败"));
        }

        ComplaintView current = getView(id)
                .orElseThrow(() -> ApiException.notFoundResource("complaint `" + id + "`"));
        if (!"pending".equals(current.status())) {
            throw ApiException.conflict("该投诉已审结，无需认领");
        }
        throw ApiException.conflict("该投诉正由「" + lockerName(current) + "」审核中，锁至 "
                + lockExpiresText(current) + "，可等待锁自动释放");
    }

    /**
     * 释放锁：本人可释放自己的锁；已过期的锁任何人可清理；平台管理员可用 force 强制释放。
     */
    public ComplaintView release(UUID id, UUID actorId, boolean force) {
        List<UUID> released = jdbc.query(
                "UPDATE complaints"
                        + "   SET locked_by = NULL, locked_at = NULL, lock_expires_at = NULL,"
                        + "       updated_at = now()"
                        + " WHERE id = ?"
                        + "   AND locked_by IS NOT NULL"
                        + "   AND (? OR locked_by = ? OR lock_expires_at <= now())"
                        + " RETURNING id",
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                id, force, actorId);

        if (released.isEmpty()) {
            ComplaintView current = getView(id)
                    .orElseThrow(() -> ApiException.notFoundResource("complaint `" + id + "`"));
            if (current.lockedBy() == null) {
                throw ApiException.conflict("该投诉当前没有被锁定");
            }
            throw ApiException.forbidden("只能释放自己认领的投诉（平台管理员可用 force 强制释放）");
        }
        return getView(id).orElseThrow(() -> ApiException.internal("释放成功但读取失败"));
    }

    /**
     * 审结：通过时累计企业投诉次数；同时清空锁字段并保留 review_started_at。
     *
     * <p>并发保护：条件 UPDATE 要求「仍是 pending 且没有被他人有效锁定」，
     * 因此即使两个审核同时提交，也只有一个会成功（另一个拿到 409）。
     * 前置检查与两条 UPDATE 在同一事务内，行为与 Rust 版一致。
     */
    public Optional<ComplaintView> review(UUID id, UUID reviewerId, boolean approved, String note) {
        return tx.execute(status -> {
            ComplaintView view = getView(id).orElse(null);
            if (view == null) {
                return Optional.<ComplaintView>empty();
            }
            if (!"pending".equals(view.status())) {
                throw ApiException.conflict("该投诉已处理，请勿重复审核");
            }
            if (view.lockActive() && !reviewerId.equals(view.lockedBy())) {
                throw ApiException.conflict("该投诉正由「" + lockerName(view)
                        + "」审核中，请等待对方完成或锁自动释放");
            }

            List<UUID> updated = jdbc.query(
                    "UPDATE complaints"
                            + "   SET status = ?, review_note = ?, reviewed_by = ?,"
                            + "       reviewed_at = now(), updated_at = now(),"
                            + "       locked_by = NULL, locked_at = NULL, lock_expires_at = NULL,"
                            + "       review_started_at = COALESCE(review_started_at, now())"
                            + " WHERE id = ?"
                            + "   AND status = 'pending'"
                            + "   AND (locked_by IS NULL OR locked_by = ? OR lock_expires_at <= now())"
                            + " RETURNING id",
                    (rs, rowNum) -> rs.getObject("id", UUID.class),
                    approved ? "approved" : "rejected", note, reviewerId, id, reviewerId);

            if (updated.isEmpty()) {
                throw ApiException.conflict("该投诉刚被其他审核处理，请刷新后重试");
            }

            if (approved) {
                jdbc.update("UPDATE companies"
                        + "   SET complaints_count = complaints_count + 1,"
                        + "       updated_at = now()"
                        + " WHERE id = ?", view.companyId());
            }
            return getView(id);
        });
    }

    /** 读取定级规则（数字来自 complaint_rule_* 函数，保证与排序/字段同源）。 */
    public ComplaintRules rules() {
        return jdbc.queryForObject(
                "SELECT complaint_rule_version() AS version,"
                        + "       complaint_rule_levels() AS levels,"
                        + "       complaint_rule_min_staff_size() AS min_staff_size_for_rate,"
                        + "       complaint_rule_rate_thresholds()::float8[] AS rate_thresholds,"
                        + "       complaint_rule_count_thresholds() AS count_thresholds",
                (rs, rowNum) -> new ComplaintRules(
                        rs.getString("version"),
                        stringList(rs.getArray("levels")),
                        rs.getInt("min_staff_size_for_rate"),
                        doubleList(rs.getArray("rate_thresholds")),
                        intList(rs.getArray("count_thresholds"))));
    }

    /** 持锁人显示名；缺失时回落到 Rust 版的“其他审核”。 */
    private static String lockerName(ComplaintView view) {
        return view.lockedByName() == null ? "其他审核" : view.lockedByName();
    }

    /** 锁到期时间文案（UTC，秒级）；缺失时为 “-”。 */
    private static String lockExpiresText(ComplaintView view) {
        OffsetDateTime expiresAt = view.lockExpiresAt();
        if (expiresAt == null) {
            return "-";
        }
        return LOCK_TIME_FORMAT.format(expiresAt) + " UTC";
    }

    private static List<String> stringList(Array array) throws SQLException {
        if (array == null) {
            return null;
        }
        Object[] raw = (Object[]) array.getArray();
        List<String> values = new ArrayList<>(raw.length);
        for (Object value : raw) {
            values.add(value == null ? null : value.toString());
        }
        return values;
    }

    private static List<Double> doubleList(Array array) throws SQLException {
        if (array == null) {
            return null;
        }
        Object[] raw = (Object[]) array.getArray();
        List<Double> values = new ArrayList<>(raw.length);
        for (Object value : raw) {
            values.add(value == null ? null : ((Number) value).doubleValue());
        }
        return values;
    }

    private static List<Integer> intList(Array array) throws SQLException {
        if (array == null) {
            return null;
        }
        Object[] raw = (Object[]) array.getArray();
        List<Integer> values = new ArrayList<>(raw.length);
        for (Object value : raw) {
            values.add(value == null ? null : ((Number) value).intValue());
        }
        return values;
    }
}
