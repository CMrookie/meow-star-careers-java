package com.meowstar.careers.ws;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * 实时枢纽（对齐 Rust 版 {@code ws::RealtimeHub}）：维护 user_id -> 在线连接集合，
 * 负责把下行负载推送给指定用户的<b>全部</b>在线连接（同一账号多端同时在线）。
 *
 * <p>Spring 的 WebSocketSession 不是线程安全的，因此登记时统一包装为
 * {@link ConcurrentWebSocketSessionDecorator}，允许从任意业务线程安全发送。
 */
@Component
public class RealtimeHub {

    private static final Logger log = LoggerFactory.getLogger(RealtimeHub.class);
    /** 单连接发送阻塞上限与缓冲上限（超出则视为慢连接丢弃） */
    private static final int SEND_TIME_LIMIT_MS = 5_000;
    private static final int BUFFER_SIZE_LIMIT = 512 * 1024;

    private final Map<UUID, List<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    /** 注册一条新连接（返回被登记的实际会话，注销时需用同一个引用）。 */
    public WebSocketSession register(UUID userId, WebSocketSession session) {
        WebSocketSession tracked = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT);
        sessions.computeIfAbsent(userId, ignored -> new CopyOnWriteArrayList<>()).add(tracked);
        return tracked;
    }

    /** 连接断开时注销。 */
    public void unregister(UUID userId, String sessionId) {
        List<WebSocketSession> list = sessions.get(userId);
        if (list == null) {
            return;
        }
        list.removeIf(item -> item.getId().equals(sessionId));
        if (list.isEmpty()) {
            sessions.remove(userId);
        }
    }

    /** 把文本负载推送给某用户的全部在线连接。 */
    public void deliver(UUID userId, String payload) {
        List<WebSocketSession> list = sessions.get(userId);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (WebSocketSession session : list) {
            if (!session.isOpen()) {
                continue;
            }
            try {
                session.sendMessage(new TextMessage(payload));
            } catch (IOException | RuntimeException ex) {
                // 慢连接 / 已断开：剔除该连接，不影响其它连接
                log.debug("ws 推送失败，剔除连接 user_id={} session={} err={}", userId, session.getId(),
                        ex.toString());
                unregister(userId, session.getId());
            }
        }
    }

    /** 某用户当前在线连接数（诊断用）。 */
    public int onlineConnections(UUID userId) {
        List<WebSocketSession> list = sessions.get(userId);
        return list == null ? 0 : list.size();
    }
}
