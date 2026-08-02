package com.quant.platform.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.quant.platform.common.dto.PageRequest;
import com.quant.platform.common.exception.BusinessException;
import com.quant.platform.system.dto.CreateUserRequest;
import com.quant.platform.system.dto.UpdateUserRequest;
import com.quant.platform.system.entity.SysUser;
import com.quant.platform.system.entity.SysUserRole;
import com.quant.platform.system.mapper.SysUserMapper;
import com.quant.platform.system.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户管理
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;

    public IPage<SysUser> pageUsers(PageRequest req, String username, String nickname, Integer status) {
        Page<SysUser> page = new Page<>(req.getPage() + 1, req.getSize());
        LambdaQueryWrapper<SysUser> q = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(username)) {
            q.like(SysUser::getUsername, username);
        }
        if (StringUtils.hasText(nickname)) {
            q.like(SysUser::getNickname, nickname);
        }
        if (status != null) {
            q.eq(SysUser::getStatus, status);
        }
        q.eq(SysUser::getDeleted, 0).orderByDesc(SysUser::getCreateTime);
        IPage<SysUser> result = userMapper.selectPage(page, q);
        result.getRecords().forEach(u -> u.setPassword(null));
        return result;
    }

    public SysUser createUser(CreateUserRequest request) {
        if (!StringUtils.hasText(request.getUsername())) {
            throw new BusinessException("登录账号不能为空");
        }
        if (userMapper.selectByUsername(request.getUsername()) != null) {
            throw new BusinessException("登录账号已存在");
        }
        SysUser user = new SysUser();
        user.setUsername(request.getUsername());
        user.setNickname(request.getNickname() != null ? request.getNickname() : request.getUsername());
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setStatus(request.getStatus() == null ? 1 : request.getStatus());
        user.setDeptId(request.getDeptId() != null ? request.getDeptId() : 0L);
        if (StringUtils.hasText(request.getPassword())) {
            validatePasswordComplexity(request.getPassword());
            user.setPassword(passwordEncoder.encode(request.getPassword()));
        }
        userMapper.insert(user);
        if (request.getRoleIds() != null && !request.getRoleIds().isEmpty()) {
            assignRoles(user.getId(), request.getRoleIds());
        }
        return user;
    }

    public SysUser updateUser(UpdateUserRequest request) {
        SysUser user = userMapper.selectById(request.getId());
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        if (StringUtils.hasText(request.getNickname())) {
            user.setNickname(request.getNickname());
        }
        if (request.getEmail() != null) {
            user.setEmail(request.getEmail());
        }
        if (request.getPhone() != null) {
            user.setPhone(request.getPhone());
        }
        if (request.getStatus() != null) {
            user.setStatus(request.getStatus());
        }
        user.setDeptId(request.getDeptId() != null ? request.getDeptId() : 0L);
        if (StringUtils.hasText(request.getPassword())) {
            validatePasswordComplexity(request.getPassword());
            user.setPassword(passwordEncoder.encode(request.getPassword()));
        }
        userMapper.updateById(user);
        if (request.getRoleIds() != null) {
            assignRoles(user.getId(), request.getRoleIds());
        }
        return user;
    }

    public void deleteUser(Long id) {
        userMapper.deleteById(id);
        userRoleMapper.deleteByUserId(id);
    }

    public void resetPassword(Long id, String newPassword) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        if (!StringUtils.hasText(newPassword)) {
            throw new BusinessException("新密码不能为空");
        }
        validatePasswordComplexity(newPassword);
        user.setPassword(passwordEncoder.encode(newPassword));
        userMapper.updateById(user);
    }

    /** 密码复杂度校验：长度 8-64，且同时包含字母与数字（拦截纯数字/纯字母弱口令） */
    private void validatePasswordComplexity(String password) {
        if (password.length() < 8 || password.length() > 64) {
            throw new BusinessException("密码长度需为 8-64 位");
        }
        boolean hasLetter = password.matches(".*[a-zA-Z].*");
        boolean hasDigit = password.matches(".*[0-9].*");
        if (!hasLetter || !hasDigit) {
            throw new BusinessException("密码必须同时包含字母和数字");
        }
    }

    public List<Long> getRoleIds(Long userId) {
        return userRoleMapper.selectRoleIdsByUserId(userId);
    }

    public void assignRoles(Long userId, List<Long> roleIds) {
        userRoleMapper.deleteByUserId(userId);
        if (roleIds != null && !roleIds.isEmpty()) {
            List<SysUserRole> list = new ArrayList<>();
            for (Long roleId : roleIds) {
                SysUserRole ur = new SysUserRole();
                ur.setUserId(userId);
                ur.setRoleId(roleId);
                list.add(ur);
            }
            userRoleMapper.insertBatch(list);
        }
    }
}
