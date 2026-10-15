package com.meowstar.careers.model.application;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

/** applications 联表查询行（含职位/企业/求职者信息）。 */
@Schema(description = "投递记录")
public record ApplicationView(
        UUID id,
        UUID jobId,
        String jobTitle,
        UUID companyId,
        String companyName,
        UUID seekerId,
        String seekerName,
        @Schema(description = "求职者联系邮箱（可空；手机号注册用户无邮箱）") String seekerEmail,
        @Schema(description = "求职者手机号（可空；历史邮箱账号无手机号）") String seekerPhone,
        UUID resumeId,
        String coverLetter,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
