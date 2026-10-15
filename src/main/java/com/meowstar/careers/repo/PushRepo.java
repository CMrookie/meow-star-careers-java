package com.meowstar.careers.repo;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 设备推送令牌注册（预留：FCM/APNs 接入后读取投递），
 * 对齐 Rust 版 repositories::push。
 */
@Repository
public class PushRepo {

    private final JdbcTemplate jdbc;

    public PushRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 注册/更新推送令牌（同平台同令牌幂等 upsert）。 */
    public void register(UUID userId, String platform, String token) {
        jdbc.update(
                "INSERT INTO device_push_tokens (user_id, platform, token) VALUES (?, ?, ?) "
                        + "ON CONFLICT (user_id, platform, token) DO UPDATE SET updated_at = now()",
                userId, platform, token);
    }

    /** 注销某平台的推送令牌。 */
    public void remove(UUID userId, String platform, String token) {
        jdbc.update("DELETE FROM device_push_tokens WHERE user_id = ? AND platform = ? AND token = ?",
                userId, platform, token);
    }
}
