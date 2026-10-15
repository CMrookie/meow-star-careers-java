package com.meowstar.careers.ws;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * WebSocket 装配：GET /ws?token=xxx（与 Rust 版同一路径、同一鉴权方式）。
 * 单条文本报文上限 2 MiB（对齐 actix-ws 的 max_continuation_size）。
 */
@Configuration
@EnableWebSocket
public class WsConfig implements WebSocketConfigurer {

    private static final int MAX_TEXT_MESSAGE_BYTES = 2 * 1024 * 1024;

    private final WsHandler wsHandler;
    private final WsHandshakeInterceptor handshakeInterceptor;

    public WsConfig(WsHandler wsHandler, WsHandshakeInterceptor handshakeInterceptor) {
        this.wsHandler = wsHandler;
        this.handshakeInterceptor = handshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(wsHandler, "/ws")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(MAX_TEXT_MESSAGE_BYTES);
        container.setMaxBinaryMessageBufferSize(MAX_TEXT_MESSAGE_BYTES);
        return container;
    }
}
