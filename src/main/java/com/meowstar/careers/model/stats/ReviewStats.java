package com.meowstar.careers.model.stats;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 审核统计响应：队列概览 + 审核账号工作量。 */
@Schema(description = "审核统计（及时性）")
public record ReviewStats(
        ReviewQueue queue,
        @Schema(description = "平台管理员：全部审核账号；审核账号：只有自己") List<ReviewerWorkload> reviewers,
        @Schema(description = "及时线（秒），与后端判定一致，供前端展示") long slaSeconds) {
}
