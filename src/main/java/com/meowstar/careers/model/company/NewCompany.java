package com.meowstar.careers.model.company;

import io.swagger.v3.oas.annotations.media.Schema;

/** 新建企业（招聘者注册时必填）。 */
public record NewCompany(
        @Schema(example = "字节跳动") String name,
        @Schema(example = "互联网") String industry,
        String description,
        @Schema(example = "北京") String location,
        String address,
        String website,
        String logoUrl,
        @Schema(description = "员工人数（企业申报，可选）。填了才能按每百人投诉率定级（>=50 人时生效）",
                example = "2000") Integer staffSize) {
}
