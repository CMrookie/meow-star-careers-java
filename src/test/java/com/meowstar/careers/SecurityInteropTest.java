package com.meowstar.careers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.meowstar.careers.limiter.LoginLimiter;
import com.meowstar.careers.model.pagination.Paginate;
import com.meowstar.careers.security.Security;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 安全原语与跨实现兼容性回归测试（不需要数据库）。
 *
 * <p>其中 RUST_HASH 来自 Rust 版（argon2 crate，2026-09-09 由 just-a-work 写入 appdb 的真实哈希），
 * 用于证明「Rust 写入的密码哈希可被 Java 校验」——两版共用同一个数据库，必须双向兼容。
 */
class SecurityInteropTest {

    /** Rust argon2 crate 生成的 PHC 字符串（明文密码 secret123）。 */
    private static final String RUST_HASH =
            "$argon2id$v=19$m=19456,t=2,p=1$hg0NHIPkySQ6EtyrJxYFVQ$s5BOaTI57U13+cKISEsmk7ejoWMGRzPjTeR5Sb9rYNQ";

    @Test
    void rustGeneratedHashVerifiesInJava() {
        assertTrue(Security.verifyPassword("secret123", RUST_HASH), "Rust 哈希应能被 Java 校验通过");
        assertFalse(Security.verifyPassword("wrongpass123", RUST_HASH), "错误密码必须被拒绝");
    }

    @Test
    void javaHashUsesSameArgon2idParametersAndRoundTrips() {
        String hash = Security.hashPassword("secret123");
        assertTrue(hash.startsWith("$argon2id$v=19$m=19456,t=2,p=1$"), "参数须与 Rust Argon2::default() 一致: " + hash);
        assertTrue(Security.verifyPassword("secret123", hash));
        assertFalse(Security.verifyPassword("secret124", hash));
        assertFalse(Security.verifyPassword("secret123", "not-a-phc-string"));
        assertFalse(Security.verifyPassword("secret123", null));
    }

    @Test
    void tokenIs32HexCharsAndDigestIsSha256() {
        Security.TokenPair pair = Security.newSessionToken();
        assertEquals(32, pair.token().length(), "令牌为 32 位十六进制（对齐 Uuid::new_v4().simple()）");
        assertTrue(pair.token().matches("[0-9a-f]{32}"));
        assertEquals(Security.sha256Hex(pair.token().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                pair.digest());
        assertEquals(64, pair.digest().length());
    }

    @Test
    void migrationChecksumIsSha384LikeSqlx() {
        assertEquals(48, Security.sha384("-- 0001\nSELECT 1;".getBytes(java.nio.charset.StandardCharsets.UTF_8)).length,
                "sqlx 用 sha384 作为迁移 checksum");
    }

    @Test
    void cnPhoneValidation() {
        assertTrue(Security.isValidCnPhone("13800138000"));
        assertFalse(Security.isValidCnPhone("1380013800"));
        assertFalse(Security.isValidCnPhone("23800138000"));
        assertFalse(Security.isValidCnPhone("1380013800a"));
        assertFalse(Security.isValidCnPhone(null));
    }

    @Test
    void paginationNormalisation() {
        Paginate.Page page = Paginate.paginate(null, null);
        assertEquals(1, page.page());
        assertEquals(20, page.pageSize());
        assertEquals(0, page.offset());
        Paginate.Page clamped = Paginate.paginate(0, 500);
        assertEquals(1, clamped.page());
        assertEquals(100, clamped.pageSize());
        assertEquals(0, clamped.offset());
        Paginate.Page third = Paginate.paginate(3, 10);
        assertEquals(20, third.offset());
    }

    @Test
    void loginLimiterBlocksAfterConfiguredFailures() {
        LoginLimiter limiter = new LoginLimiter(new com.meowstar.careers.config.AppProps(
                "postgres://u:p@127.0.0.1:5432/db", 10, java.util.List.of(), null, null, "平台管理员",
                1024 * 1024, 5, 900, 900));
        String key = "13800138000|127.0.0.1";
        assertFalse(limiter.blocked(key));
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure(key);
        }
        assertFalse(limiter.blocked(key), "第 4 次失败还不应冻结");
        limiter.recordFailure(key);
        assertTrue(limiter.blocked(key), "第 5 次失败应触发冻结（5 次/15 分钟）");
        limiter.clear(key);
        assertFalse(limiter.blocked(key), "登录成功后应清零");
        ReflectionTestUtils.invokeMethod(limiter, "blocked", key);
    }
}
