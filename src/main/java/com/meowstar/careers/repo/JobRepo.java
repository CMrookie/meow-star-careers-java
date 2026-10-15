package com.meowstar.careers.repo;

import com.meowstar.careers.db.Rows;
import com.meowstar.careers.error.ApiException;
import com.meowstar.careers.model.job.JobQuery;
import com.meowstar.careers.model.job.JobView;
import com.meowstar.careers.model.job.NewJob;
import com.meowstar.careers.model.job.UpdateJob;
import com.meowstar.careers.model.pagination.Paginate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 职位数据访问：公开搜索/详情、招聘者管理、收藏（对齐 Rust 版 {@code repositories::job}）。
 *
 * <p>列表排序契约：三个职位列表（公开搜索 / 本企业 / 我的收藏）**一律按用人单位投诉等级
 * 从优到劣**返回。排序键与 Rust 版逐项对齐（定级 v2，见
 * {@code migrations/0017_company_staff_size.sql} 与 {@code migrations/0018_complaint_rule_single_source.sql}）：
 *
 * <ol>
 *   <li>{@code complaint_level_rank(complaints_count, staff_size)} —— 有申报规模且 &gt;=50 人时按
 *       <b>每百人投诉率</b>（0.5/1.5/3.0%），否则退回投诉次数口径；</li>
 *   <li>同等级内有规模折算的排前面（数据更可信）；</li>
 *   <li>再按率（无规模时按次数）由小到大；</li>
 *   <li>最后按时间倒序、{@code id} 兜底，保证分页稳定、跨页全局有序。</li>
 * </ol>
 *
 * <p>阈值**只在 SQL 函数里定义一次**（{@code complaint_rule_*}），本类不硬编码任何阈值。
 */
@Repository
public class JobRepo {

    /** 职位列表分页结果（对齐 Rust 版 {@code (Vec<JobView>, i64)}）。 */
    public record JobList(List<JobView> items, long total) {
    }

    /**
     * 职位视图公共列（与 Rust 版各处 SELECT 列表逐字一致）。
     *
     * <p>{@code complaint_level} / {@code complaint_basis} / {@code complaint_rate_percent}
     * 一律调用 SQL 函数计算，不在此处判定等级。
     */
    private static final String JOB_COLUMNS =
            "j.id, j.company_id, c.name AS company_name, j.title, j.description,\n"
                    + "                j.requirements, j.location, j.salary_min, j.salary_max, j.job_type,\n"
                    + "                j.experience, j.education, j.is_active, j.created_by,"
                    + " j.created_at, j.updated_at,\n"
                    + "                c.complaints_count, c.staff_size AS company_staff_size,\n"
                    + "                complaint_level(c.complaints_count, c.staff_size) AS complaint_level,\n"
                    + "                complaint_basis(c.complaints_count, c.staff_size) AS complaint_basis,\n"
                    + "                complaint_rate_percent(c.complaints_count, c.staff_size)::float8"
                    + " AS complaint_rate_percent";

    private static final String JOB_SELECT =
            "SELECT " + JOB_COLUMNS + "\n"
                    + "           FROM jobs j\n"
                    + "           JOIN companies c ON c.id = j.company_id\n";

    /** 收藏列表的 FROM（saved_jobs -> jobs -> companies），列清单与 JOB_SELECT 完全相同。 */
    private static final String SAVED_SELECT =
            "SELECT " + JOB_COLUMNS + "\n"
                    + "           FROM saved_jobs s\n"
                    + "           JOIN jobs j ON j.id = s.job_id\n"
                    + "           JOIN companies c ON c.id = j.company_id\n";

    /** 公开搜索过滤条件（Rust 版 search_active 的 WHERE，占位符顺序与 searchArgs 一致）。 */
    private static final String SEARCH_FILTER =
            "          WHERE j.is_active = true\n"
                    + "            AND (?::text IS NULL\n"
                    + "                 OR j.title ILIKE '%' || ? || '%'\n"
                    + "                 OR j.description ILIKE '%' || ? || '%'\n"
                    + "                 OR j.requirements ILIKE '%' || ? || '%')\n"
                    + "            AND (?::text IS NULL OR j.location ILIKE '%' || ? || '%')\n"
                    + "            AND (?::text IS NULL OR j.job_type = ?)\n"
                    + "            AND (?::int IS NULL OR COALESCE(j.salary_max, 2147483647) >= ?)\n"
                    + "            AND (?::int IS NULL OR COALESCE(j.salary_min, 0) <= ?)\n";

    /** 投诉等级排序前三键（三个列表完全一致；阈值来自 SQL 函数，Java 不参与定级）。 */
    private static final String ORDER_BY_LEVEL =
            "          ORDER BY complaint_level_rank(c.complaints_count, c.staff_size) ASC,\n"
                    + "                   (complaint_rate_percent(c.complaints_count, c.staff_size)"
                    + " IS NULL) ASC,\n"
                    + "                   COALESCE(complaint_rate_percent(c.complaints_count, c.staff_size),\n"
                    + "                            c.complaints_count::numeric) ASC,\n";

    /** 搜索：发布时间倒序 + id 兜底。 */
    private static final String ORDER_BY_SEARCH =
            ORDER_BY_LEVEL
                    + "                   j.created_at DESC,\n"
                    + "                   j.id DESC\n";

    /** 本企业：同一企业等级相同，实际退化为按发布时间倒序。 */
    private static final String ORDER_BY_COMPANY =
            ORDER_BY_LEVEL
                    + "                   j.created_at DESC,\n"
                    + "                   j.id DESC\n";

    /** 收藏：同级内最近收藏的在前。 */
    private static final String ORDER_BY_SAVED =
            ORDER_BY_LEVEL
                    + "                   s.created_at DESC,\n"
                    + "                   j.id DESC\n";

    private final JdbcTemplate jdbc;

    public JobRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 公开搜索在招职位（仅 is_active = true）；返回 (items, total)。 */
    public JobList searchActive(JobQuery query) {
        Paginate.Page page = Paginate.paginate(query.page(), query.pageSize());
        Object[] filters = searchArgs(query);

        Long total = jdbc.queryForObject(
                "SELECT count(*)\n"
                        + "           FROM jobs j\n"
                        + SEARCH_FILTER,
                Long.class, filters);

        Object[] args = Arrays.copyOf(filters, filters.length + 2);
        args[filters.length] = page.pageSize();
        args[filters.length + 1] = page.offset();
        List<JobView> items = jdbc.query(
                JOB_SELECT + SEARCH_FILTER + ORDER_BY_SEARCH + "          LIMIT ? OFFSET ?",
                Rows.of(JobView.class), args);

        return new JobList(items, total == null ? 0L : total);
    }

    /** 招聘者视角：本公司职位（含已下架）。 */
    public JobList listForCompany(UUID companyId, Integer page, Integer pageSize) {
        Paginate.Page normalized = Paginate.paginate(page, pageSize);

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM jobs WHERE company_id = ?", Long.class, companyId);

        List<JobView> items = jdbc.query(
                JOB_SELECT
                        + "          WHERE j.company_id = ?\n"
                        + ORDER_BY_COMPANY
                        + "          LIMIT ? OFFSET ?",
                Rows.of(JobView.class), companyId, normalized.pageSize(), normalized.offset());

        return new JobList(items, total == null ? 0L : total);
    }

    /** 任意状态职位（招聘者校验所有权后使用）。 */
    public Optional<JobView> getById(UUID id) {
        return jdbc.query(JOB_SELECT + "          WHERE j.id = ?", Rows.of(JobView.class), id)
                .stream().findFirst();
    }

    /** 公开职位详情（仅上架中）。 */
    public Optional<JobView> getActive(UUID id) {
        return jdbc.query(JOB_SELECT + "          WHERE j.id = ? AND j.is_active = true",
                Rows.of(JobView.class), id).stream().findFirst();
    }

    /** 发布职位（jobType 缺省 full_time；title/description 落库前 trim，与 Rust 版一致）。 */
    @Transactional
    public JobView create(UUID companyId, UUID createdBy, NewJob input) {
        String jobType = input.jobType() == null ? "full_time" : input.jobType();
        UUID id = jdbc.queryForObject(
                "INSERT INTO jobs (company_id, title, description, requirements, location,\n"
                        + "                           salary_min, salary_max, job_type,"
                        + " experience, education, created_by)\n"
                        + "         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)\n"
                        + "         RETURNING id",
                UUID.class, companyId, trim(input.title()), trim(input.description()),
                input.requirements(), input.location(), input.salaryMin(), input.salaryMax(),
                jobType, input.experience(), input.education(), createdBy);

        return getById(id).orElseThrow(() -> ApiException.internal("职位创建成功但读取失败"));
    }

    /** 更新职位内容（仅更新提供的字段）；返回空表示职位不存在。 */
    @Transactional
    public Optional<JobView> update(UUID id, UpdateJob input) {
        int updated = jdbc.update(
                "UPDATE jobs\n"
                        + "            SET title        = COALESCE(?, title),\n"
                        + "                description  = COALESCE(?, description),\n"
                        + "                requirements = COALESCE(?, requirements),\n"
                        + "                location     = COALESCE(?, location),\n"
                        + "                salary_min   = COALESCE(?, salary_min),\n"
                        + "                salary_max   = COALESCE(?, salary_max),\n"
                        + "                job_type     = COALESCE(?, job_type),\n"
                        + "                experience   = COALESCE(?, experience),\n"
                        + "                education    = COALESCE(?, education),\n"
                        + "                updated_at   = now()\n"
                        + "          WHERE id = ?",
                input.title(), input.description(), input.requirements(), input.location(),
                input.salaryMin(), input.salaryMax(), input.jobType(), input.experience(),
                input.education(), id);

        if (updated == 0) {
            return Optional.empty();
        }
        return getById(id);
    }

    /** 上架/下架。 */
    public boolean setActive(UUID id, boolean active) {
        return jdbc.update("UPDATE jobs SET is_active = ?, updated_at = now() WHERE id = ?",
                active, id) > 0;
    }

    /** 删除职位（投递由外键 ON DELETE CASCADE 级联清理）。 */
    public boolean delete(UUID id) {
        return jdbc.update("DELETE FROM jobs WHERE id = ?", id) > 0;
    }

    // ---------------------------------------------------------------- 收藏

    /** 收藏职位（重复收藏幂等）。 */
    public void save(UUID userId, UUID jobId) {
        jdbc.update("INSERT INTO saved_jobs (user_id, job_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                userId, jobId);
    }

    /** 取消收藏，返回是否真的删除了某行。 */
    public boolean unsave(UUID userId, UUID jobId) {
        return jdbc.update("DELETE FROM saved_jobs WHERE user_id = ? AND job_id = ?",
                userId, jobId) > 0;
    }

    /** 我的收藏职位列表（排序与公开列表一致，同级内按最近收藏时间倒序）。 */
    public JobList listSaved(UUID userId, Integer page, Integer pageSize) {
        Paginate.Page normalized = Paginate.paginate(page, pageSize);

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM saved_jobs WHERE user_id = ?", Long.class, userId);

        List<JobView> items = jdbc.query(
                SAVED_SELECT
                        + "          WHERE s.user_id = ?\n"
                        + ORDER_BY_SAVED
                        + "          LIMIT ? OFFSET ?",
                Rows.of(JobView.class), userId, normalized.pageSize(), normalized.offset());

        return new JobList(items, total == null ? 0L : total);
    }

    /**
     * 公开搜索的 12 个过滤参数，顺序与 {@link #SEARCH_FILTER} 的 {@code ?} 一一对应。
     *
     * <p>JDBC 与 sqlx 的差异：Rust 的 {@code $4}/{@code $5} 各出现两次但只需绑定一次；
     * JDBC 每个 {@code ?} 都要单独传值，因此 salaryMin（对应职位上限 salary_max）与
     * salaryMax（对应职位下限 salary_min）各出现两次。
     */
    private static Object[] searchArgs(JobQuery query) {
        String keyword = query.keyword();
        String location = query.location();
        String jobType = query.jobType();
        Integer salaryMin = query.salaryMin();
        Integer salaryMax = query.salaryMax();
        return new Object[] {
            keyword, keyword, keyword, keyword,
            location, location,
            jobType, jobType,
            salaryMin, salaryMin,
            salaryMax, salaryMax
        };
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
