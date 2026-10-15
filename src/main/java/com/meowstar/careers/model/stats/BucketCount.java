package com.meowstar.careers.model.stats;

import io.swagger.v3.oas.annotations.media.Schema;

/** 分档计数（投递数分档人数）。 */
public record BucketCount(
        @Schema(description = "0 / 1-2 / 3-5 / 6+") String bucket,
        long count) {
}
