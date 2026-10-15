package com.meowstar.careers.model.user;

import io.swagger.v3.oas.annotations.media.Schema;

/** 新建审核专用账号（仅平台管理员；手机号 + 初始密码，登录后可改密）。 */
public record NewReviewer(
        @Schema(example = "13700000001", minLength = 11, maxLength = 11) String phone,
        @Schema(example = "审核员小王") String name,
        @Schema(example = "secret123", minLength = 8) String password) {
}
