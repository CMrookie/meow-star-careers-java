package com.meowstar.careers.repo;

import com.meowstar.careers.db.Rows;
import com.meowstar.careers.error.ApiException;
import com.meowstar.careers.model.user.NewUser;
import com.meowstar.careers.model.user.UpdateUser;
import com.meowstar.careers.model.user.User;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * users 数据访问 + 角色校验辅助（对齐 Rust 版 repositories::user）。
 * 角色门禁统一走 require* 方法，保证各处理器口径一致。
 */
@Repository
public class UserRepo {

    private final JdbcTemplate jdbc;

    public UserRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 列出全部用户（按创建时间升序）。 */
    public List<User> list() {
        return jdbc.query("SELECT " + AuthRepo.USER_COLUMNS + " FROM users ORDER BY created_at",
                Rows.of(User.class));
    }

    /** 按 id 查询单个用户，不存在时返回空。 */
    public Optional<User> get(UUID id) {
        return jdbc.query("SELECT " + AuthRepo.USER_COLUMNS + " FROM users WHERE id = ?",
                Rows.of(User.class), id).stream().findFirst();
    }

    /** 按 id 查询，不存在时抛 404。 */
    public User getOrNotFound(UUID id) {
        return get(id).orElseThrow(() -> ApiException.notFoundUser(id));
    }

    /** 创建用户（默认求职者）；email 唯一冲突时由统一错误处理返回 409。 */
    public User create(NewUser input) {
        return jdbc.queryForObject(
                "INSERT INTO users (email, name) VALUES (?, ?) RETURNING " + AuthRepo.USER_COLUMNS,
                Rows.of(User.class), input.email(), input.name());
    }

    /** 更新用户（name / isActive 仅更新提供的字段），返回空表示不存在。 */
    public Optional<User> update(UUID id, UpdateUser input) {
        return jdbc.query(
                "UPDATE users SET name = COALESCE(?, name), is_active = COALESCE(?, is_active), "
                        + "updated_at = now() WHERE id = ? RETURNING " + AuthRepo.USER_COLUMNS,
                Rows.of(User.class), input.name(), input.isActive(), id).stream().findFirst();
    }

    /** 删除用户，返回是否真的删除了某行。 */
    public boolean delete(UUID id) {
        return jdbc.update("DELETE FROM users WHERE id = ?", id) > 0;
    }

    /** 必须是招聘者且已绑定企业才放行，否则 403。 */
    public User requireRecruiter(UUID userId) {
        User user = getOrNotFound(userId);
        if (!"recruiter".equals(user.role()) || user.companyId() == null) {
            throw ApiException.forbidden("仅招聘者账号（且已绑定企业）可执行该操作");
        }
        return user;
    }

    /** 必须是求职者角色才放行。 */
    public User requireSeeker(UUID userId) {
        User user = getOrNotFound(userId);
        if (!"seeker".equals(user.role())) {
            throw ApiException.forbidden("仅求职者可执行该操作");
        }
        return user;
    }

    /** 必须是平台管理员才放行（账号管理 / 用户管理等平台级操作）。 */
    public User requireAdmin(UUID userId) {
        User user = getOrNotFound(userId);
        if (!"admin".equals(user.role())) {
            throw ApiException.forbidden("仅平台管理员可执行该操作");
        }
        return user;
    }

    /** 审核权限：审核专用账号（reviewer）或平台管理员（admin，作为超级角色可代审）。 */
    public User requireReviewer(UUID userId) {
        User user = getOrNotFound(userId);
        if (!"reviewer".equals(user.role()) && !"admin".equals(user.role())) {
            throw ApiException.forbidden("仅审核账号或平台管理员可执行该操作");
        }
        return user;
    }

    /** 按角色列出账号（审核账号管理用），按创建时间升序。 */
    public List<User> listByRole(String role) {
        return jdbc.query("SELECT " + AuthRepo.USER_COLUMNS + " FROM users WHERE role = ? ORDER BY created_at",
                Rows.of(User.class), role);
    }

    /** 某企业全部招聘者（用于投递状态/面试变更实时推送）。 */
    public List<UUID> recruitersOfCompany(UUID companyId) {
        return jdbc.queryForList("SELECT id FROM users WHERE role = 'recruiter' AND company_id = ?",
                UUID.class, companyId);
    }
}
