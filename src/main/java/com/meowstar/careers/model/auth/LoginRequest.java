package com.meowstar.careers.model.auth;

import io.swagger.v3.oas.annotations.media.Schema;

/** 登录请求（手机号 + 密码）。 */
public record LoginRequest(
        @Schema(example = "13800138000", minLength = 11, maxLength = 11) String phone,
        @Schema(example = "secret123") String password) {
}
