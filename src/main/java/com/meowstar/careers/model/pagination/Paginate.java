package com.meowstar.careers.model.pagination;

/** 通用分页工具：归一化分页参数（与 Rust 版 {@code models::pagination::paginate} 一致）。 */
public final class Paginate {

    private Paginate() {
    }

    /**
     * 归一化分页：page>=1，1<=pageSize<=100，默认 20。
     *
     * @return 归一化后的 (page, pageSize, offset)
     */
    public static Page paginate(Integer page, Integer pageSize) {
        long normalizedPage = page == null ? 1 : Math.max(page, 1);
        long normalizedSize = pageSize == null ? 20 : Math.clamp(pageSize, 1, 100);
        return new Page(normalizedPage, normalizedSize, (normalizedPage - 1) * normalizedSize);
    }

    public record Page(long page, long pageSize, long offset) {
    }
}
