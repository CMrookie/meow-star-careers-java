package com.meowstar.careers.web;

import com.meowstar.careers.auth.AuthUser;
import com.meowstar.careers.error.ApiException;
import com.meowstar.careers.model.user.NewUser;
import com.meowstar.careers.model.user.UpdateUser;
import com.meowstar.careers.model.user.User;
import com.meowstar.careers.repo.UserRepo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户 CRUD 端点（全部需要 Bearer 鉴权），对齐 Rust 版 handlers::users。
 * 列表 / 创建 / 删除仅平台管理员；查改限本人或平台管理员（查他人一律 404，不泄露账号存在性）。
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "users", description = "用户管理（仅平台管理员；本人可查改自己）")
public class UsersController {

    private final UserRepo userRepo;

    public UsersController(UserRepo userRepo) {
        this.userRepo = userRepo;
    }

    /** 列出全部用户（仅平台管理员；含手机号等敏感字段）。 */
    @Operation(summary = "用户列表（仅平台管理员）")
    @GetMapping
    public List<User> listUsers(AuthUser auth) {
        userRepo.requireAdmin(auth.userId());
        return userRepo.list();
    }

    /** 创建用户（仅平台管理员；管理端历史邮箱账号，无密码）。 */
    @Operation(summary = "创建用户（仅平台管理员）")
    @PostMapping
    public ResponseEntity<User> createUser(AuthUser auth, @RequestBody NewUser payload) {
        userRepo.requireAdmin(auth.userId());

        String email = (payload.email() == null ? "" : payload.email().trim()).toLowerCase(Locale.ROOT);
        String name = payload.name() == null ? "" : payload.name().trim();

        if (email.isEmpty() || !email.contains("@")) {
            throw ApiException.badRequest("email 必须为合法邮箱地址");
        }
        if (name.isEmpty()) {
            throw ApiException.badRequest("name 不能为空");
        }

        User user = userRepo.create(new NewUser(email, name));
        return ResponseEntity.status(HttpStatus.CREATED).body(user);
    }

    /** 按 id 获取用户（本人或平台管理员；其余一律 404，不泄露账号是否存在）。 */
    @Operation(summary = "用户详情（本人或平台管理员）")
    @GetMapping("/{id}")
    public User getUser(AuthUser auth, @PathVariable UUID id) {
        User me = userRepo.get(auth.userId())
                .orElseThrow(() -> ApiException.notFoundUser(auth.userId()));
        if (!"admin".equals(me.role()) && !me.id().equals(id)) {
            // 与简历一致：非本人且非管理员按 404 处理，不泄露账号存在性
            throw ApiException.notFoundUser(id);
        }

        return userRepo.get(id).orElseThrow(() -> ApiException.notFoundUser(id));
    }

    /** 更新用户（仅本人或平台管理员；仅更新请求体中提供的字段）。 */
    @Operation(summary = "更新用户（本人或平台管理员）")
    @PutMapping("/{id}")
    public User updateUser(AuthUser auth, @PathVariable UUID id, @RequestBody UpdateUser payload) {
        // 仅本人或平台管理员可修改（管理端禁用/改名走同一入口）
        User me = userRepo.get(auth.userId())
                .orElseThrow(() -> ApiException.notFoundUser(auth.userId()));
        if (!"admin".equals(me.role()) && !me.id().equals(id)) {
            throw ApiException.forbidden("仅本人或平台管理员可修改用户资料");
        }

        if (payload.name() == null && payload.isActive() == null) {
            throw ApiException.badRequest("至少提供 name 或 isActive 之一");
        }
        String name = payload.name();
        if (name != null) {
            name = name.trim();
            if (name.isEmpty()) {
                throw ApiException.badRequest("name 不能为空");
            }
        }

        UpdateUser input = new UpdateUser(name, payload.isActive());
        return userRepo.update(id, input).orElseThrow(() -> ApiException.notFoundUser(id));
    }

    /** 删除用户（仅平台管理员；级联清理其令牌/简历/投递等）。 */
    @Operation(summary = "删除用户（仅平台管理员）")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(AuthUser auth, @PathVariable UUID id) {
        userRepo.requireAdmin(auth.userId());

        if (!userRepo.delete(id)) {
            throw ApiException.notFoundUser(id);
        }
        return ResponseEntity.noContent().build();
    }
}
