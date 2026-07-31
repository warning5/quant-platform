package com.quant.platform.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 部门（支持多级：parent_id 自引用，dept_path 祖先链用于层级可见性匹配）
 */
@Data
@TableName("sys_department")
public class SysDepartment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long parentId;

    private String deptName;

    private String deptPath;

    private Integer deptLevel;

    private Integer sort;

    private Integer status;

    private LocalDateTime createTime;
}
