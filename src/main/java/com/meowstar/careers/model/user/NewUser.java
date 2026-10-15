package com.meowstar.careers.model.user;

import io.swagger.v3.oas.annotations.media.Schema;

/** 创建用户请求体（管理端用；auth/register 走手机号）。 */
public record NewUser(
        @Schema(example = "alice@example.com") String email,
        @Schema(example = "Alice") String name) {
}
