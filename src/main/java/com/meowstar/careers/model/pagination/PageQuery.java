package com.meowstar.careers.model.pagination;

/** 分页查询参数（page 从 1 开始；pageSize 默认 20、上限 100）。 */
public record PageQuery(Integer page, Integer pageSize) {
}
