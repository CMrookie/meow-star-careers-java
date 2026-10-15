package com.meowstar.careers.model.application;

import java.util.List;

/** 投递分页响应。 */
public record ApplicationPage(List<ApplicationView> items, long total, long page, long pageSize) {
}
