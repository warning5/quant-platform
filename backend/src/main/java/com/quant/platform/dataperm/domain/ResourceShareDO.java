package com.quant.platform.dataperm.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * resource_share：显式授权（每个授权一行）。
 * grantee_type: USER / DEPT / ROLE；perm_level: VIEW / EDIT
 */
@Data
@TableName("resource_share")
public class ResourceShareDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String resourceType;

    private Long resourceId;

    private String granteeType;

    private Long granteeId;

    private String permLevel;

    private Long grantedBy;

    private LocalDateTime createdAt;
}
