package com.meowstar.careers.model.job;

/** 更新职位（仅更新提供的字段）。 */
public record UpdateJob(
        String title,
        String description,
        String requirements,
        String location,
        Integer salaryMin,
        Integer salaryMax,
        String jobType,
        String experience,
        String education) {
}
