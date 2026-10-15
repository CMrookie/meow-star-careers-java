package com.meowstar.careers.model.user;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

/** users 表中的一行（也是 GET 响应的实体）。 */
@Schema(description = "用户账号")
public record User(
        UUID id,
        @Schema(description = "联系邮箱（可空；手机号注册的新账号为空）") String email,
        String name,
        @Schema(description = "是否启用（禁用后不可登录）") boolean isActive,
        @Schema(description = "seeker=求职者 / recruiter=招聘者 / admin=平台管理 / reviewer=审核专用账号") String role,
        @Schema(description = "招聘者所属企业（求职者为 null）") UUID companyId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        @Schema(description = "注册手机号（登录账号，唯一；历史邮箱账号为空）") String phone) {
}
