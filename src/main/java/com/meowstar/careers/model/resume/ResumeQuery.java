package com.meowstar.careers.model.resume;

/** 简历查询参数（keyword 匹配姓名/期望职位/技能/自我评价，仅招聘者可见）。 */
public record ResumeQuery(String keyword, Integer page, Integer pageSize) {
}
