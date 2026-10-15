package com.meowstar.careers.auth;

import java.util.UUID;

/**
 * 已认证用户（由参数解析器从 {@code Authorization: Bearer <token>} 注入处理器）。
 *
 * @param userId 用户 id
 * @param token  原始令牌（登出时使用）
 */
public record AuthUser(UUID userId, String token) {
}
