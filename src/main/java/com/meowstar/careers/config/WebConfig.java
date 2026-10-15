package com.meowstar.careers.config;

import com.meowstar.careers.auth.AuthUserArgumentResolver;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 装配：鉴权参数解析器 + CORS 策略。
 *
 * <p>CORS 与 Rust 版一致：默认（未配置 CORS_ALLOWED_ORIGINS）不带跨域头，浏览器仅允许同源访问；
 * 配置了白名单才按列表放行，且不允许携带凭据。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final List<String> METHODS = List.of("GET", "POST", "PUT", "DELETE", "OPTIONS");
    private static final List<String> HEADERS =
            List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, HttpHeaders.ACCEPT);

    private final AppProps props;
    private final AuthUserArgumentResolver authUserArgumentResolver;

    public WebConfig(AppProps props, AuthUserArgumentResolver authUserArgumentResolver) {
        this.props = props;
        this.authUserArgumentResolver = authUserArgumentResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(authUserArgumentResolver);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (props.corsOrigins().isEmpty()) {
            return; // 默认拒绝跨域
        }
        registry.addMapping("/**")
                .allowedOrigins(props.corsOrigins().toArray(String[]::new))
                .allowedMethods(METHODS.toArray(String[]::new))
                .allowedHeaders(HEADERS.toArray(String[]::new))
                .maxAge(3600);
    }
}
