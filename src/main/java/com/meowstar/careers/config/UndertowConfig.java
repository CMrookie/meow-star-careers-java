package com.meowstar.careers.config;

import io.undertow.UndertowOptions;
import org.springframework.boot.web.embedded.undertow.UndertowServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Undertow 定制：允许 URL 中出现未转义的非 ASCII 字符。
 *
 * <p>Rust 版 actix-web 接受 {@code /api/v1/jobs?location=北京} 这类未百分号编码的查询串
 * （README 的 curl 示例就是这种写法）；Tomcat 会在 HTTP 解析层直接 400。这里打开 Undertow 的
 * {@code ALLOW_UNESCAPED_CHARACTERS_IN_URL} 使行为与 Rust 版一致，标准的百分号编码同样支持。
 */
@Configuration
public class UndertowConfig {

    @Bean
    public WebServerFactoryCustomizer<UndertowServletWebServerFactory> undertowUnescapedUrlCustomizer() {
        return factory -> factory.addBuilderCustomizers(
                builder -> builder.setServerOption(UndertowOptions.ALLOW_UNESCAPED_CHARACTERS_IN_URL, Boolean.TRUE));
    }
}
