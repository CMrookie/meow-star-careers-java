package com.meowstar.careers.model.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.meowstar.careers.model.user.UserPublic;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 会话列表项视图（lastMessage 为空时不输出该字段，对齐 Rust 的 skip_serializing_if）。 */
public record ConversationSummary(
        UUID id,
        UserPublic peer,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) Message lastMessage,
        long unreadCount) {
}
