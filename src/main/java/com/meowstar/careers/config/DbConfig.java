package com.meowstar.careers.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 数据源装配：把 Rust 版使用的 {@code postgres://user:pass@host:port/db} 连接串
 * 转成 JDBC URL 并构建 HikariCP 连接池（最大连接数 DB_MAX_CONNECTIONS，获取超时 5s）。
 */
@Configuration
public class DbConfig {

    private static final Logger log = LoggerFactory.getLogger(DbConfig.class);

    @Bean(destroyMethod = "close")
    public DataSource dataSource(AppProps props) {
        JdbcTarget target = parse(props.databaseUrl());

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(target.jdbcUrl());
        if (target.username() != null) {
            config.setUsername(target.username());
        }
        if (target.password() != null) {
            config.setPassword(target.password());
        }
        config.setMaximumPoolSize(props.dbMaxConnections());
        config.setConnectionTimeout(5_000);
        config.setInitializationFailTimeout(5_000);
        config.setPoolName("meow-star-pool");
        try {
            HikariDataSource dataSource = new HikariDataSource(config);
            log.info("数据库连接池就绪（{}）", props.sanitizedDatabaseUrl());
            return dataSource;
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                    "连接 PostgreSQL 失败（" + props.sanitizedDatabaseUrl() + "）: " + ex.getMessage(), ex);
        }
    }

    private record JdbcTarget(String jdbcUrl, String username, String password) {
    }

    /** postgres:// / postgresql:// -> jdbc:postgresql://，并拆出用户名密码。 */
    static JdbcTarget parse(String databaseUrl) {
        String url = databaseUrl.trim();
        String withoutScheme;
        if (url.startsWith("jdbc:postgresql://")) {
            return new JdbcTarget(url, null, null);
        } else if (url.startsWith("postgres://")) {
            withoutScheme = url.substring("postgres://".length());
        } else if (url.startsWith("postgresql://")) {
            withoutScheme = url.substring("postgresql://".length());
        } else {
            throw new IllegalStateException("DATABASE_URL 必须以 postgres:// 或 postgresql:// 开头");
        }

        String credentials = null;
        String hostPart = withoutScheme;
        int at = withoutScheme.lastIndexOf('@');
        if (at >= 0) {
            credentials = withoutScheme.substring(0, at);
            hostPart = withoutScheme.substring(at + 1);
        }
        String username = null;
        String password = null;
        if (credentials != null && !credentials.isEmpty()) {
            int colon = credentials.indexOf(':');
            if (colon >= 0) {
                username = decode(credentials.substring(0, colon));
                password = decode(credentials.substring(colon + 1));
            } else {
                username = decode(credentials);
            }
        }
        try {
            URI ignored = URI.create("http://" + hostPart);
            if (ignored.getHost() == null) {
                throw new IllegalArgumentException("缺少主机名");
            }
        } catch (RuntimeException ex) {
            throw new IllegalStateException("DATABASE_URL 非法：" + ex.getMessage(), ex);
        }
        return new JdbcTarget("jdbc:postgresql://" + hostPart, username, password);
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
