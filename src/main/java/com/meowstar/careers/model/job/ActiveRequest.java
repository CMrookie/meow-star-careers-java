package com.meowstar.careers.model.job;

import io.swagger.v3.oas.annotations.media.Schema;

/** 上架/下架请求体（{@code POST /api/v1/jobs/{id}/active}）。 */
public record ActiveRequest(
        @Schema(example = "false") boolean isActive) {
}
