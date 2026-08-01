package com.quant.platform.dataperm.service;

import com.quant.platform.dataperm.domain.ResourceShareDO;
import lombok.Data;

import java.util.List;

/**
 * 资源权限配置视图对象
 */
@Data
public class ResourcePermissionVO {

    /** 全局可见性：PRIVATE / DEPT / PUBLIC */
    private String visibility;

    /** 创建者 user_id */
    private Long ownerId;

    /** 显式授权列表 */
    private List<ResourceShareDO> shares;
}
