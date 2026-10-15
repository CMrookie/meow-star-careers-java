package com.meowstar.careers.model.resume;

/** 新建/更新简历（更新时仅覆盖提供的字段）。 */
public record ResumeWrite(
        String fullName,
        String title,
        String phone,
        String email,
        Integer years,
        String education,
        String skills,
        String summary,
        Boolean isPublic) {
}
