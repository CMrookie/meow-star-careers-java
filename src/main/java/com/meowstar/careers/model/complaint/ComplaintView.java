package com.meowstar.careers.model.complaint;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 投诉视图（企业、投诉人、审核人与认领锁）。
 *
 * <p>对应数据库只读视图 complaint_views（见 0016_complaint_review_lock.sql），lock_active 由数据库按 now() 计算，
 * 因此不会出现前端拿到过期锁状态的情况。
 */
@Schema(description = "投诉记录")
public record ComplaintView(
        UUID id,
        UUID companyId,
        String companyName,
        UUID complainantId,
        String complainantName,
        String evidence,
        String status,
        String reviewNote,
        UUID reviewedBy,
        String reviewedByName,
        @Schema(description = "当前认领该投诉的审核账号（null = 空闲）") UUID lockedBy,
        String lockedByName,
        @Schema(description = "本次获得锁的时间（续约不改写）") OffsetDateTime lockedAt,
        @Schema(description = "锁到期时间；到期即视为空闲，可被他人重新认领") OffsetDateTime lockExpiresAt,
        @Schema(description = "锁当前是否仍有效（数据库计算）") boolean lockActive,
        @Schema(description = "首次认领（接单）时间：审核处理时长的起点，审结后仍保留") OffsetDateTime reviewStartedAt,
        OffsetDateTime createdAt,
        OffsetDateTime reviewedAt,
        OffsetDateTime updatedAt) {
}
