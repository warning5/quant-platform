package com.quant.platform.credential.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 凭证新增/编辑请求
 */
@Data
public class CredentialRequest {
    private Long id;
    @NotBlank(message = "凭证标识不能为空")
    @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "凭证标识只能含字母、数字、下划线、中划线")
    private String credentialKey;
    @NotBlank(message = "名称不能为空")
    private String name;
    private String category;
    /** 明文密钥，创建/编辑时传入，不落库（加密后存储） */
    private String value;
    private Integer enabled;
    private String remark;
}
