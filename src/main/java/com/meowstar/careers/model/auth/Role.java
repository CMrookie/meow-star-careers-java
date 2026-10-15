package com.meowstar.careers.model.auth;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 用户角色。
 *
 * <p>Admin / Reviewer 仅用于表达数据库/文档里的角色取值：注册接口<b>拒绝</b>自助注册这两种角色 ——
 * admin 由服务端按 ADMIN_PHONE / ADMIN_PASSWORD 引导创建，reviewer 由平台管理员创建。
 */
public enum Role {
    SEEKER("seeker"),
    RECRUITER("recruiter"),
    ADMIN("admin"),
    REVIEWER("reviewer");

    private final String value;

    Role(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static Role fromValue(String raw) {
        for (Role role : values()) {
            if (role.value.equals(raw)) {
                return role;
            }
        }
        throw new IllegalArgumentException("未知角色: " + raw);
    }
}
