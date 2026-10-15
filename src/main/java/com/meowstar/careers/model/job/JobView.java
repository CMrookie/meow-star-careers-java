package com.meowstar.careers.model.job;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

/** jobs 表 + 企业名的查询行（列表/详情共用），含服务端算好的投诉等级。 */
@Schema(description = "职位视图")
public record JobView(
        UUID id,
        UUID companyId,
        String companyName,
        String title,
        String description,
        String requirements,
        String location,
        Integer salaryMin,
        Integer salaryMax,
        @Schema(description = "full_time / part_time / contract / intern") String jobType,
        String experience,
        String education,
        @JsonProperty("isActive") boolean isActive,
        UUID createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        @Schema(description = "企业被投诉次数（决定职位展示颜色）") Integer complaintsCount,
        @Schema(description = "用人单位规模（员工人数，企业申报；null = 未申报）") Integer companyStaffSize,
        @Schema(description = "投诉等级（服务端算好）：excellent/minor/alert/warning/severe") String complaintLevel,
        @Schema(description = "定级口径：rate（按每百人投诉率）/ count（按次数兜底）") String complaintBasis,
        @Schema(description = "每百人投诉率（%）；按次数口径时为 null") Double complaintRatePercent) {
}
