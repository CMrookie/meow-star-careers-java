package com.meowstar.careers.model.user;

import io.swagger.v3.oas.annotations.media.Schema;

/** 启用 / 禁用请求体（审核账号管理）。 */
public record SetActiveRequest(
        @Schema(example = "false") boolean isActive) {
}
