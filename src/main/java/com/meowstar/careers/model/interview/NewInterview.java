package com.meowstar.careers.model.interview;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 发起面试请求体。 */
public record NewInterview(
        @Schema(description = "对哪个投递发起（招聘者名下企业的投递）") UUID applicationId,
        @Schema(description = "预约时间（可选；null = 即时面试）") OffsetDateTime scheduledAt) {
}
