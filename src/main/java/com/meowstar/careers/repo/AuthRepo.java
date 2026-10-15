package com.meowstar.careers.repo;

import com.meowstar.careers.db.Rows;
import com.meowstar.careers.error.ApiException;
import com.meowstar.careers.model.company.Company;
import com.meowstar.careers.model.company.NewCompany;
import com.meowstar.careers.model.user.User;
import com.meowstar.careers.security.Security;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证数据访问：用户凭证查询/注册（含招聘者+企业事务）、令牌签发/校验/撤销。
 * 库中只保存 token 的 sha256 摘要，不保存明文（与 Rust 版 repositories::auth 对齐）。
 */
@Repository
public class AuthRepo {

    /** 用户查询的公共列（顺序固定，供各处复用）。 */
    static final String USER_COLUMNS =
            "id, email, name, is_active, role, company_id, created_at, updated_at, phone";

    private final JdbcTemplate jdbc;

    public AuthRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 带密码哈希的用户记录（仅登录校验用，不参与序列化）。 */
    public record StoredUser(User user, String passwordHash) {
    }

    /** 管理员引导结果（服务启动时打印日志用）。 */
    public enum AdminBootstrap {
        /** 本次启动创建了管理员账号 */
        CREATED,
        /** 已存在同手机号的管理员账号（保持原密码不变） */
        ALREADY_EXISTS
    }

    /** 按手机号查找（用于登录）。 */
    public Optional<StoredUser> findByPhone(String phone) {
        List<StoredUser> rows = jdbc.query(
                "SELECT " + USER_COLUMNS + ", password_hash FROM users WHERE phone = ?",
                (rs, rowNum) -> new StoredUser(
                        new User(
                                rs.getObject("id", UUID.class),
                                rs.getString("email"),
                                rs.getString("name"),
                                rs.getBoolean("is_active"),
                                rs.getString("role"),
                                rs.getObject("company_id", UUID.class),
                                rs.getObject("created_at", OffsetDateTime.class),
                                rs.getObject("updated_at", OffsetDateTime.class),
                                rs.getString("phone")),
                        rs.getString("password_hash")),
                phone);
        return rows.stream().findFirst();
    }

    /** 注册求职者（phone 唯一冲突经统一错误处理返回 409）。 */
    public User registerSeeker(String phone, String name, String passwordHash) {
        return jdbc.queryForObject(
                "INSERT INTO users (phone, name, password_hash, role) VALUES (?, ?, ?, 'seeker') "
                        + "RETURNING " + USER_COLUMNS,
                Rows.of(User.class), phone, name, passwordHash);
    }

    /** 注册招聘者：事务内创建用户 + 其所属企业并回填 company_id。 */
    @Transactional
    public User registerRecruiter(String phone, String name, String passwordHash, NewCompany company) {
        User user = jdbc.queryForObject(
                "INSERT INTO users (phone, name, password_hash, role) VALUES (?, ?, ?, 'recruiter') "
                        + "RETURNING " + USER_COLUMNS,
                Rows.of(User.class), phone, name, passwordHash);

        Company created = jdbc.queryForObject(
                "INSERT INTO companies (name, industry, description, location, address, website, logo_url, "
                        + "created_by, staff_size) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "RETURNING id, name, industry, description, location, address, website, logo_url, "
                        + "is_active, created_by, created_at, updated_at, complaints_count, staff_size",
                Rows.of(Company.class),
                company.name() == null ? null : company.name().trim(),
                company.industry(),
                company.description(),
                company.location(),
                company.address(),
                company.website(),
                company.logoUrl(),
                user.id(),
                company.staffSize());

        return jdbc.queryForObject(
                "UPDATE users SET company_id = ? WHERE id = ? RETURNING " + USER_COLUMNS,
                Rows.of(User.class), created.id(), user.id());
    }

    /** 为用户签发新令牌（30 天），返回明文令牌（仅此一次）。 */
    public String issueToken(UUID userId) {
        Security.TokenPair pair = Security.newSessionToken();
        OffsetDateTime expiresAt = OffsetDateTime.now().plusDays(30);
        jdbc.update("INSERT INTO auth_tokens (token_hash, user_id, expires_at) VALUES (?, ?, ?)",
                pair.digest(), userId, expiresAt);
        return pair.token();
    }

    /** 按明文令牌查找用户（未撤销、未过期且账号启用）。 */
    public Optional<User> findUserByToken(String token) {
        String digest = Security.sha256Hex(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        List<User> rows = jdbc.query(
                "SELECT u.id, u.email, u.name, u.is_active, u.role, u.company_id, u.created_at, "
                        + "u.updated_at, u.phone FROM auth_tokens t JOIN users u ON u.id = t.user_id "
                        + "WHERE t.token_hash = ? AND t.revoked_at IS NULL AND t.expires_at > now() "
                        + "AND u.is_active = true",
                Rows.of(User.class), digest);
        return rows.stream().findFirst();
    }

    /** 撤销令牌（登出），返回是否命中有效令牌。 */
    public boolean revokeToken(String token) {
        String digest = Security.sha256Hex(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return jdbc.update("UPDATE auth_tokens SET revoked_at = now() WHERE token_hash = ? AND revoked_at IS NULL",
                digest) > 0;
    }

    /**
     * 引导平台管理员账号（幂等，服务启动时调用）：
     * 手机号不存在 -> 创建 admin；已是 admin -> 保持原密码不动；已被其它角色占用 -> 409，绝不提权。
     */
    public AdminBootstrap ensureAdmin(String phone, String name, String password) {
        String trimmedPhone = phone.trim();
        if (!Security.isValidCnPhone(trimmedPhone)) {
            throw ApiException.badRequest("ADMIN_PHONE 必须为 11 位手机号（1 开头）");
        }
        if (password.length() < 8 || password.length() > 128) {
            throw ApiException.badRequest("ADMIN_PASSWORD 长度须在 8-128 之间");
        }
        String trimmedName = name.trim();
        if (trimmedName.isEmpty() || trimmedName.codePointCount(0, trimmedName.length()) > 64) {
            throw ApiException.badRequest("ADMIN_NAME 必填且不超过 64 字符");
        }

        List<String> roles = jdbc.queryForList("SELECT role FROM users WHERE phone = ?", String.class, trimmedPhone);
        if (!roles.isEmpty()) {
            String existing = roles.getFirst();
            if ("admin".equals(existing)) {
                return AdminBootstrap.ALREADY_EXISTS;
            }
            throw ApiException.conflict("ADMIN_PHONE 已被 " + existing + " 账号占用，拒绝自动提权");
        }

        String passwordHash = Security.hashPassword(password);
        jdbc.update("INSERT INTO users (phone, name, password_hash, role) VALUES (?, ?, ?, 'admin')",
                trimmedPhone, trimmedName, passwordHash);
        return AdminBootstrap.CREATED;
    }

    /** 创建审核专用账号（role=reviewer，仅平台管理员调用）。 */
    public User createReviewer(String phone, String name, String passwordHash) {
        return jdbc.queryForObject(
                "INSERT INTO users (phone, name, password_hash, role) VALUES (?, ?, ?, 'reviewer') "
                        + "RETURNING " + USER_COLUMNS,
                Rows.of(User.class), phone.trim(), name.trim(), passwordHash);
    }

    /** 重置密码（审核账号管理用），返回是否命中某行。 */
    public boolean updatePassword(UUID userId, String passwordHash) {
        return jdbc.update("UPDATE users SET password_hash = ?, updated_at = now() WHERE id = ?",
                passwordHash, userId) > 0;
    }

    /** 撤销某账号的全部有效令牌（重置密码 / 禁用账号时强制其重新登录）。 */
    public long revokeAllTokens(UUID userId) {
        return jdbc.update("UPDATE auth_tokens SET revoked_at = now() WHERE user_id = ? AND revoked_at IS NULL",
                userId);
    }
}
