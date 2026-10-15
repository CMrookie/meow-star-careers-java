package com.meowstar.careers.db;

import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.RowMapper;

/**
 * 行映射工具：{@link DataClassRowMapper} 按「下划线列名 -> camelCase 记录组件名」自动映射，
 * 因此记录组件名必须与 SQL 结果列一一对应（别名用 snake_case）。
 * 需要计算/分支逻辑时改用手写 lambda {@code rs -> new X(...)}。
 */
public final class Rows {

    private Rows() {
    }

    public static <T> RowMapper<T> of(Class<T> type) {
        return new DataClassRowMapper<>(type);
    }
}
