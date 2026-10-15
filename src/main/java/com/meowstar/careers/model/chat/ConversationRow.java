package com.meowstar.careers.model.chat;

import java.time.OffsetDateTime;
import java.util.UUID;

/** conversations 表的一行（user_lo < user_hi 唯一化）。 */
public record ConversationRow(
        UUID id,
        UUID userLo,
        UUID userHi,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
