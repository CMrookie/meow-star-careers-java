package com.meowstar.careers.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/**
 * 安全原语：密码哈希（argon2id）、令牌生成与摘要、手机号校验。集中在此便于审计与更换算法。
 *
 * <p>哈希参数与 Rust 版 {@code argon2::Argon2::default()} 完全一致（argon2id v19 / m=19456 KiB / t=2 / p=1），
 * PHC 字符串双向兼容：Rust 写入的哈希可被本类校验，反之亦然。
 */
public final class Security {

    private static final int VERSION = 0x13; // 19
    private static final int MEMORY_KIB = 19 * 1024;
    private static final int ITERATIONS = 2;
    private static final int PARALLELISM = 1;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getEncoder().withoutPadding();
    private static final Base64.Decoder B64_DECODER = Base64.getDecoder();

    private Security() {
    }

    /** 使用 argon2id 对明文密码哈希（返回 PHC 字符串，存库）。 */
    public static String hashPassword(String password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] hash = argon2(password.getBytes(StandardCharsets.UTF_8), salt,
                MEMORY_KIB, ITERATIONS, PARALLELISM, VERSION, HASH_BYTES);
        return "$argon2id$v=" + VERSION + "$m=" + MEMORY_KIB + ",t=" + ITERATIONS + ",p=" + PARALLELISM
                + "$" + B64.encodeToString(salt)
                + "$" + B64.encodeToString(hash);
    }

    /** 校验明文密码是否匹配已存 PHC 哈希；格式非法一律返回 false（不抛异常）。 */
    public static boolean verifyPassword(String password, String passwordHash) {
        if (password == null || passwordHash == null) {
            return false;
        }
        try {
            String[] parts = passwordHash.split("\\$");
            // ["", "argon2id", "v=19", "m=...,t=...,p=...", salt, hash]
            if (parts.length != 6 || !"argon2id".equals(parts[1])) {
                return false;
            }
            int version = Integer.parseInt(parts[2].substring("v=".length()));
            int memory = MEMORY_KIB;
            int iterations = ITERATIONS;
            int parallelism = PARALLELISM;
            for (String item : parts[3].split(",")) {
                String[] kv = item.split("=");
                if (kv.length != 2) {
                    return false;
                }
                switch (kv[0]) {
                    case "m" -> memory = Integer.parseInt(kv[1]);
                    case "t" -> iterations = Integer.parseInt(kv[1]);
                    case "p" -> parallelism = Integer.parseInt(kv[1]);
                    default -> {
                        // 兼容未知参数（如 keyid/data）：忽略
                    }
                }
            }
            byte[] salt = B64_DECODER.decode(parts[4]);
            byte[] expected = B64_DECODER.decode(parts[5]);
            byte[] actual = argon2(password.getBytes(StandardCharsets.UTF_8), salt,
                    memory, iterations, parallelism, version, expected.length);
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static byte[] argon2(byte[] password, byte[] salt, int memoryKib, int iterations,
                                 int parallelism, int version, int outputBytes) {
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(version)
                .withMemoryAsKB(memoryKib)
                .withIterations(iterations)
                .withParallelism(parallelism)
                .withSalt(salt)
                .build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(params);
        byte[] out = new byte[outputBytes];
        generator.generateBytes(password, out);
        return out;
    }

    /**
     * 生成随机会话令牌（32 位十六进制，对齐 Rust 版 {@code Uuid::new_v4().simple()}）
     * 并返回其 sha256 摘要（摘要才入库）。
     */
    public static TokenPair newSessionToken() {
        String token = UUID.randomUUID().toString().replace("-", "");
        return new TokenPair(token, sha256Hex(token.getBytes(StandardCharsets.UTF_8)));
    }

    /** 令牌明文 + 摘要。 */
    public record TokenPair(String token, String digest) {
    }

    /** 中国大陆手机号：11 位、1 开头、全数字。 */
    public static boolean isValidCnPhone(String phone) {
        if (phone == null || phone.length() != 11 || phone.charAt(0) != '1') {
            return false;
        }
        for (int i = 0; i < phone.length(); i++) {
            char c = phone.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** sha256 十六进制摘要。 */
    public static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    /** sha384 摘要（迁移校验用，与 sqlx 的 checksum 算法一致）。 */
    public static byte[] sha384(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-384").digest(data);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-384 不可用", ex);
        }
    }
}
