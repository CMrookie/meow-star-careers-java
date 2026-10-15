package com.meowstar.careers.model.stats;

import io.swagger.v3.oas.annotations.media.Schema;

/** 待审队列概览。 */
@Schema(description = "待审队列概览")
public record ReviewQueue(
        long pendingTotal,
        @Schema(description = "正被有效锁持有（有人正在审）") long lockedActive,
        @Schema(description = "锁已过期但仍留着锁字段（可被任何人重新认领）") long lockedExpired,
        @Schema(description = "当前调用者自己持有的有效锁") long lockedByMe,
        @Schema(description = "已超过及时线仍未审结的待审投诉") long overdueTotal,
        @Schema(description = "最老的待审投诉已积压多少秒") Double oldestPendingSeconds) {
}
