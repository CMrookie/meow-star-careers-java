package com.meowstar.careers.model.application;

import io.swagger.v3.oas.annotations.media.Schema;

/** 更新投递状态请求体（招聘者推进；求职者撤回）。 */
public record UpdateApplicationStatus(
        @Schema(description = "viewed / interviewing / offered / rejected（招聘者）；withdrawn（求职者撤回）",
                example = "interviewing") String status) {
}
