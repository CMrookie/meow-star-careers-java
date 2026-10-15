package com.meowstar.careers.model.stats;

import io.swagger.v3.oas.annotations.media.Schema;

/** 求职用户总量与参与度概览。 */
@Schema(description = "求职用户概览")
public record SeekerOverview(
        long total,
        long active,
        long disabled,
        long newLast7d,
        long newLast30d,
        @Schema(description = "建过简历的求职者数与其简历总数") long withResume,
        long resumesTotal,
        @Schema(description = "投过简历的求职者数与其投递总数") long withApplication,
        long applicationsTotal,
        long interviewsTotal,
        @Schema(description = "发起过投诉的求职者数与其投诉总数") long complainants,
        long complaintsTotal) {
}
