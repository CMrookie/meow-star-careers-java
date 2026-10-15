package com.meowstar.careers.limiter;

import com.meowstar.careers.config.AppProps;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 简易进程内登录防爆破限流器（对齐 Rust 版 {@code limiter::LoginLimiter}）：
 * 以「手机号|客户端IP」为键，窗口期内连续失败 N 次进入冻结窗口。
 * 进程内实现（单实例足够；多实例部署请替换为 Redis 等共享存储）。
 */
@Component
public class LoginLimiter {

    private final int maxFailures;
    private final Duration window;
    private final Duration block;
    private final Map<String, Entry> entries = new HashMap<>();

    public LoginLimiter(AppProps props) {
        this.maxFailures = props.loginMaxFailures();
        this.window = Duration.ofSeconds(props.loginWindowSeconds());
        this.block = Duration.ofSeconds(props.loginBlockSeconds());
    }

    private static final class Entry {
        int failures;
        Instant firstFailure;
        Instant blockedUntil;
    }

    /** 该键当前是否被冻结；顺带清理过期条目。 */
    public synchronized boolean blocked(String key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return false;
        }
        Instant now = Instant.now();
        if (entry.blockedUntil != null) {
            if (now.isBefore(entry.blockedUntil)) {
                return true;
            }
            entries.remove(key);
            return false;
        }
        if (entry.firstFailure != null && now.isAfter(entry.firstFailure.plus(window))) {
            entries.remove(key);
            return false;
        }
        return false;
    }

    /** 记录一次失败；达到上限即冻结。 */
    public synchronized void recordFailure(String key) {
        Instant now = Instant.now();
        Entry entry = entries.computeIfAbsent(key, ignored -> new Entry());
        // 滑动窗口：距首次失败超过窗口则重新计数
        if (entry.firstFailure != null && now.isAfter(entry.firstFailure.plus(window))) {
            entry.failures = 0;
            entry.firstFailure = null;
            entry.blockedUntil = null;
        }
        entry.failures++;
        if (entry.firstFailure == null) {
            entry.firstFailure = now;
        }
        if (entry.failures >= maxFailures) {
            entry.blockedUntil = now.plus(block);
        }
    }

    /** 登录成功后清空该键计数。 */
    public synchronized void clear(String key) {
        entries.remove(key);
    }
}
