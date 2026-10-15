package com.meowstar.careers.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meowstar.careers.error.ErrorResponse;
import com.meowstar.careers.model.user.User;
import com.meowstar.careers.repo.AuthRepo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * WebSocket 握手鉴权：浏览器 WebSocket 无法自带头，因此令牌走查询参数
 * {@code GET /ws?token=xxx}（与 Rust 版 ws_upgrade 一致）。
 * 令牌缺失/无效时返回 401 统一 JSON 并拒绝升级。
 */
@Component
public class WsHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(WsHandshakeInterceptor.class);
    /** 会话属性键：已认证用户 id */
    public static final String ATTR_USER_ID = "userId";

    private final AuthRepo authRepo;
    private final ObjectMapper objectMapper;

    public WsHandshakeInterceptor(AuthRepo authRepo, ObjectMapper objectMapper) {
        this.authRepo = authRepo;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) throws IOException {
        String token = UriComponentsBuilder.fromUri(request.getURI()).build()
                .getQueryParams().getFirst("token");
        if (token == null || token.isEmpty()) {
            return reject(response, "缺少 token 查询参数");
        }
        Optional<User> user = authRepo.findUserByToken(token);
        if (user.isEmpty()) {
            return reject(response, "无效或已过期的 token");
        }
        attributes.put(ATTR_USER_ID, user.get().id());
        log.info("ws connected user_id={}", user.get().id());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 无需处理
    }

    private boolean reject(ServerHttpResponse response, String message) throws IOException {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = objectMapper.writeValueAsBytes(new ErrorResponse("unauthorized", message));
        response.getBody().write(body);
        response.getBody().flush();
        return false;
    }
}
