package com.meowstar.careers.config;

import java.util.List;

/**
 * 应用配置（全部来自环境变量，与 Rust 版 {@code config::Config} 同名同义）。
 *
 * @param databaseUrl      PostgreSQL 连接串（postgres://user:pass@host:port/db）
 * @param dbMaxConnections 连接池最大连接数
 * @param corsOrigins      CORS 允许来源（空 = 默认拒绝跨域，仅同源可用）
 * @param adminPhone       平台管理员引导手机号（与 adminPassword 同时提供才生效）
 * @param adminPassword    平台管理员引导密码（8-128）
 * @param adminName        平台管理员显示名（默认「平台管理员」）
 * @param maxRequestBytes  请求体上限（默认 1 MiB）
 * @param loginMaxFailures 登录失败次数上限（默认 5）
 * @param loginWindowSeconds 失败计数窗口秒数（默认 900）
 * @param loginBlockSeconds  冻结时长秒数（默认 900）
 */
public record AppProps(
        String databaseUrl,
        int dbMaxConnections,
        List<String> corsOrigins,
        String adminPhone,
        String adminPassword,
        String adminName,
        long maxRequestBytes,
        int loginMaxFailures,
        long loginWindowSeconds,
        long loginBlockSeconds) {

    /** 打日志时不泄露连接串中的密码（postgres://app:***@127.0.0.1:5432/appdb）。 */
    public String sanitizedDatabaseUrl() {
        int at = databaseUrl.lastIndexOf('@');
        if (at < 0) {
            return databaseUrl;
        }
        int schemeEnd = databaseUrl.indexOf("://");
        schemeEnd = schemeEnd < 0 ? 0 : schemeEnd + 3;
        if (schemeEnd < at) {
            String cred = databaseUrl.substring(schemeEnd, at);
            int colon = cred.indexOf(':');
            if (colon >= 0) {
                return databaseUrl.substring(0, schemeEnd) + cred.substring(0, colon + 1) + "***@"
                        + databaseUrl.substring(at + 1);
            }
        }
        return databaseUrl;
    }
}
