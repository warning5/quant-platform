package com.quant.platform.auth.config;

import cn.dev33.satoken.stp.StpInterface;
import com.quant.platform.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 自定义权限加载接口：根据登录用户从库里实时拉取角色与权限
 */
@Component
@RequiredArgsConstructor
public class StpInterfaceImpl implements StpInterface {

    private final SysUserMapper sysUserMapper;

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return sysUserMapper.selectPermissionByUserId(Long.valueOf(loginId.toString()));
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        return sysUserMapper.selectRoleCodeByUserId(Long.valueOf(loginId.toString()));
    }
}
