package com.quant.platform.system.dto;

import lombok.Data;

import java.util.List;

/**
 * 更新用户请求
 */
@Data
public class UpdateUserRequest {
    private Long id;
    private String nickname;
    private String email;
    private String phone;
    private Integer status;
    private String password;
    private List<Long> roleIds;
}
