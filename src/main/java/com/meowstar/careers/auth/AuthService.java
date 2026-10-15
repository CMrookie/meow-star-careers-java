package com.meowstar.careers.auth;

import com.meowstar.careers.error.ApiException;
import com.meowstar.careers.model.user.User;
import com.meowstar.careers.repo.AuthRepo;
import org.springframework.stereotype.Service;

/**
 * 认证服务：从 {@code Authorization: Bearer <token>} 解析并校验令牌（查库），
 * 失败时抛出统一的 401（与 Rust 版 AuthenticatedUser 提取器的错误一致）。
 */
@Service
public class AuthService {

    private final AuthRepo authRepo;

    public AuthService(AuthRepo authRepo) {
        this.authRepo = authRepo;
    }

    /** 校验 Authorization 头并返回已认证用户。 */
    public AuthUser authenticate(String authorizationHeader) {
        String token = bearerToken(authorizationHeader);
        if (token == null) {
            throw ApiException.unauthorized("missing bearer token");
        }
        User user = authRepo.findUserByToken(token)
                .orElseThrow(() -> ApiException.unauthorized("invalid or expired token"));
        return new AuthUser(user.id(), token);
    }

    /** 解析 Bearer 令牌；缺失或为空返回 null。 */
    public static String bearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authorizationHeader.substring("Bearer ".length()).trim();
        return token.isEmpty() ? null : token;
    }
}
