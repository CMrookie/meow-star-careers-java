package com.meowstar.careers.model.stats;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 单个审核账号的工作量与及时性（不含任何结论指标）。 */
@Schema(description = "审核账号工作量（只衡量及时性，不统计结论分布）")
public record ReviewerWorkload(
        UUID reviewerId,
        String reviewerName,
        String phone,
        @JsonProperty("isActive") boolean isActive,
        @Schema(description = "当前持有的有效锁（正在处理中）") long activeLocks,
        @Schema(description = "已审结总数：仅作分母与工作量参考，不代表结论好坏") long reviewedTotal,
        long reviewedLast7d,
        @Schema(description = "提交 -> 审结 平均耗时（秒）") Double avgTotalSeconds,
        Double medianTotalSeconds,
        Double p90TotalSeconds,
        @Schema(description = "接单 -> 审结 平均耗时（秒）；只有留下认领时间的记录参与") Double avgHandleSeconds,
        @Schema(description = "参与处理时长统计的样本数（认领时间缺失的历史数据不计入）") long handleSample,
        @Schema(description = "总耗时在及时线内的条数") long onTimeTotal,
        @Schema(description = "及时率 = onTimeTotal / reviewedTotal") Double onTimeRate,
        OffsetDateTime lastReviewedAt) {
}
