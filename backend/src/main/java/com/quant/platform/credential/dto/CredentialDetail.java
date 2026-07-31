package com.quant.platform.credential.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 凭证详情（含解密明文，仅详情接口返回）
 */
@Data
public class CredentialDetail {
    private Long id;
    private String credentialKey;
    private String name;
    private String category;
    /** 解密后的明文密钥 */
    private String value;
    private Integer enabled;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
