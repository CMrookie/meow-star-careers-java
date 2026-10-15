package com.meowstar.careers.web;

import com.meowstar.careers.auth.AuthUser;
import com.meowstar.careers.error.ApiException;
import com.meowstar.careers.limiter.LoginLimiter;
import com.meowstar.careers.model.auth.AuthResponse;
import com.meowstar.careers.model.auth.LoginRequest;
import com.meowstar.careers.model.auth.RegisterRequest;
import com.meowstar.careers.model.auth.Role;
import com.meowstar.careers.model.company.NewCompany;
import com.meowstar.careers.model.user.User;
import com.meowstar.careers.repo.AuthRepo;
import com.meowstar.careers.repo.UserRepo;
import com.meowstar.careers.security.Security;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证端点：注册（求职者 / 招聘者+企业）/ 登录 / 登出 / 当前用户。
 * 账号体系为「手机号 + 密码」；历史邮箱账号不受影响（users.email 可空）。
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "auth", description = "注册 / 登录 / 令牌")
public class AuthController {

    private final AuthRepo authRepo;
    private final UserRepo userRepo;
    private final LoginLimiter loginLimiter;

    public AuthController(AuthRepo authRepo, UserRepo userRepo, LoginLimiter loginLimiter) {
        this.authRepo = authRepo;
        this.userRepo = userRepo;
        this.loginLimiter = loginLimiter;
    }

    @Operation(summary = "注册", description = "求职者或招聘者（招聘者同事务创建企业）并签发令牌")
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@RequestBody RegisterRequest payload) {
        validateRegister(payload);

        String passwordHash = Security.hashPassword(payload.password());
        String phone = payload.phone().trim();
        String name = payload.name().trim();

        User user = switch (payload.roleOrDefault()) {
            case RECRUITER -> {
                NewCompany company = payload.company();
                if (company == null) {
                    throw ApiException.badRequest("招聘者注册必须提供 company");
                }
                yield authRepo.registerRecruiter(phone, name, passwordHash, company);
            }
            case SEEKER -> authRepo.registerSeeker(phone, name, passwordHash);
            case ADMIN -> throw ApiException.badRequest(
                    "admin 账号不可自助注册（由服务端 ADMIN_PHONE / ADMIN_PASSWORD 引导创建）");
            case REVIEWER -> throw ApiException.badRequest(
                    "reviewer 审核账号不可自助注册（由平台管理员在 /reviewers 创建）");
        };

        String token = authRepo.issueToken(user.id());
        return ResponseEntity.status(HttpStatus.CREATED).body(new AuthResponse(token, user));
    }

    @Operation(summary = "登录", description = "校验手机号密码并签发新令牌（内置失败限流：5 次/15 分钟 -> 429）")
    @PostMapping("/login")
    public AuthResponse login(@RequestBody LoginRequest payload, HttpServletRequest request) {
        String phone = payload.phone() == null ? "" : payload.phone().trim();
        String password = payload.password() == null ? "" : payload.password();
        if (!Security.isValidCnPhone(phone) || password.isEmpty() || password.length() > 128) {
            throw ApiException.badRequest("手机号（11 位，1 开头）与 password 必填且长度合法");
        }

        // 防爆破：按「手机号|IP」限流
        String ip = request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
        String limiterKey = phone + "|" + ip;
        if (loginLimiter.blocked(limiterKey)) {
            throw ApiException.tooManyRequests("登录失败次数过多，请 15 分钟后再试");
        }

        Optional<AuthRepo.StoredUser> stored = authRepo.findByPhone(phone);
        if (stored.isEmpty()) {
            loginLimiter.recordFailure(limiterKey);
            throw ApiException.unauthorized("手机号或密码错误");
        }
        User user = stored.get().user();
        if (!user.isActive()) {
            loginLimiter.recordFailure(limiterKey);
            throw ApiException.forbidden("账号已被禁用");
        }
        String hash = stored.get().passwordHash();
        if (hash == null || !Security.verifyPassword(password, hash)) {
            loginLimiter.recordFailure(limiterKey);
            throw ApiException.unauthorized("手机号或密码错误");
        }

        loginLimiter.clear(limiterKey);
        String token = authRepo.issueToken(user.id());
        return new AuthResponse(token, user);
    }

    @Operation(summary = "登出", description = "撤销当前令牌")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(AuthUser auth) {
        authRepo.revokeToken(auth.token());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "当前用户", description = "可用于前端校验令牌有效性")
    @GetMapping("/me")
    public User me(AuthUser auth) {
        return userRepo.getOrNotFound(auth.userId());
    }

    /** 注册参数校验（与 Rust 版 validate_register 完全一致的口径与错误信息）。 */
    static void validateRegister(RegisterRequest payload) {
        switch (payload.roleOrDefault()) {
            case ADMIN -> throw ApiException.badRequest(
                    "admin 账号不可自助注册（由服务端 ADMIN_PHONE / ADMIN_PASSWORD 引导创建）");
            case REVIEWER -> throw ApiException.badRequest(
                    "reviewer 审核账号不可自助注册（由平台管理员在 /reviewers 创建）");
            default -> {
                // seeker / recruiter 继续校验
            }
        }
        String phone = payload.phone() == null ? "" : payload.phone().trim();
        if (!Security.isValidCnPhone(phone)) {
            throw ApiException.badRequest("手机号必须为 11 位数字（1 开头）");
        }
        String name = payload.name() == null ? "" : payload.name().trim();
        if (name.isEmpty() || name.codePointCount(0, name.length()) > 64) {
            throw ApiException.badRequest("name 必填且不超过 64 字符");
        }
        String password = payload.password() == null ? "" : payload.password();
        if (password.length() < 8 || password.length() > 128) {
            throw ApiException.badRequest("password 长度须在 8-128 之间");
        }
        if (payload.roleOrDefault() == Role.RECRUITER) {
            NewCompany company = payload.company();
            if (company == null) {
                throw ApiException.badRequest("招聘者注册必须提供 company");
            }
            String companyName = company.name() == null ? "" : company.name().trim();
            if (companyName.isEmpty() || companyName.codePointCount(0, companyName.length()) > 128) {
                throw ApiException.badRequest("招聘者注册必须提供 company.name（≤128 字符）");
            }
            if (tooLong(company.industry(), 64) || tooLong(company.location(), 128)
                    || tooLong(company.description(), 2000) || tooLong(company.website(), 200)
                    || tooLong(company.logoUrl(), 500)) {
                throw ApiException.badRequest(
                        "企业字段超长（industry≤64 / location≤128 / description≤2000 / website≤200 / logoUrl≤500）");
            }
            if (company.staffSize() != null && (company.staffSize() < 0 || company.staffSize() > 10_000_000)) {
                throw ApiException.badRequest("company.staffSize 须在 0~10,000,000 之间（员工人数）");
            }
        }
    }

    private static boolean tooLong(String value, int max) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return trimmed.codePointCount(0, trimmed.length()) > max;
    }
}
