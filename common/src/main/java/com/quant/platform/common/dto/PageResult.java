package com.quant.platform.common.dto;

import lombok.Data;

import java.util.List;

/**
 * 统一分页响应封装。
 * 规范字段：list / total / page / size / totalPages。
 * 各分页接口统一返回本类型，避免前端对不同接口的返回结构做分支处理。
 */
@Data
public class PageResult<T> {

    /** 当前页数据 */
    private List<T> list;
    /** 总记录数 */
    private long total;
    /** 当前页码（从 1 开始） */
    private int page;
    /** 每页大小 */
    private int size;
    /** 总页数 */
    private int totalPages;

    public PageResult() {
    }

    public PageResult(List<T> list, long total, int page, int size) {
        this.list = list;
        this.total = total;
        this.page = page;
        this.size = size;
        this.totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 0;
    }

    public static <T> PageResult<T> of(List<T> list, long total, int page, int size) {
        return new PageResult<>(list, total, page, size);
    }
}
