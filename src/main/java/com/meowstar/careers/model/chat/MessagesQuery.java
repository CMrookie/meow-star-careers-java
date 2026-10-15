package com.meowstar.careers.model.chat;

/**
 * 消息历史查询参数（对齐 Rust {@code handlers::chat::MessagesQuery}）。
 *
 * @param limit  每页条数（默认 50，上限 200；仓储层最终 clamp 到 1..200）
 * @param before 消息 id 游标：返回比该 id 更早的消息
 */
public record MessagesQuery(
        Long limit,
        Long before) {
}
