package com.meowstar.careers.model.application;

import java.util.UUID;

/** 投递查询参数。 */
public record ApplicationQuery(UUID jobId, String status, Integer page, Integer pageSize) {
}
