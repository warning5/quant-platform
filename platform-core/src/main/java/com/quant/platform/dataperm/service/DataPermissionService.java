package com.quant.platform.dataperm.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.quant.platform.common.enums.ResourceType;
import com.quant.platform.common.exception.BusinessException;
import com.quant.platform.dataperm.domain.ResourceMetaDO;
import com.quant.platform.dataperm.domain.ResourceShareDO;
import com.quant.platform.dataperm.mapper.ResourceMetaMapper;
import com.quant.platform.dataperm.mapper.ResourceShareMapper;
import com.quant.spi.ResourceOptionProvider;
import com.quant.platform.system.entity.SysDepartment;
import com.quant.platform.system.entity.SysRole;
import com.quant.platform.system.entity.SysUser;
import com.quant.platform.system.mapper.SysDepartmentMapper;
import com.quant.platform.system.mapper.SysRoleMapper;
import com.quant.platform.system.mapper.SysUserMapper;
import com.quant.spi.ResourceOptionVO;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 数据权限配置服务（方案C 配置入口）。
 * 仅 owner 与 ADMIN 可查看/修改某资源的权限设置；查询隔离由拦截器在 SQL 层完成。
 *
 * <p>X2 解耦：资源下拉选项（STRATEGY/FACTOR/BACKTEST/PAPER_TRADING）不再由本类直接 import 业务 Mapper 拼装，
 * 而是由业务模块通过 {@link ResourceOptionProvider} SPI 注册，本类按 {@link ResourceType} 查表。
 * 本类因此不再依赖任何业务包（factor/strategy/backtest/paper），彻底打破双向耦合。</p>
 */
@Service
@RequiredArgsConstructor
public class DataPermissionService {

    private static final List<String> VISIBILITIES = List.of("PRIVATE", "DEPT", "PUBLIC");
    private static final List<String> GRANTEE_TYPES = List.of("USER", "DEPT", "ROLE");
    private static final List<String> PERM_LEVELS = List.of("VIEW", "EDIT");

    private final ResourceMetaMapper metaMapper;
    private final ResourceShareMapper shareMapper;
    private final SysUserMapper userMapper;
    private final SysDepartmentMapper deptMapper;
    private final SysRoleMapper roleMapper;
    private final List<ResourceOptionProvider> optionProviders;

    /** ResourceType -> 选项提供方（由 Spring 收集所有 SPI 实现）。 */
    private Map<ResourceType, ResourceOptionProvider> providerMap;

    @PostConstruct
    void initProviders() {
        providerMap = optionProviders.stream()
                .collect(Collectors.toMap(ResourceOptionProvider::supports, p -> p));
    }

    /**
     * 资源下拉选项：按类型返回 id + 中文标签，供配置页下拉选择。
     * 受数据权限拦截器约束（ADMIN 看全部，其余仅看可见资源）。
     * 选项内容由各业务模块通过 {@link ResourceOptionProvider} SPI 提供。
     */
    public List<ResourceOptionVO> listOptions(String type) {
        assertValidType(type);
        ResourceType rt = ResourceType.fromCode(type);
        ResourceOptionProvider provider = providerMap.get(rt);
        return provider == null ? List.of() : provider.listOptions();
    }

    /**
     * 授权对象下拉选项：按类型返回可选列表。
     * USER → 用户列表(id, label=nickname/username)
     * DEPT → 部门树(树形结构，供 TreeSelect 使用)
     * ROLE → 角色列表(id, label=roleName)
     */
    public Object listGrantees(String granteeType) {
        return switch (granteeType) {
            case "USER" -> {
                List<SysUser> users = userMapper.selectList(
                        new QueryWrapper<SysUser>().select("id", "username", "nickname")
                                .eq("status", 1).eq("deleted", 0)
                                .last("ORDER BY id LIMIT 500"));
                yield users.stream().map(u -> new GranteeOptionVO(u.getId(),
                        (u.getNickname() != null && !u.getNickname().isBlank()
                                ? u.getNickname() : u.getUsername()) + " (" + u.getUsername() + ")"))
                        .toList();
            }
            case "DEPT" -> buildDeptTree();
            case "ROLE" -> {
                List<SysRole> roles = roleMapper.selectList(
                        new QueryWrapper<SysRole>().select("id", "role_code", "role_name")
                                .eq("status", 1).eq("deleted", 0)
                                .last("ORDER BY id"));
                yield roles.stream().map(r -> new GranteeOptionVO(r.getId(),
                        r.getRoleName() + " (" + r.getRoleCode() + ")")).toList();
            }
            default -> List.of();
        };
    }

    /** 构建部门树（parentId 自引用 → 递归树） */
    private List<DeptTreeNodeVO> buildDeptTree() {
        List<SysDepartment> all = deptMapper.selectList(
                new QueryWrapper<SysDepartment>().eq("status", 1)
                        .orderByAsc("sort").orderByAsc("id"));
        // 扁平 map
        java.util.Map<Long, DeptTreeNodeVO> nodeMap = new java.util.LinkedHashMap<>();
        for (SysDepartment d : all) {
            nodeMap.put(d.getId(), new DeptTreeNodeVO(d.getId(), d.getDeptName()));
        }
        // 挂子节点
        List<DeptTreeNodeVO> roots = new java.util.ArrayList<>();
        for (SysDepartment d : all) {
            DeptTreeNodeVO node = nodeMap.get(d.getId());
            if (d.getParentId() == null || d.getParentId() == 0 || !nodeMap.containsKey(d.getParentId())) {
                roots.add(node);
            } else {
                nodeMap.get(d.getParentId()).getChildren().add(node);
            }
        }
        return roots;
    }

    public ResourcePermissionVO getPermissions(String type, Long id, Long operatorUid) {
        assertValidType(type);
        checkOwner(type, id, operatorUid);
        ResourceMetaDO meta = selectMeta(type, id);
        ResourcePermissionVO vo = new ResourcePermissionVO();
        vo.setVisibility(meta != null ? meta.getVisibility() : "PRIVATE");
        vo.setOwnerId(meta != null ? meta.getOwnerId() : null);
        vo.setShares(shareMapper.listByResource(type, id));
        return vo;
    }

    public void setVisibility(String type, Long id, String visibility, Long operatorUid) {
        assertValidType(type);
        if (visibility == null || !VISIBILITIES.contains(visibility)) {
            throw new BusinessException("非法可见性值: " + visibility);
        }
        checkOwner(type, id, operatorUid);
        ResourceMetaDO meta = selectMeta(type, id);
        if (meta == null) {
            meta = new ResourceMetaDO();
            meta.setResourceType(type);
            meta.setResourceId(id);
            meta.setOwnerId(operatorUid);
            meta.setOwnerDeptId(0L);
            meta.setVisibility(visibility);
            metaMapper.insert(meta);
        } else {
            meta.setVisibility(visibility);
            metaMapper.updateById(meta);
        }
    }

    public void addShare(String type, Long id, String granteeType, Long granteeId,
                         String permLevel, Long grantedBy) {
        assertValidType(type);
        if (granteeType == null || !GRANTEE_TYPES.contains(granteeType)) {
            throw new BusinessException("非法授权对象类型: " + granteeType);
        }
        if (granteeId == null) {
            throw new BusinessException("授权对象 id 必填");
        }
        if (permLevel == null || !PERM_LEVELS.contains(permLevel)) {
            throw new BusinessException("非法权限级别: " + permLevel);
        }
        checkOwner(type, id, grantedBy);
        ResourceShareDO s = new ResourceShareDO();
        s.setResourceType(type);
        s.setResourceId(id);
        s.setGranteeType(granteeType);
        s.setGranteeId(granteeId);
        s.setPermLevel(permLevel);
        s.setGrantedBy(grantedBy);
        shareMapper.insert(s);
    }

    public void removeShare(String type, Long id, Long shareId, Long operatorUid) {
        assertValidType(type);
        checkOwner(type, id, operatorUid);
        ResourceShareDO s = shareMapper.selectById(shareId);
        if (s == null || !s.getResourceType().equals(type) || !s.getResourceId().equals(id)) {
            throw new BusinessException("授权记录不存在");
        }
        shareMapper.deleteById(shareId);
    }

    private ResourceMetaDO selectMeta(String type, Long id) {
        return metaMapper.selectOne(new QueryWrapper<ResourceMetaDO>()
                .eq("resource_type", type).eq("resource_id", id));
    }

    private void checkOwner(String type, Long id, Long operatorUid) {
        if (StpUtil.hasRole("ADMIN")) {
            return;
        }
        ResourceMetaDO meta = selectMeta(type, id);
        if (meta == null || !meta.getOwnerId().equals(operatorUid)) {
            throw new BusinessException("无权操作该资源的权限设置");
        }
    }

    private void assertValidType(String type) {
        if (ResourceType.fromCode(type) == null) {
            throw new BusinessException("非法资源类型: " + type);
        }
    }
}
