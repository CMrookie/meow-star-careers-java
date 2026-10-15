package com.meowstar.careers.model.job;

import io.swagger.v3.oas.annotations.media.Schema;

/** 创建职位。 */
public record NewJob(
        @Schema(example = "后端工程师") String title,
        @Schema(example = "负责 xxx 系统的设计与开发") String description,
        String requirements,
        @Schema(example = "北京") String location,
        Integer salaryMin,
        Integer salaryMax,
        @Schema(example = "full_time") String jobType,
        @Schema(example = "3-5年") String experience,
        @Schema(example = "本科") String education) {
}
