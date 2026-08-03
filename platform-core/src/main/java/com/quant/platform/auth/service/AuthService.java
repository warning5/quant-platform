package com.quant.platform.auth.service;

import cn.dev33.satoken.stp.StpUtil;
import com.quant.platform.audit.entity.SysOperationLog;
import com.quant.platform.audit.mapper.SysOperationLogMapper;
import com.quant.platform.auth.dto.ChangePasswordRequest;
import com.quant.platform.auth.dto.LoginVO;
import com.quant.platform.auth.dto.ProfileVO;
import com.quant.platform.auth.dto.UpdateProfileRequest;
import com.quant.platform.auth.service.LoginSecurityService;
import com.quant.platform.common.exception.BusinessException;
import com.quant.platform.system.entity.SysDepartment;
import com.quant.platform.system.entity.SysMenu;
import com.quant.platform.system.entity.SysUser;
import com.quant.platform.system.mapper.SysDepartmentMapper;
import com.quant.platform.system.mapper.SysUserMapper;
import com.quant.platform.system.service.MenuService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 账号密码登录与会话发放
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper userMapper;
    private final MenuService menuService;
    private final PasswordEncoder passwordEncoder;
    private final SysDepartmentMapper departmentMapper;
    private final LoginSecurityService loginSecurityService;
    private final SysOperationLogMapper logMapper;

    /**
     * 账号密码登录（含登录安全防护：账号/IP 锁定、渐进式验证码、失败审计）
     *
     * @param ip           客户端 IP（供锁定维度与审计使用）
     * @param captchaId    图形验证码 id（失败达阈值后必填）
     * @param captchaCode  图形验证码输入值
     */
    public LoginVO login(String username, String password, String ip, String captchaId, String captchaCode, String userAgent) {
        String accountKey = LoginSecurityService.accountKey(username, ip);
        String ipKey = LoginSecurityService.ipKey(ip);

        // 1) 锁定检查（账号维度 / IP 维度）
        long accountRemain = loginSecurityService.getLockRemainSeconds(accountKey);
        if (accountRemain > 0) {
            throw loginFail(username, ip, "账号登录已被锁定，请 " + (accountRemain / 60 + 1) + " 分钟后再试");
        }
        long ipRemain = loginSecurityService.getLockRemainSeconds(ipKey);
        if (ipRemain > 0) {
            throw loginFail(username, ip, "当前网络登录尝试过于频繁，请稍后重试");
        }

        // 2) 渐进式验证码：失败达阈值后必须携带且正确
        //    校验失败同样计入失败次数，避免攻击者靠"不填验证码"绕过账号锁定
        if (loginSecurityService.isCaptchaRequired(accountKey)) {
            if (!loginSecurityService.validateCaptcha(captchaId, captchaCode)) {
                loginSecurityService.recordFailure(accountKey);
                loginSecurityService.recordFailure(ipKey);
                writeLoginFailLog(username, ip, "验证码错误", userAgent);
                throw loginFail(username, ip, "请输入正确的图形验证码");
            }
        }

        // 3) 账号与密码校验
        SysUser user = userMapper.selectByUsername(username);
        if (user == null || (user.getStatus() != null && user.getStatus() == 0)) {
            // 账号不存在/禁用也记录失败，防止用户名枚举爆破（统一返回"账号或密码错误"）
            loginSecurityService.recordFailure(accountKey);
            loginSecurityService.recordFailure(ipKey);
            writeLoginFailLog(username, ip, "账号不存在或已禁用", userAgent);
            throw loginFail(username, ip, "账号或密码错误");
        }
        if (user.getPassword() == null || !passwordEncoder.matches(password, user.getPassword())) {
            loginSecurityService.recordFailure(accountKey);
            loginSecurityService.recordFailure(ipKey);
            writeLoginFailLog(username, ip, "密码错误", userAgent);
            throw loginFail(username, ip, "账号或密码错误");
        }

        // 4) 登录成功：清空失败计数
        loginSecurityService.clear(accountKey);
        loginSecurityService.clear(ipKey);
        writeLoginSuccessLog(user, ip, userAgent);
        return issueToken(user);
    }

    /** 构造登录失败异常，并附带是否需要图形验证码的标志（供前端渐进式展示） */
    private BusinessException loginFail(String username, String ip, String msg) {
        boolean needCaptcha = loginSecurityService.isCaptchaRequired(
                LoginSecurityService.accountKey(username, ip));
        return new BusinessException(400, msg, java.util.Map.of("needCaptcha", needCaptcha));
    }

    /** 登录失败写审计日志（失败路径不会被 @OperationLog 切面捕获，这里手动记录） */
    private void writeLoginFailLog(String username, String ip, String reason, String userAgent) {
        try {
            SysOperationLog logEntity = new SysOperationLog();
            logEntity.setUsername(username);
            logEntity.setIp(ip);
            logEntity.setRequestUrl("/api/auth/login");
            logEntity.setHttpMethod("POST");
            logEntity.setModule("auth");
            logEntity.setAction("login_fail");
            logEntity.setResult(0);
            logEntity.setErrorMsg(reason);
            logEntity.setUserAgent(userAgent);
            logEntity.setOperationTime(LocalDateTime.now());
            logEntity.setCreateTime(LocalDateTime.now());
            logEntity.setUpdateTime(LocalDateTime.now());
            logEntity.setDeleted(0);
            logMapper.insert(logEntity);
        } catch (Exception e) {
            log.warn("写入登录失败审计日志异常: {}", e.getMessage());
        }
    }

    /** 登录成功写审计日志（含 IP 与 User-Agent，供安全审计追溯） */
    private void writeLoginSuccessLog(SysUser user, String ip, String userAgent) {
        try {
            SysOperationLog logEntity = new SysOperationLog();
            logEntity.setUserId(user.getId());
            logEntity.setUsername(user.getUsername());
            logEntity.setIp(ip);
            logEntity.setRequestUrl("/api/auth/login");
            logEntity.setHttpMethod("POST");
            logEntity.setModule("auth");
            logEntity.setAction("login_success");
            logEntity.setResult(1);
            logEntity.setUserAgent(userAgent);
            logEntity.setOperationTime(LocalDateTime.now());
            logEntity.setCreateTime(LocalDateTime.now());
            logEntity.setUpdateTime(LocalDateTime.now());
            logEntity.setDeleted(0);
            logMapper.insert(logEntity);
        } catch (Exception e) {
            log.warn("写入登录成功审计日志异常: {}", e.getMessage());
        }
    }

    /**
     * 已解析出用户后发放登录态并构建返回结果（微信登录复用）
     */
    public LoginVO issueToken(SysUser user) {
        StpUtil.login(user.getId());
        StpUtil.getSession().set("username", user.getUsername());
        // 数据权限上下文：部门路径 + 角色id（查询拦截器与插入拦截器读取）
        Long deptId = user.getDeptId() != null ? user.getDeptId() : 0L;
        String deptPath = "/1";
        if (deptId != 0L) {
            SysDepartment dept = departmentMapper.selectById(deptId);
            if (dept != null && dept.getDeptPath() != null && !dept.getDeptPath().isEmpty()) {
                deptPath = dept.getDeptPath();
            }
        }
        StpUtil.getSession().set("deptId", deptId);
        StpUtil.getSession().set("deptPath", deptPath);
        List<Long> roleIds = userMapper.selectRoleIdByUserId(user.getId());
        String roleIdStr = (roleIds != null && !roleIds.isEmpty())
                ? roleIds.stream().map(String::valueOf).collect(Collectors.joining(","))
                : "0";
        StpUtil.getSession().set("roleIds", roleIdStr);
        user.setLastLoginTime(LocalDateTime.now());
        userMapper.updateById(user);
        return buildResult(user, StpUtil.getTokenValue());
    }

    /**
     * 获取当前登录用户信息（刷新用）
     */
    public LoginVO getMe() {
        StpUtil.checkLogin();
        Long uid = Long.parseLong(StpUtil.getLoginIdAsString());
        SysUser user = userMapper.selectById(uid);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        return buildResult(user, StpUtil.getTokenValue());
    }

    public void logout() {
        StpUtil.logout();
    }

    /**
     * 获取当前登录用户的个人资料（不含密码等敏感字段）
     */
    public ProfileVO getProfile() {
        StpUtil.checkLogin();
        Long uid = Long.parseLong(StpUtil.getLoginIdAsString());
        SysUser user = userMapper.selectById(uid);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        ProfileVO vo = new ProfileVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setEmail(user.getEmail());
        vo.setPhone(user.getPhone());
        vo.setAvatar(user.getAvatar());
        vo.setLastLoginTime(user.getLastLoginTime());
        return vo;
    }

    /**
     * 更新当前登录用户自己的资料（仅覆盖传入的非空字段）
     */
    public void updateProfile(UpdateProfileRequest req) {
        StpUtil.checkLogin();
        Long uid = Long.parseLong(StpUtil.getLoginIdAsString());
        SysUser user = userMapper.selectById(uid);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        if (StringUtils.hasText(req.getNickname())) {
            user.setNickname(req.getNickname());
        }
        if (StringUtils.hasText(req.getEmail())) {
            user.setEmail(req.getEmail());
        }
        if (StringUtils.hasText(req.getPhone())) {
            user.setPhone(req.getPhone());
        }
        if (StringUtils.hasText(req.getAvatar())) {
            user.setAvatar(req.getAvatar());
        }
        userMapper.updateById(user);
    }

    /**
     * 自助修改密码：校验原密码 → 校验新密码复杂度 → 重新哈希落库
     */
    public void changePassword(ChangePasswordRequest req) {
        StpUtil.checkLogin();
        Long uid = Long.parseLong(StpUtil.getLoginIdAsString());
        SysUser user = userMapper.selectById(uid);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        if (!StringUtils.hasText(req.getOldPassword())
                || !passwordEncoder.matches(req.getOldPassword(), user.getPassword())) {
            throw new BusinessException("原密码错误");
        }
        if (req.getNewPassword().equals(req.getOldPassword())) {
            throw new BusinessException("新密码不能与原密码相同");
        }
        validatePasswordComplexity(req.getNewPassword());
        user.setPassword(passwordEncoder.encode(req.getNewPassword()));
        userMapper.updateById(user);
    }

    /** 密码复杂度：长度 8-64 且同时包含字母和数字（与 UserService 三入口保持一致） */
    private void validatePasswordComplexity(String password) {
        if (password == null || password.length() < 8 || password.length() > 64) {
            throw new BusinessException("密码长度需为 8-64 位");
        }
        boolean hasLetter = password.matches(".*[a-zA-Z].*");
        boolean hasDigit = password.matches(".*[0-9].*");
        if (!hasLetter || !hasDigit) {
            throw new BusinessException("密码必须同时包含字母和数字");
        }
    }

    private LoginVO buildResult(SysUser user, String token) {
        LoginVO result = new LoginVO();
        result.setTokenName(StpUtil.getTokenName());
        result.setToken(token);
        result.setUserId(user.getId());
        result.setUsername(user.getUsername());
        result.setNickname(user.getNickname());
        result.setAvatar(user.getAvatar());
        result.setRoles(StpUtil.getRoleList());
        result.setPermissions(StpUtil.getPermissionList());
        List<SysMenu> menus = menuService.getUserMenuTree(user.getId());
        result.setMenus(menus);
        return result;
    }
}
