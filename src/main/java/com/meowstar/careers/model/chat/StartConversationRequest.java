package com.meowstar.careers.model.chat;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** 发起私聊请求体。 */
public record StartConversationRequest(
        @Schema(example = "uuid-of-other-user") UUID userId) {
}
