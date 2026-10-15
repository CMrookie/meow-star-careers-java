package com.meowstar.careers.model.auth;

import com.meowstar.careers.model.company.NewCompany;
import io.swagger.v3.oas.annotations.media.Schema;

/** 注册请求（手机号 + 密码）。 */
@Schema(description = "注册请求")
public record RegisterRequest(
        @Schema(example = "13800138000", minLength = 11, maxLength = 11) String phone,
        @Schema(example = "Bob") String name,
        @Schema(example = "secret123", minLength = 8) String password,
        @Schema(description = "seeker（默认）或 recruiter（admin/reviewer 不可自助注册）") Role role,
        @Schema(description = "角色为 recruiter 时必填：其所属企业") NewCompany company) {

    /** 未提供 role 时默认求职者（对齐 Rust 的 #[serde(default)]）。 */
    public Role roleOrDefault() {
        return role == null ? Role.SEEKER : role;
    }
}
