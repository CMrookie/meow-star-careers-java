package com.meowstar.careers.web;

import com.meowstar.careers.auth.AuthUser;
import com.meowstar.careers.error.ApiException;
import com.meowstar.careers.model.user.NewReviewer;
import com.meowstar.careers.model.user.ResetPassword;
import com.meowstar.careers.model.user.SetActiveRequest;
import com.meowstar.careers.model.user.UpdateUser;
import com.meowstar.careers.model.user.User;
import com.meowstar.careers.repo.AuthRepo;
import com.meowstar.careers.repo.UserRepo;
import com.meowstar.careers.security.Security;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审核专用账号管理（**仅平台管理员**），对齐 Rust 版 handlers::reviewers：
 * 创建 / 列表 / 启用禁用（禁用即踢下线）/ 重置密码（撤销全部会话）/ 删除。
 * 本模块只操作 role=reviewer 的账号，其它角色一律 404，避免误伤普通用户。
 */
@RestController
@RequestMapping("/api/v1/reviewers")
@Tag(name = "reviewer", description = "审核专用账号管理（仅平台管理员）")
public class ReviewersController {

    /** 审核账号的角色取值（与 0015_reviewer_role.sql 的 CHECK 一致）。 */
    public static final String REVIEWER_ROLE = "reviewer";

    private final AuthRepo authRepo;
    private final UserRepo userRepo;

    public ReviewersController(AuthRepo authRepo, UserRepo userRepo) {
        this.authRepo = authRepo;
        this.userRepo = userRepo;
    }

    private static void validatePhone(String phone) {
        String trimmed = phone == null ? "" : phone.trim();
        if (!Security.isValidCnPhone(trimmed)) {
            throw ApiException.badRequest("phone 必须为 11 位手机号（1 开头）");
        }
    }

    private static void validateName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > 64) {
            throw ApiException.badRequest("name 必填且不超过 64 字符");
        }
    }

    private static void validatePassword(String password) {
        String value = password == null ? "" : password;
        if (value.length() < 8 || value.length() > 128) {
            throw ApiException.badRequest("password 长度须在 8-128 之间");
        }
    }

    /** 取出目标账号并确认它确实是审核账号（否则 404：既防探测也防误操作其它角色）。 */
    private User reviewerTarget(UUID id) {
        User target = userRepo.get(id)
                .orElseThrow(() -> ApiException.notFoundResource("reviewer `" + id + "`"));
        if (!REVIEWER_ROLE.equals(target.role())) {
            throw ApiException.notFoundResource("reviewer `" + id + "`");
        }
        return target;
    }

    /** 审核账号列表。 */
    @Operation(summary = "审核账号列表（仅平台管理员）")
    @GetMapping
    public List<User> listReviewers(AuthUser auth) {
        userRepo.requireAdmin(auth.userId());
        return userRepo.listByRole(REVIEWER_ROLE);
    }

    /** 创建审核账号（平台管理员）。 */
    @Operation(summary = "创建审核账号（仅平台管理员）")
    @PostMapping
    public ResponseEntity<User> createReviewer(AuthUser auth, @RequestBody NewReviewer payload) {
        userRepo.requireAdmin(auth.userId());

        validatePhone(payload.phone());
        validateName(payload.name());
        validatePassword(payload.password());

        String passwordHash = Security.hashPassword(payload.password());
        User reviewer = authRepo.createReviewer(payload.phone().trim(), payload.name().trim(), passwordHash);
        return ResponseEntity.status(HttpStatus.CREATED).body(reviewer);
    }

    /** 启用 / 禁用审核账号（禁用会立即撤销其全部会话）。 */
    @Operation(summary = "启用 / 禁用审核账号（仅平台管理员）")
    @PostMapping("/{id}/active")
    public User setReviewerActive(AuthUser auth, @PathVariable UUID id,
                                  @RequestBody SetActiveRequest payload) {
        userRepo.requireAdmin(auth.userId());
        reviewerTarget(id);
        boolean active = payload.isActive();

        User updated = userRepo.update(id, new UpdateUser(null, active))
                .orElseThrow(() -> ApiException.notFoundResource("reviewer `" + id + "`"));

        if (!active) {
            // 禁用即刻踢下线：与「禁用账号令牌立即失效」的约定保持一致
            authRepo.revokeAllTokens(id);
        }
        return updated;
    }

    /** 重置审核账号密码（会撤销该账号全部会话，需重新登录）。 */
    @Operation(summary = "重置审核账号密码（仅平台管理员）")
    @PostMapping("/{id}/password")
    public ResponseEntity<Void> resetReviewerPassword(AuthUser auth, @PathVariable UUID id,
                                                      @RequestBody ResetPassword payload) {
        userRepo.requireAdmin(auth.userId());
        reviewerTarget(id);
        validatePassword(payload.password());

        String passwordHash = Security.hashPassword(payload.password());
        if (!authRepo.updatePassword(id, passwordHash)) {
            throw ApiException.notFoundResource("reviewer `" + id + "`");
        }
        authRepo.revokeAllTokens(id);

        return ResponseEntity.noContent().build();
    }

    /** 删除审核账号。 */
    @Operation(summary = "删除审核账号（仅平台管理员）")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteReviewer(AuthUser auth, @PathVariable UUID id) {
        userRepo.requireAdmin(auth.userId());
        reviewerTarget(id);

        userRepo.delete(id);
        return ResponseEntity.noContent().build();
    }
}
