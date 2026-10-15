package com.meowstar.careers.model.chat;

/** 标记已读的结果体（对齐 Rust {@code json!({ "marked": marked })}）。 */
public record MarkedCount(
        long marked) {
}
