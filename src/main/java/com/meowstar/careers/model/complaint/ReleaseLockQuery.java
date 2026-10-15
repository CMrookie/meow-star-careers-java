package com.meowstar.careers.model.complaint;

import io.swagger.v3.oas.annotations.media.Schema;

/** 释放认领锁的查询参数。 */
public record ReleaseLockQuery(
        @Schema(description = "强制释放：仅平台管理员可用，用于解锁他人忘记释放的投诉") Boolean force) {

    public boolean forceOrDefault() {
        return force != null && force;
    }
}
