package com.quant.platform.credential.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统凭证(密钥)加密存储
 */
@Data
@TableName("sys_credential")
public class SysCredential {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 凭证标识，如 DEEPSEEK_API_KEY */
    private String credentialKey;

    private String name;
    private String category;

    /** AES 加密密文(Base64) */
    private String encryptedValue;

    /** 明文掩码，可安全展示 */
    private String maskedValue;

    /** 1=启用 0=禁用 */
    private Integer enabled;

    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;

    /** 详情编辑用明文，非表字段（仅详情接口填充，列表不返回） */
    @TableField(exist = false)
    private String plainValueForEdit;
}
