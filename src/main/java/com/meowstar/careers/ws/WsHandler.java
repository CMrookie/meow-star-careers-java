package com.meowstar.careers.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meowstar.careers.error.ApiException;
import com.meowstar.careers.model.chat.ConversationRow;
import com.meowstar.careers.model.chat.Message;
import com.meowstar.careers.repo.ChatRepo;
import com.meowstar.careers.repo.InterviewRepo;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * WebSocket 实时通道（与 Rust 版 ws.rs 的报文协议逐字对齐）：
 * <ul>
 *   <li>上行：{@code {"type":"send","conversationId":"...","body":"..."}}、
 *       {@code {"type":"ping"}}、{@code {"type":"signal","interviewId":"...","payload":{...}}}</li>
 *   <li>下行：message / ack / pong / error / interview-signal / interview-updated /
 *       application-updated / conversation-read</li>
 * </ul>
 */
@Component
public class WsHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(WsHandler.class);
    private static final String ATTR_TRACKED_SESSION = "trackedSessionId";
    /** Rust: body.len() > 2000 指的是 UTF-8 字节数 */
    private static final int MAX_BODY_BYTES = 2000;

    private final ChatRepo chatRepo;
    private final InterviewRepo interviewRepo;
    private final RealtimeHub hub;
    private final RealtimeNotifier notifier;
    private final WsEvents events;
    private final ObjectMapper mapper;

    public WsHandler(ChatRepo chatRepo, InterviewRepo interviewRepo, RealtimeHub hub,
                     RealtimeNotifier notifier, WsEvents events, ObjectMapper mapper) {
        this.chatRepo = chatRepo;
        this.interviewRepo = interviewRepo;
        this.hub = hub;
        this.notifier = notifier;
        this.events = events;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        UUID userId = userId(session);
        if (userId == null) {
            closeQuietly(session);
            return;
        }
        WebSocketSession tracked = hub.register(userId, session);
        session.getAttributes().put(ATTR_TRACKED_SESSION, tracked.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        UUID userId = userId(session);
        if (userId == null) {
            return;
        }
        handleIncomingText(session, userId, message.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        UUID userId = userId(session);
        Object trackedId = session.getAttributes().get(ATTR_TRACKED_SESSION);
        if (userId != null) {
            hub.unregister(userId, trackedId == null ? session.getId() : trackedId.toString());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("ws transport error session={} err={}", session.getId(), exception.toString());
        closeQuietly(session);
    }

    // ------------------------------------------------------------------ 报文处理

    private void handleIncomingText(WebSocketSession session, UUID userId, String text) {
        JsonNode root;
        try {
            root = mapper.readTree(text);
        } catch (Exception ex) {
            send(session, events.error("bad_request", "非法报文：应为 JSON { type, conversationId?, body? }"));
            return;
        }
        JsonNode typeNode = root.path("type");
        if (!typeNode.isTextual()) {
            send(session, events.error("bad_request", "非法报文：应为 JSON { type, conversationId?, body? }"));
            return;
        }

        switch (typeNode.asText()) {
            case "ping" -> send(session, events.pong());
            case "send" -> handleSend(session, userId, root);
            case "signal" -> handleSignal(session, userId, root);
            default -> send(session, events.error("bad_request", "未知报文类型: " + typeNode.asText()));
        }
    }

    private void handleSend(WebSocketSession session, UUID userId, JsonNode root) {
        UUID conversationId = uuid(root.path("conversationId"));
        JsonNode bodyNode = root.path("body");
        if (conversationId == null || !bodyNode.isTextual()) {
            send(session, events.error("bad_request", "send 报文需要 conversationId 与 body"));
            return;
        }
        String body = bodyNode.asText().trim();
        if (body.isEmpty() || body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            send(session, events.error("bad_request", "body 不能为空且不超过 2000 字符"));
            return;
        }

        try {
            ConversationRow conversation = chatRepo.findConversationForMember(conversationId, userId)
                    .orElseThrow(() -> ApiException.forbidden("conversation 不存在或不属于当前用户"));
            chatRepo.requireMember(conversation, userId);
            Message message = chatRepo.insertMessage(conversation, userId, body);
            notifier.deliverMessage(message);
            send(session, events.ack(conversationId));
        } catch (ApiException ex) {
            send(session, events.error(ex.code(), ex.getMessage()));
        }
    }

    private void handleSignal(WebSocketSession session, UUID userId, JsonNode root) {
        UUID interviewId = uuid(root.path("interviewId"));
        JsonNode payload = root.get("payload");
        if (interviewId == null || payload == null || payload.isNull()) {
            send(session, events.error("bad_request", "signal 报文需要 interviewId 与 payload"));
            return;
        }
        try {
            Optional<UUID> peer = interviewRepo.peerOf(interviewId, userId);
            if (peer.isPresent()) {
                log.info("ws interview signal forward interview_id={} from={} to={} kind={}",
                        interviewId, userId, peer.get(), payload.path("kind").asText(""));
                hub.deliver(peer.get(), events.interviewSignal(interviewId, userId, payload));
            } else {
                send(session, events.error("forbidden", "非面试参与者或面试不存在"));
            }
        } catch (ApiException ex) {
            send(session, events.error(ex.code(), ex.getMessage()));
        }
    }

    // ------------------------------------------------------------------ 工具

    private static UUID userId(WebSocketSession session) {
        Object value = session.getAttributes().get(WsHandshakeInterceptor.ATTR_USER_ID);
        return value instanceof UUID id ? id : null;
    }

    private static UUID uuid(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        try {
            return UUID.fromString(node.asText());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void send(WebSocketSession session, String payload) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(payload));
            }
        } catch (Exception ex) {
            log.debug("ws 发送失败 session={} err={}", session.getId(), ex.toString());
        }
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close(CloseStatus.SERVER_ERROR);
        } catch (Exception ignored) {
            // 忽略关闭异常
        }
    }
}
