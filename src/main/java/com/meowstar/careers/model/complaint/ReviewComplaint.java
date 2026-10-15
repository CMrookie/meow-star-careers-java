package com.meowstar.careers.model.complaint;

import io.swagger.v3.oas.annotations.media.Schema;

/** 审核载荷。 */
public record ReviewComplaint(
        boolean approved,
        @Schema(description = "审核备注（驳回建议填写原因）") String note) {
}
