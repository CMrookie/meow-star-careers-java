package com.meowstar.careers.model.user;

import io.swagger.v3.oas.annotations.media.Schema;

/** 更新用户请求体（字段均为可选，至少提供一个）。 */
public record UpdateUser(
        @Schema(example = "Alice Smith") String name,
        @Schema(example = "false") Boolean isActive) {
}
