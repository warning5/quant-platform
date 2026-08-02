package com.quant.platform.system.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 更新用户请求
 */
@Data
public class UpdateUserRequest {
    @NotNull(message = "ID不能为空")
    private Long id;
    private String nickname;
    @Email(message = "邮箱格式不正确")
    private String email;
    private String phone;
    private Integer status;
    private Long deptId;
    private String password;
    private List<Long> roleIds;
}
