package com.meowstar.careers.db;

import com.meowstar.careers.security.Security;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 迁移执行器：启动时按版本顺序执行 {@code ./migrations/*.sql}（与 Rust 版 sqlx::migrate! 行为一致）。
 *
 * <p>为与 Rust 版在同一数据库上互通，迁移记录写入 sqlx 的 {@code _sqlx_migrations} 表：
 * version 取文件名数字前缀、description 取其余部分、checksum 为文件内容的 sha384
 * （与 sqlx 的校验算法相同）。因此本实现与 Rust 版可交替启动而不会重复执行或校验失败。
 */
@Component
public class Migrator {

    private static final Logger log = LoggerFactory.getLogger(Migrator.class);
    private static final Pattern FILE_PATTERN = Pattern.compile("^(\\d+)_(.+)\\.sql$");

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS _sqlx_migrations (
                version BIGINT PRIMARY KEY,
                description TEXT NOT NULL,
                installed_on TIMESTAMPTZ NOT NULL DEFAULT now(),
                success BOOLEAN NOT NULL,
                checksum BYTEA NOT NULL,
                execution_time BIGINT NOT NULL
            )
            """;

    private final DataSource dataSource;

    public Migrator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void migrate() {
        Path dir = migrationsDir();
        List<MigrationFile> files = readMigrationFiles(dir);
        if (files.isEmpty()) {
            log.warn("未找到迁移文件（{}）", dir.toAbsolutePath());
            return;
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            try (Statement statement = connection.createStatement()) {
                statement.execute(CREATE_TABLE);
            }
            Map<Long, byte[]> applied = appliedMigrations(connection);

            int executed = 0;
            for (MigrationFile file : files) {
                byte[] checksum = Security.sha384(file.sql().getBytes(StandardCharsets.UTF_8));
                byte[] previous = applied.get(file.version());
                if (previous != null && java.util.Arrays.equals(previous, checksum)) {
                    continue; // 已应用且内容未变
                }
                if (previous != null) {
                    throw new IllegalStateException("迁移 " + file.name()
                            + " 的 checksum 与库中记录不一致（本地开发库请先重置数据库）");
                }
                long startedAt = System.nanoTime();
                apply(connection, file);
                long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
                record(connection, file, checksum, elapsedMillis);
                log.info("已应用迁移 {}（{} ms）", file.name(), elapsedMillis);
                executed++;
            }
            log.info("数据库迁移完成（共 {} 个文件，本次执行 {} 个）", files.size(), executed);
        } catch (SQLException ex) {
            throw new IllegalStateException("执行数据库迁移失败: " + ex.getMessage(), ex);
        }
    }

    private void apply(Connection connection, MigrationFile file) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("BEGIN");
            try {
                statement.execute(file.sql());
                statement.execute("COMMIT");
            } catch (SQLException ex) {
                try {
                    statement.execute("ROLLBACK");
                } catch (SQLException ignored) {
                    // 忽略回滚失败，抛出原始异常
                }
                throw new SQLException("迁移 " + file.name() + " 执行失败: " + ex.getMessage(), ex);
            }
        }
    }

    private void record(Connection connection, MigrationFile file, byte[] checksum, long elapsedMillis)
            throws SQLException {
        String sql = """
                INSERT INTO _sqlx_migrations (version, description, success, checksum, execution_time)
                VALUES (?, ?, TRUE, ?, ?)
                ON CONFLICT (version) DO UPDATE
                   SET description = EXCLUDED.description,
                       success = TRUE,
                       checksum = EXCLUDED.checksum,
                       execution_time = EXCLUDED.execution_time
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, file.version());
            statement.setString(2, file.description());
            statement.setBytes(3, checksum);
            statement.setLong(4, elapsedMillis);
            statement.executeUpdate();
        }
    }

    private Map<Long, byte[]> appliedMigrations(Connection connection) throws SQLException {
        Map<Long, byte[]> applied = new HashMap<>();
        String sql = "SELECT version, checksum FROM _sqlx_migrations WHERE success = TRUE";
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                applied.put(rs.getLong("version"), rs.getBytes("checksum"));
            }
        }
        return applied;
    }

    private Path migrationsDir() {
        String override = System.getenv("MIGRATIONS_DIR");
        if (override != null && !override.isBlank()) {
            return Path.of(override.trim());
        }
        String property = System.getProperty("MIGRATIONS_DIR");
        if (property != null && !property.isBlank()) {
            return Path.of(property.trim());
        }
        return Path.of("migrations");
    }

    private List<MigrationFile> readMigrationFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<MigrationFile> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile).sorted(Comparator.comparing(Path::getFileName)).forEach(path -> {
                Matcher matcher = FILE_PATTERN.matcher(path.getFileName().toString());
                if (!matcher.matches()) {
                    return;
                }
                try {
                    files.add(new MigrationFile(Long.parseLong(matcher.group(1)), matcher.group(2),
                            matcher.group(0), Files.readString(path, StandardCharsets.UTF_8)));
                } catch (IOException | NumberFormatException ex) {
                    throw new IllegalStateException("读取迁移文件失败: " + path, ex);
                }
            });
        } catch (IOException ex) {
            throw new IllegalStateException("扫描迁移目录失败: " + dir, ex);
        }
        files.sort(Comparator.comparingLong(MigrationFile::version));
        return files;
    }

    private record MigrationFile(long version, String description, String name, String sql) {
    }
}
