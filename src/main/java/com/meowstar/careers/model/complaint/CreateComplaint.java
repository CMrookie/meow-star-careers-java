package com.meowstar.careers.model.complaint;

import io.swagger.v3.oas.annotations.media.Schema;

/** 发起投诉载荷。 */
public record CreateComplaint(
        @Schema(description = "有效证据说明（要求非空且 >=20 字符）") String evidence) {
}
