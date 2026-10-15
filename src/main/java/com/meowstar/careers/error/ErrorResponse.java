package com.meowstar.careers.error;

import io.swagger.v3.oas.annotations.media.Schema;

/** 统一 JSON 错误响应体（code 为机器可读错误码，message 为人类可读描述），与 Rust 版逐字段一致。 */
@Schema(description = "统一错误响应")
public record ErrorResponse(
        @Schema(description = "机器可读错误码", example = "not_found") String code,
        @Schema(description = "人类可读描述", example = "route not found") String message) {
}
