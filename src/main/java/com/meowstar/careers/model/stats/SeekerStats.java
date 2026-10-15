package com.meowstar.careers.model.stats;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 求职用户分析。 */
@Schema(description = "求职用户分析")
public record SeekerStats(
        SeekerOverview overview,
        @Schema(description = "近 12 个月注册趋势") List<MonthlyCount> registrationsByMonth,
        List<StatusCount> applicationsByStatus,
        List<BucketCount> applicationBuckets,
        @Schema(description = "参与度：有简历或有过投递的求职者占比") Double engagementRate,
        @Schema(description = "人均投诉数（仅统计发起过投诉的求职者）") Double complaintsPerComplainant) {
}
