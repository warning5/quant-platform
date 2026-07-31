package com.quant.platform.dataperm.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * resource_meta：资源归属 + 全局可见性（方案C 外置权限表，业务表零改动）。
 */
@Setter
@Getter
@TableName("resource_meta")
public class ResourceMetaDO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String resourceType;
    private Long resourceId;
    private Long ownerId;
    private Long ownerDeptId;
    private String visibility;
    private LocalDateTime createdAt;

}
