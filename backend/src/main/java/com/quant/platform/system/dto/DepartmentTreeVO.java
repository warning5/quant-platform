package com.quant.platform.system.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 部门树节点（前端 Tree 使用）
 */
@Data
public class DepartmentTreeVO {
    private Long id;
    private Long parentId;
    private String deptName;
    private String deptPath;
    private Integer deptLevel;
    private Integer sort;
    private Integer status;
    private List<DepartmentTreeVO> children = new ArrayList<>();

    public DepartmentTreeVO() {
    }

    public DepartmentTreeVO(Long id, Long parentId, String deptName, String deptPath,
                            Integer deptLevel, Integer sort, Integer status) {
        this.id = id;
        this.parentId = parentId;
        this.deptName = deptName;
        this.deptPath = deptPath;
        this.deptLevel = deptLevel;
        this.sort = sort;
        this.status = status;
    }
}
