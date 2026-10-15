package com.meowstar.careers.repo;

import com.meowstar.careers.db.Rows;
import com.meowstar.careers.model.company.Company;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 企业数据访问（对齐 Rust 版 {@code repositories::company}）。
 *
 * <p>注意：公开企业信息**不过滤 {@code is_active}**（与 Rust 版一致），仅按 id 精确查询。
 */
@Repository
public class CompanyRepo {

    /** 企业查询列（顺序固定，与 Rust 版 SELECT 列表逐字一致，别名必须是 snake_case）。 */
    private static final String COMPANY_COLUMNS =
            "id, name, industry, description, location, address, website, logo_url, "
                    + "is_active, created_by, created_at, updated_at, complaints_count, staff_size";

    private final JdbcTemplate jdbc;

    public CompanyRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按 id 查询企业，不存在时返回空（由调用方决定 404 文案）。 */
    public Optional<Company> get(UUID id) {
        return jdbc.query(
                "SELECT " + COMPANY_COLUMNS + " FROM companies WHERE id = ?",
                Rows.of(Company.class), id).stream().findFirst();
    }
}
