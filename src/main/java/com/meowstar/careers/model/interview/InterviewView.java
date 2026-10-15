package com.meowstar.careers.model.interview;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 面试视图（含职位/企业与双方姓名，列表与详情共用）。 */
@Schema(description = "线上面试")
public record InterviewView(
        UUID id,
        UUID applicationId,
        UUID jobId,
        String jobTitle,
        UUID companyId,
        String companyName,
        UUID interviewerId,
        String interviewerName,
        UUID intervieweeId,
        String intervieweeName,
        String status,
        @Schema(description = "null = 即时面试；非空 = 预约时间") OffsetDateTime scheduledAt,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
