package com.meowstar.careers.model.resume;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

/** resumes 表的一行。 */
@Schema(description = "简历")
public record Resume(
        UUID id,
        UUID userId,
        String fullName,
        @Schema(example = "期望职位，如后端工程师") String title,
        String phone,
        String email,
        Integer years,
        String education,
        String skills,
        String summary,
        @JsonProperty("isPublic") boolean isPublic,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
