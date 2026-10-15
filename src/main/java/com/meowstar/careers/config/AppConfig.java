package com.meowstar.careers.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 配置装配：从环境变量读取业务配置并做一致性校验（与 Rust 版 {@code Config::from_env} 对齐）。
 * 缺失/冲突时直接启动失败，避免带病运行。
 */
@Configuration
public class AppConfig {

    @Bean
    public AppProps appProps(Environment env) {
        String databaseUrl = env.getProperty("DATABASE_URL");
        if (databaseUrl == null || databaseUrl.isBlank()) {
            throw new IllegalStateException("缺少环境变量 DATABASE_URL（postgres://user:pass@host:port/db）");
        }

        List<String> corsOrigins = Arrays.stream(env.getProperty("CORS_ALLOWED_ORIGINS", "").split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toList();

        String adminPhone = envOpt(env, "ADMIN_PHONE");
        String adminPassword = envOpt(env, "ADMIN_PASSWORD");
        // ADMIN_PHONE / ADMIN_PASSWORD 必须成对出现，否则视为误配置直接失败
        if ((adminPhone == null) != (adminPassword == null)) {
            throw new IllegalStateException(
                    "ADMIN_PHONE 与 ADMIN_PASSWORD 必须同时提供（用于引导平台管理员账号，投诉审核依赖 admin）");
        }
        String adminName = adminPhone == null ? "平台管理员" : env.getProperty("ADMIN_NAME", "平台管理员").trim();

        return new AppProps(
                databaseUrl.trim(),
                intEnv(env, "DB_MAX_CONNECTIONS", 10),
                corsOrigins,
                adminPhone,
                adminPassword,
                adminName.isEmpty() ? "平台管理员" : adminName,
                longEnv(env, "MAX_REQUEST_BYTES", 1024 * 1024),
                intEnv(env, "APP_LOGIN_MAX_FAILURES", 5),
                longEnv(env, "APP_LOGIN_WINDOW_SECONDS", 900),
                longEnv(env, "APP_LOGIN_BLOCK_SECONDS", 900));
    }

    /** 读取可选环境变量：未设置或空白一律视为「未提供」。 */
    static String envOpt(Environment env, String key) {
        String raw = env.getProperty(key);
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static int intEnv(Environment env, String key, int fallback) {
        String raw = env.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("环境变量 " + key + " 的值无法解析: " + raw);
        }
    }

    private static long longEnv(Environment env, String key, long fallback) {
        String raw = env.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("环境变量 " + key + " 的值无法解析: " + raw);
        }
    }
}
