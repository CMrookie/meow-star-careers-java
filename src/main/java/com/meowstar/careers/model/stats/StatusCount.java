package com.meowstar.careers.model.stats;

/** 按状态计数（投递状态分布）。 */
public record StatusCount(String status, long count) {
}
