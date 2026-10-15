package com.meowstar.careers.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 极简 .env 载入器（对齐 Rust 版 dotenvy::dotenv() 的语义）：
 * 在当前工作目录查找 .env；已存在的真实环境变量 / 系统属性优先；支持 KEY=VALUE、export、# 注释与引号；失败静默。
 */
public final class DotenvLoader {

    private static final Logger log = LoggerFactory.getLogger(DotenvLoader.class);

    private DotenvLoader() {
    }

    /** 尝试载入 ./.env；不存在或解析失败时静默返回。 */
    public static void loadIfPresent() {
        Path envFile = Path.of(".env");
        if (!Files.isRegularFile(envFile)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(envFile, StandardCharsets.UTF_8);
            int loaded = 0;
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("export ")) {
                    line = line.substring("export ".length()).trim();
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                String value = unquote(line.substring(eq + 1).trim());
                if (key.isEmpty()) {
                    continue;
                }
                if (System.getenv(key) != null || System.getProperty(key) != null) {
                    continue; // 真实环境变量优先
                }
                System.setProperty(key, value);
                loaded++;
            }
            log.info("已载入 .env（{} 项；真实环境变量优先）", loaded);
        } catch (IOException | RuntimeException ex) {
            log.debug("读取 .env 失败（忽略）：{}", ex.toString());
        }
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
