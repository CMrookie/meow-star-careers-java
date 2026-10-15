package com.meowstar.careers.model.auth;

import com.meowstar.careers.model.user.User;

/** 认证成功响应：签发的一次性令牌 + 用户信息。 */
public record AuthResponse(String token, User user) {
}
