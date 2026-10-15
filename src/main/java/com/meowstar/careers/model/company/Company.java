package com.meowstar.careers.model.company;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

/** companies 表的一行。 */
@Schema(description = "企业信息")
public record Company(
        UUID id,
        String name,
        @Schema(description = "所属行业（自由文本）") String industry,
        @Schema(description = "企业介绍") String description,
        @Schema(description = "所在城市/地区") String location,
        @Schema(description = "详细地址（街道级，可选；用于地图定位）") String address,
        String website,
        String logoUrl,
        @JsonProperty("isActive") boolean isActive,
        UUID createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        @Schema(description = "被投诉次数：只统计管理员审核通过的投诉（0 = 优秀档）") Integer complaintsCount,
        @Schema(description = "企业规模（员工人数，企业申报；null = 未申报），投诉定级 v2 的分母") Integer staffSize) {
}
