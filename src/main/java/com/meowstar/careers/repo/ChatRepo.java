package com.meowstar.careers.repo;

import com.meowstar.careers.db.Rows;
import com.meowstar.careers.error.ApiException;
import com.meowstar.careers.model.chat.ConversationRow;
import com.meowstar.careers.model.chat.Message;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 私聊数据访问（对齐 Rust 版 {@code repositories::chat}）：
 * 会话以 (user_lo, user_hi) 唯一约束保证两人只有一个会话。
 */
@Repository
public class ChatRepo {

    /** conversations 表列清单（snake_case，供 DataClassRowMapper 映射到 camelCase 记录组件）。 */
    static final String CONVERSATION_COLUMNS = "id, user_lo, user_hi, created_at, updated_at";

    /** messages 表列清单。 */
    static final String MESSAGE_COLUMNS =
            "id, conversation_id, sender_id, recipient_id, content, created_at, read_at";

    private final JdbcTemplate jdbc;

    public ChatRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 会话行 + 该会话的末条消息（可能为空）+ 我的未读数。 */
    public record ConversationEntry(ConversationRow conversation, Message lastMessage, long unreadCount) {
    }

    /** 判断用户是否存在且启用。 */
    public boolean userExistsActive(UUID userId) {
        if (userId == null) {
            return false;
        }
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM users WHERE id = ? AND is_active = true)",
                Boolean.class, userId);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * 获取或创建两个用户之间的一对一会话（供会话列表/发起复用）。
     *
     * <p>归一化用「无符号 128 位」比较，等价 Rust {@code Uuid} 的 Ord 与
     * PostgreSQL {@code uuid} 的字节序比较，保证 {@code user_lo < user_hi} 满足 CHECK 约束、
     * 且 (a,b) 与 (b,a) 落到同一行。
     */
    public Optional<ConversationRow> findOrCreateBetween(UUID userA, UUID userB) {
        UUID[] pair = normalize(userA, userB);
        jdbc.update(
                "INSERT INTO conversations (user_lo, user_hi) VALUES (?, ?) "
                        + "ON CONFLICT (user_lo, user_hi) DO NOTHING",
                pair[0], pair[1]);
        return findByPair(pair[0], pair[1]);
    }

    /** 当前用户参与的会话（按 updated_at 倒序，含每个会话的末条消息与未读数）。 */
    public List<ConversationEntry> listForUser(UUID user) {
        List<ConversationRow> conversations = jdbc.query(
                "SELECT " + CONVERSATION_COLUMNS + " FROM conversations "
                        + "WHERE user_lo = ? OR user_hi = ? ORDER BY updated_at DESC",
                Rows.of(ConversationRow.class), user, user);

        List<ConversationEntry> result = new ArrayList<>(conversations.size());
        for (ConversationRow conv : conversations) {
            Message last = jdbc.query(
                    "SELECT " + MESSAGE_COLUMNS + " FROM messages WHERE conversation_id = ? "
                            + "ORDER BY id DESC LIMIT 1",
                    Rows.of(Message.class), conv.id()).stream().findFirst().orElse(null);
            long unread = countUnread(conv.id(), user);
            result.add(new ConversationEntry(conv, last, unread));
        }
        return result;
    }

    /**
     * 校验会话归属：必须是参与者才能访问（空 = 越权）。
     *
     * <p>WebSocket 层契约方法（PORTING.md §6）。
     */
    public Optional<ConversationRow> findConversationForMember(UUID conversationId, UUID userId) {
        return jdbc.query(
                "SELECT " + CONVERSATION_COLUMNS + " FROM conversations "
                        + "WHERE id = ? AND (user_lo = ? OR user_hi = ?)",
                Rows.of(ConversationRow.class), conversationId, userId, userId).stream().findFirst();
    }

    /** 已有会话行直接放行；否则 403（对齐 Rust {@code require_member}）。 */
    public void requireMember(ConversationRow conversation, UUID userId) {
        if (conversation != null && userId != null
                && (userId.equals(conversation.userLo()) || userId.equals(conversation.userHi()))) {
            return;
        }
        throw ApiException.forbidden("conversation 不属于当前用户");
    }

    /** 会话中的另一位成员 id（对齐 Rust {@code other_party}）。 */
    public UUID otherParty(ConversationRow conversation, UUID me) {
        if (me != null && me.equals(conversation.userLo())) {
            return conversation.userHi();
        }
        return conversation.userLo();
    }

    /**
     * 在会话内插入消息并刷新会话时间（事务内完成），返回落库后的消息。
     *
     * <p>WebSocket 层契约方法（PORTING.md §6）。
     */
    @Transactional
    public Message insertMessage(ConversationRow conversation, UUID senderId, String body) {
        UUID recipient = otherParty(conversation, senderId);
        Message message = jdbc.queryForObject(
                "INSERT INTO messages (conversation_id, sender_id, recipient_id, content) "
                        + "VALUES (?, ?, ?, ?) RETURNING " + MESSAGE_COLUMNS,
                Rows.of(Message.class), conversation.id(), senderId, recipient, body);
        jdbc.update("UPDATE conversations SET updated_at = now() WHERE id = ?", conversation.id());
        return message;
    }

    /**
     * 拉取某会话的历史消息（升序）；before 为消息 id 游标（不含），limit 默认 50。
     * Rust {@code limit.clamp(1, 200)} 同样在仓储层兜底。
     */
    public List<Message> listMessages(UUID conversationId, Long before, long limit) {
        long effective = Math.clamp(limit, 1L, 200L);

        List<Message> messages;
        if (before != null) {
            messages = jdbc.query(
                    "SELECT " + MESSAGE_COLUMNS + " FROM messages "
                            + "WHERE conversation_id = ? AND id < ? ORDER BY id DESC LIMIT ?",
                    Rows.of(Message.class), conversationId, before, effective);
        } else {
            messages = jdbc.query(
                    "SELECT " + MESSAGE_COLUMNS + " FROM messages "
                            + "WHERE conversation_id = ? ORDER BY id DESC LIMIT ?",
                    Rows.of(Message.class), conversationId, effective);
        }
        Collections.reverse(messages);
        return messages;
    }

    /** 把对方发给我的消息全部标记为已读，返回标记条数。 */
    public long markConversationRead(UUID conversationId, UUID reader) {
        return jdbc.update(
                "UPDATE messages SET read_at = now() "
                        + "WHERE conversation_id = ? AND recipient_id = ? AND read_at IS NULL",
                conversationId, reader);
    }

    /** 某会话中我的未读数（收件人是我且 read_at 为空）。 */
    public long countUnread(UUID conversationId, UUID userId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM messages "
                        + "WHERE conversation_id = ? AND recipient_id = ? AND read_at IS NULL",
                Long.class, conversationId, userId);
        return count == null ? 0L : count;
    }

    /** 会话中 user_id 的对端（用于已读回执实时同步）；非参与者返回空。 */
    public Optional<UUID> peerForMember(UUID conversationId, UUID userId) {
        List<UUID[]> rows = jdbc.query(
                "SELECT user_lo, user_hi FROM conversations WHERE id = ?",
                (rs, rowNum) -> new UUID[] {
                        rs.getObject("user_lo", UUID.class),
                        rs.getObject("user_hi", UUID.class)},
                conversationId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        UUID[] row = rows.get(0);
        if (row[0].equals(userId)) {
            return Optional.of(row[1]);
        }
        if (row[1].equals(userId)) {
            return Optional.of(row[0]);
        }
        return Optional.empty();
    }

    private Optional<ConversationRow> findByPair(UUID lo, UUID hi) {
        return jdbc.query(
                "SELECT " + CONVERSATION_COLUMNS + " FROM conversations WHERE user_lo = ? AND user_hi = ?",
                Rows.of(ConversationRow.class), lo, hi).stream().findFirst();
    }

    /** 两个用户 id 规范化为 (lo, hi)：无符号比较，与 Rust uuid Ord / PostgreSQL uuid 排序一致。 */
    private static UUID[] normalize(UUID a, UUID b) {
        return lessThan(a, b) ? new UUID[] {a, b} : new UUID[] {b, a};
    }

    private static boolean lessThan(UUID a, UUID b) {
        int high = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        if (high != 0) {
            return high < 0;
        }
        return Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits()) < 0;
    }
}
