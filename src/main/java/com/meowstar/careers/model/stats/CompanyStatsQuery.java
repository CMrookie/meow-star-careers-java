package com.meowstar.careers.model.stats;

import io.swagger.v3.oas.annotations.media.Schema;

/** 公司统计的查询参数（{@code GET /api/v1/stats/companies}）。 */
public record CompanyStatsQuery(
        @Schema(description = "返回条数上限（默认 200，1-1000）") Long limit) {

    /** 对齐 Rust：{@code query.into_inner().limit.unwrap_or(200).clamp(1, 1000)}。 */
    public long limitClamped() {
        long value = limit == null ? 200L : limit;
        return Math.max(1L, Math.min(1000L, value));
    }
}
