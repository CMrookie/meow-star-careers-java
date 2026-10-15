package com.meowstar.careers.model.resume;

import java.util.List;

/** 简历分页响应。 */
public record ResumePage(List<Resume> items, long total, long page, long pageSize) {
}
