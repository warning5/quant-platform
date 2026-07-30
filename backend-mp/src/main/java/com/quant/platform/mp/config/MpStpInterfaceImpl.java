package com.quant.platform.mp.config;

import cn.dev33.satoken.stp.StpInterface;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 小程序端不做细粒度权限/角色校验（由 MpAuthFilter 仅校验登录态），
 * 故权限与角色列表返回空。如后续需要可按 userId 从 sys_role_menu 查。
 */
@Component
public class MpStpInterfaceImpl implements StpInterface {

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return List.of();
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        return List.of();
    }
}
