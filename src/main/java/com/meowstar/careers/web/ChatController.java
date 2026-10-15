package com.meowstar.careers.web;

import com.meowstar.careers.auth.AuthUser;
import com.meowstar.careers.error.ApiException;
import com.meowstar.careers.model.chat.ConversationRow;
import com.meowstar.careers.model.chat.ConversationSummary;
import com.meowstar.careers.model.chat.MarkedCount;
import com.meowstar.careers.model.chat.Message;
import com.meowstar.careers.model.chat.MessagesQuery;
import com.meowstar.careers.model.chat.NewMessage;
import com.meowstar.careers.model.chat.StartConversationRequest;
import com.meowstar.careers.model.user.User;
import com.meowstar.careers.model.user.UserPublic;
import com.meowstar.careers.repo.ChatRepo;
import com.meowstar.careers.repo.UserRepo;
import com.meowstar.careers.ws.RealtimeNotifier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 私聊 REST 端点：会话创建/列表、消息历史/发送/标记已读（对齐 Rust {@code handlers::chat}）。
 * WebSocket 升级与实时推送见 {@code ws}；REST 发消息与 /ws 的 send 走同一条「入库 + 推送」链路。
 */
@RestController
@RequestMapping("/api/v1/conversations")
@Tag(name = "chat", description = "一对一私聊（REST；实时通道见 /ws 与 README）")
public class ChatController {

    /** 非法/缺字段 JSON 的统一文案（对齐 Rust {@code error::json_error_handler}）。 */
    private static final String BAD_JSON = "malformed or invalid JSON request body";

    private final ChatRepo chatRepo;
    private final UserRepo userRepo;
    private final RealtimeNotifier notifier;

    public ChatController(ChatRepo chatRepo, UserRepo userRepo, RealtimeNotifier notifier) {
        this.chatRepo = chatRepo;
        this.userRepo = userRepo;
        this.notifier = notifier;
    }

    @Operation(summary = "发起会话", description = "获取或创建与某用户的一对一会话（不能与自己聊天）")
    @PostMapping
    public ConversationSummary startConversation(@RequestBody StartConversationRequest payload, AuthUser auth) {
        UUID me = auth.userId();
        if (payload == null || payload.userId() == null) {
            throw ApiException.badRequest(BAD_JSON);
        }
        UUID other = payload.userId();

        if (me.equals(other)) {
            throw ApiException.badRequest("不能与自己建立私聊会话");
        }
        if (!chatRepo.userExistsActive(other)) {
            throw ApiException.notFoundUser(other);
        }

        ConversationRow conversation = chatRepo.findOrCreateBetween(me, other)
                .orElseThrow(() -> ApiException.notFound("record not found"));
        UserPublic peer = peerPublic(chatRepo.otherParty(conversation, me));

        return new ConversationSummary(
                conversation.id(),
                peer,
                conversation.createdAt(),
                conversation.updatedAt(),
                null,
                0L);
    }

    @Operation(summary = "我的会话列表", description = "按最近活跃倒序，含对端、末条消息与未读数")
    @GetMapping
    public List<ConversationSummary> listConversations(AuthUser auth) {
        UUID me = auth.userId();
        List<ChatRepo.ConversationEntry> rows = chatRepo.listForUser(me);

        List<ConversationSummary> summaries = new ArrayList<>(rows.size());
        for (ChatRepo.ConversationEntry row : rows) {
            ConversationRow conversation = row.conversation();
            // Rust：对端用户已不存在时跳过该会话
            Optional<User> peer = userRepo.get(chatRepo.otherParty(conversation, me));
            if (peer.isEmpty()) {
                continue;
            }
            User user = peer.get();
            summaries.add(new ConversationSummary(
                    conversation.id(),
                    new UserPublic(user.id(), user.name()),
                    conversation.createdAt(),
                    conversation.updatedAt(),
                    row.lastMessage(),
                    row.unreadCount()));
        }
        return summaries;
    }

    @Operation(summary = "会话详情", description = "含对端与我的未读数；非成员 403")
    @GetMapping("/{id}")
    public ConversationSummary getConversation(@PathVariable UUID id, AuthUser auth) {
        UUID me = auth.userId();
        ConversationRow conversation = chatRepo.findConversationForMember(id, me)
                .orElseThrow(() -> ApiException.forbidden("conversation 不存在或不属于当前用户"));

        UserPublic peer = peerPublic(chatRepo.otherParty(conversation, me));
        long unread = chatRepo.countUnread(conversation.id(), me);

        return new ConversationSummary(
                conversation.id(),
                peer,
                conversation.createdAt(),
                conversation.updatedAt(),
                null,
                unread);
    }

    @Operation(summary = "消息历史", description = "升序返回；limit 默认 50 上限 200，before 为消息 id 游标（更早的消息）")
    @GetMapping("/{id}/messages")
    public List<Message> listMessages(@PathVariable UUID id, MessagesQuery query, AuthUser auth) {
        ConversationRow conversation = chatRepo.findConversationForMember(id, auth.userId())
                .orElseThrow(() -> ApiException.forbidden("conversation 不存在或不属于当前用户"));

        Long before = query == null ? null : query.before();
        long limit = query == null || query.limit() == null ? 50L : query.limit();
        return chatRepo.listMessages(conversation.id(), before, limit);
    }

    @Operation(summary = "发送消息", description = "入库并实时推送给双方在线连接（与 /ws 的 send 同一条链路）")
    @PostMapping("/{id}/messages")
    public ResponseEntity<Message> sendMessage(@PathVariable UUID id,
                                               @RequestBody NewMessage payload,
                                               AuthUser auth) {
        if (payload == null || payload.body() == null) {
            throw ApiException.badRequest(BAD_JSON);
        }
        String body = payload.body().trim();
        if (body.isEmpty() || body.getBytes(StandardCharsets.UTF_8).length > 2000) {
            throw ApiException.badRequest("body 不能为空且不超过 2000 字符");
        }

        // 与 ws::persist_and_notify 完全一致的三步 + 推送
        ConversationRow conversation = chatRepo.findConversationForMember(id, auth.userId())
                .orElseThrow(() -> ApiException.forbidden("conversation 不存在或不属于当前用户"));
        chatRepo.requireMember(conversation, auth.userId());
        Message message = chatRepo.insertMessage(conversation, auth.userId(), body);
        notifier.deliverMessage(message);

        return ResponseEntity.status(HttpStatus.CREATED).body(message);
    }

    @Operation(summary = "标记已读", description = "把该会话中对方发给我的消息标记为已读，并通知对端（conversation-read）")
    @PostMapping("/{id}/read")
    public MarkedCount markRead(@PathVariable UUID id, AuthUser auth) {
        UUID me = auth.userId();
        ConversationRow conversation = chatRepo.findConversationForMember(id, me)
                .orElseThrow(() -> ApiException.forbidden("conversation 不存在或不属于当前用户"));

        long marked = chatRepo.markConversationRead(conversation.id(), me);
        try {
            // Rust：`if let Ok(Some(peer)) = ...` —— 取对端失败时静默跳过推送
            chatRepo.peerForMember(conversation.id(), me)
                    .ifPresent(peer -> notifier.notifyConversationRead(conversation.id(), me, peer));
        } catch (RuntimeException ignored) {
            // 与 Rust 一致：已读回执推送失败不影响标记结果
        }
        return new MarkedCount(marked);
    }

    /** 会话对端的最小用户信息；用户不存在时 404（对齐 Rust {@code peer_public}）。 */
    private UserPublic peerPublic(UUID peerId) {
        User user = userRepo.get(peerId).orElseThrow(() -> ApiException.notFoundUser(peerId));
        return new UserPublic(user.id(), user.name());
    }
}
