package com.meowstar.careers.model.stats;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 用人单位质量画像。 */
@Schema(description = "用人单位质量画像")
public record CompanyQuality(
        UUID companyId,
        String name,
        String industry,
        String location,
        long complaintsTotal,
        long complaintsPending,
        @Schema(description = "已核实（审核通过）投诉——平台的权威口径") long complaintsApproved,
        long complaintsRejected,
        long complaintsLast30d,
        long jobsTotal,
        long jobsActive,
        @Schema(description = "与 complaint_level_rank 一致的等级序：0 优秀 … 4 严重") Integer levelRank,
        @Schema(description = "每个在招职位摊到的已核实投诉数（规模归一化后的风险）") Double complaintRate,
        @Schema(description = "综合质量分（0-100，越高越好）：100 - 已核实投诉*10 - 待核实投诉*2，下限 0")
        Integer qualityScore,
        OffsetDateTime firstComplaintAt,
        OffsetDateTime lastComplaintAt) {
}
