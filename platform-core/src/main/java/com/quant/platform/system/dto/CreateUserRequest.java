package com.quant.platform.system.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 创建用户请求
 */
@Data
public class CreateUserRequest {
    @NotBlank(message = "登录账号不能为空")
    private String username;
    private String nickname;
    @Email(message = "邮箱格式不正确")
    private String email;
    private String phone;
    private Integer status;
    @NotBlank(message = "密码不能为空")
    private String password;
    private List<Long> roleIds;
}
