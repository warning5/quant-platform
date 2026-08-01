package com.quant.platform.dataperm.service;

import lombok.Data;

import java.util.List;

/**
 * 数据权限配置页 - 部门树节点（DEPT 用 TreeSelect）。
 */
@Data
public class DeptTreeNodeVO {
    private Long id;
    private String label;
    private List<DeptTreeNodeVO> children;

    public DeptTreeNodeVO(Long id, String label) {
        this.id = id;
        this.label = label;
        this.children = new java.util.ArrayList<>();
    }
}