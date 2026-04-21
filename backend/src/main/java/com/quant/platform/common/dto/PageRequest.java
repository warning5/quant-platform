package com.quant.platform.common.dto;

import lombok.Data;

/**
 * 分页请求参数
 */
@Data
public class PageRequest {
    private int page = 0;
    private int size = 20;
    private String sortBy = "createdAt";
    private String sortDir = "desc";
}
