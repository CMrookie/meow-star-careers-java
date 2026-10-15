package com.meowstar.careers.model.job;

import java.util.List;

/**
 * 职位列表分页响应。
 *
 * <p>items 一律按用人单位投诉等级<b>从优到劣</b>排序（排序在服务端完成、翻页全局有序），
 * 客户端按返回顺序渲染即可，无需再自行排序。
 */
public record JobPage(List<JobView> items, long total, long page, long pageSize) {
}
