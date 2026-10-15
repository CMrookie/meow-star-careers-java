package com.meowstar.careers.repo;

import com.meowstar.careers.db.Rows;
import com.meowstar.careers.model.pagination.Paginate;
import com.meowstar.careers.model.resume.Resume;
import com.meowstar.careers.model.resume.ResumePage;
import com.meowstar.careers.model.resume.ResumeQuery;
import com.meowstar.careers.model.resume.ResumeWrite;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 简历数据访问（对齐 Rust 版 {@code repositories::resume}）。
 * 必填/长度校验在 handler（与 Rust 一致），本层只负责 SQL 语义。
 */
@Repository
public class ResumeRepo {

    /** resumes 表列清单（Rust 各语句共用）。 */
    private static final String COLUMNS =
            "id, user_id, full_name, title, phone, email, years, "
                    + "education, skills, summary, is_public, created_at, updated_at";

    /** 招聘者检索公开简历的过滤条件（keyword 为空则不过滤；匹配姓名/期望职位/技能/简介）。 */
    private static final String PUBLIC_FILTER =
            " WHERE is_public = true"
                    + " AND (?::text IS NULL"
                    + " OR full_name ILIKE '%' || ? || '%'"
                    + " OR title ILIKE '%' || ? || '%'"
                    + " OR skills ILIKE '%' || ? || '%'"
                    + " OR summary ILIKE '%' || ? || '%')";

    private final JdbcTemplate jdbc;

    public ResumeRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 创建简历（fullName / title 由 handler 校验必填；未提供的字段按 Rust 绑定 NULL，空串按空串）。 */
    public Resume create(UUID userId, ResumeWrite input) {
        return jdbc.queryForObject(
                "INSERT INTO resumes (user_id, full_name, title, phone, email, years, "
                        + "education, skills, summary, is_public) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING " + COLUMNS,
                Rows.of(Resume.class),
                userId,
                input.fullName() == null ? "" : input.fullName(),
                input.title() == null ? "" : input.title(),
                input.phone(),
                input.email(),
                input.years(),
                input.education(),
                input.skills(),
                input.summary(),
                input.isPublic() != null && input.isPublic());
    }

    /** 我的简历列表（按创建时间倒序）。 */
    public List<Resume> listMine(UUID userId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM resumes WHERE user_id = ? ORDER BY created_at DESC",
                Rows.of(Resume.class), userId);
    }

    /** 按 id 查询简历（任意可见性；可见性判断由 handler 做）。 */
    public Optional<Resume> get(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM resumes WHERE id = ?",
                Rows.of(Resume.class), id).stream().findFirst();
    }

    /** 更新自己的简历（仅更新提供的字段），返回空表示不存在/非本人。 */
    @Transactional
    public Optional<Resume> update(UUID id, UUID userId, ResumeWrite input) {
        int updated = jdbc.update(
                "UPDATE resumes"
                        + " SET full_name = COALESCE(?, full_name),"
                        + " title = COALESCE(?, title),"
                        + " phone = COALESCE(?, phone),"
                        + " email = COALESCE(?, email),"
                        + " years = COALESCE(?, years),"
                        + " education = COALESCE(?, education),"
                        + " skills = COALESCE(?, skills),"
                        + " summary = COALESCE(?, summary),"
                        + " is_public = COALESCE(?, is_public),"
                        + " updated_at = now()"
                        + " WHERE id = ? AND user_id = ?",
                input.fullName(), input.title(), input.phone(), input.email(), input.years(),
                input.education(), input.skills(), input.summary(), input.isPublic(), id, userId);

        if (updated == 0) {
            return Optional.empty();
        }
        return get(id);
    }

    /** 删除自己的简历，返回是否真的删除了某行。 */
    public boolean delete(UUID id, UUID userId) {
        return jdbc.update("DELETE FROM resumes WHERE id = ? AND user_id = ?", id, userId) > 0;
    }

    /** 招聘者检索公开简历（按更新时间倒序）。 */
    public ResumePage searchPublic(ResumeQuery query) {
        Paginate.Page page = Paginate.paginate(query.page(), query.pageSize());
        String keyword = query.keyword();

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM resumes" + PUBLIC_FILTER,
                Long.class, keyword, keyword, keyword, keyword, keyword);

        List<Resume> items = jdbc.query(
                "SELECT " + COLUMNS + " FROM resumes" + PUBLIC_FILTER
                        + " ORDER BY updated_at DESC LIMIT ? OFFSET ?",
                Rows.of(Resume.class),
                keyword, keyword, keyword, keyword, keyword,
                page.pageSize(), page.offset());

        return new ResumePage(items, total == null ? 0L : total, page.page(), page.pageSize());
    }
}
