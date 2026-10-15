package com.meowstar.careers.model.job;

/** 职位搜索/列表查询参数。 */
public record JobQuery(
        String keyword,
        String location,
        String jobType,
        Integer salaryMin,
        Integer salaryMax,
        Integer page,
        Integer pageSize) {
}
