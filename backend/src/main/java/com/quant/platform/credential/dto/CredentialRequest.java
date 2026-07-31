package com.quant.platform.credential.dto;

import lombok.Data;

/**
 * 凭证新增/编辑请求
 */
@Data
public class CredentialRequest {
    private Long id;
    private String credentialKey;
    private String name;
    private String category;
    /** 明文密钥，创建/编辑时传入，不落库（加密后存储） */
    private String value;
    private Integer enabled;
    private String remark;
}
