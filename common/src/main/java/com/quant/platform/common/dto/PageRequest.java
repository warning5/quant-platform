package com.quant.platform.common.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 分页请求参数
 */
@Data
public class PageRequest {
    @Min(0)
    private int page = 0;
    @Min(1)
    @Max(1000)
    private int size = 20;
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "sortBy 只能包含字母、数字、下划线")
    private String sortBy = "createdAt";
    @Pattern(regexp = "^(asc|desc)$", message = "sortDir 只能是 asc 或 desc")
    private String sortDir = "desc";
}
