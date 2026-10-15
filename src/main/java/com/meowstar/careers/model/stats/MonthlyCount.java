package com.meowstar.careers.model.stats;

import io.swagger.v3.oas.annotations.media.Schema;

/** 按月计数（注册趋势）。 */
public record MonthlyCount(
        @Schema(description = "YYYY-MM") String month,
        long count) {
}
