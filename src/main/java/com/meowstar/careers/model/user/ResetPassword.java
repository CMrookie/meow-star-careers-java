package com.meowstar.careers.model.user;

import io.swagger.v3.oas.annotations.media.Schema;

/** 重置审核账号密码（仅平台管理员；重置后该账号已登录的全部会话立即失效）。 */
public record ResetPassword(
        @Schema(example = "secret123", minLength = 8) String password) {
}
