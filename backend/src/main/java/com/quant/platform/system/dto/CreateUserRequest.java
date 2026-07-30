package com.quant.platform.system.dto;

import lombok.Data;

import java.util.List;

/**
 * 创建用户请求
 */
@Data
public class CreateUserRequest {
    private String username;
    private String nickname;
    private String email;
    private String phone;
    private Integer status;
    private String password;
    private List<Long> roleIds;
}
