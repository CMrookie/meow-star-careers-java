package com.meowstar.careers.model.push;

import io.swagger.v3.oas.annotations.media.Schema;

/** 设备推送令牌请求体（注册 / 注销共用），对齐 Rust 版 handlers::push::PushTokenPayload。 */
public record PushTokenPayload(
        @Schema(description = "android / ios / web", example = "android") String platform,
        @Schema(description = "设备推送令牌（≤512 字符）") String token) {
}
