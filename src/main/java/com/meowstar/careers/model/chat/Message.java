package com.meowstar.careers.model.chat;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

/** messages 表的一行（同时也是 API 消息实体）。 */
@Schema(description = "私聊消息")
public record Message(
        Long id,
        UUID conversationId,
        UUID senderId,
        UUID recipientId,
        String content,
        OffsetDateTime createdAt,
        OffsetDateTime readAt) {
}
