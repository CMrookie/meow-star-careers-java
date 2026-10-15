package com.meowstar.careers.model.chat;

import io.swagger.v3.oas.annotations.media.Schema;

/** 发送消息请求体。 */
public record NewMessage(
        @Schema(example = "你好！", maxLength = 2000) String body) {
}
