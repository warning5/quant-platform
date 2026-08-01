package com.quant.platform.credential.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.quant.platform.common.dto.PageRequest;
import com.quant.platform.common.exception.BusinessException;
import com.quant.platform.credential.dto.CredentialDetail;
import com.quant.platform.credential.dto.CredentialRequest;
import com.quant.platform.credential.entity.SysCredential;
import com.quant.platform.credential.mapper.SysCredentialMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 凭证(密钥)管理：AES 加密存储，列表返回掩码，详情返回明文。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CredentialService {

    private final SysCredentialMapper mapper;
    private final CredentialCryptoService crypto;

    /** LLM 接入使用的凭证标识 */
    public static final String LLM_DEEPSEEK_KEY = "DEEPSEEK_API_KEY";

    public IPage<SysCredential> page(PageRequest req, String category, String keyword) {
        Page<SysCredential> page = new Page<>(req.getPage() + 1, req.getSize());
        LambdaQueryWrapper<SysCredential> q = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(category)) {
            q.eq(SysCredential::getCategory, category);
        }
        if (StringUtils.hasText(keyword)) {
            String kw = keyword;
            q.and(w -> w.like(SysCredential::getCredentialKey, kw).or().like(SysCredential::getName, kw));
        }
        q.eq(SysCredential::getDeleted, 0).orderByDesc(SysCredential::getCreateTime);
        IPage<SysCredential> res = mapper.selectPage(page, q);
        // 列表不暴露密文
        res.getRecords().forEach(c -> c.setEncryptedValue(null));
        return res;
    }

    public void create(CredentialRequest req) {
        validateKey(req.getCredentialKey());
        if (mapper.selectOne(new LambdaQueryWrapper<SysCredential>()
                .eq(SysCredential::getCredentialKey, req.getCredentialKey())
                .eq(SysCredential::getDeleted, 0)) != null) {
            throw new BusinessException("凭证标识已存在");
        }
        SysCredential c = new SysCredential();
        c.setCredentialKey(req.getCredentialKey());
        c.setName(req.getName());
        c.setCategory(StringUtils.hasText(req.getCategory()) ? req.getCategory() : "llm");
        c.setEncryptedValue(crypto.encrypt(req.getValue()));
        c.setMaskedValue(mask(req.getValue()));
        c.setEnabled(req.getEnabled() == null ? 1 : req.getEnabled());
        c.setRemark(req.getRemark());
        mapper.insert(c);
        log.info("[Credential] 新增凭证: key={}", req.getCredentialKey());
    }

    public void update(CredentialRequest req) {
        if (req.getId() == null) {
            throw new BusinessException("ID不能为空");
        }
        SysCredential c = mapper.selectById(req.getId());
        if (c == null) {
            throw new BusinessException("凭证不存在");
        }
        if (StringUtils.hasText(req.getName())) {
            c.setName(req.getName());
        }
        if (StringUtils.hasText(req.getCategory())) {
            c.setCategory(req.getCategory());
        }
        if (StringUtils.hasText(req.getValue())) {
            c.setEncryptedValue(crypto.encrypt(req.getValue()));
            c.setMaskedValue(mask(req.getValue()));
        }
        if (req.getEnabled() != null) {
            c.setEnabled(req.getEnabled());
        }
        if (req.getRemark() != null) {
            c.setRemark(req.getRemark());
        }
        mapper.updateById(c);
        log.info("[Credential] 更新凭证: id={}", req.getId());
    }

    public void delete(Long id) {
        SysCredential c = mapper.selectById(id);
        if (c == null) {
            throw new BusinessException("凭证不存在");
        }
        mapper.deleteById(id);
        log.info("[Credential] 删除凭证: id={}, key={}", id, c.getCredentialKey());
    }

    public CredentialDetail detail(Long id) {
        SysCredential c = mapper.selectById(id);
        if (c == null) {
            throw new BusinessException("凭证不存在");
        }
        CredentialDetail d = new CredentialDetail();
        d.setId(c.getId());
        d.setCredentialKey(c.getCredentialKey());
        d.setName(c.getName());
        d.setCategory(c.getCategory());
        d.setValue(crypto.decrypt(c.getEncryptedValue()));
        d.setEnabled(c.getEnabled());
        d.setRemark(c.getRemark());
        d.setCreateTime(c.getCreateTime());
        d.setUpdateTime(c.getUpdateTime());
        return d;
    }

    /**
     * 按凭证标识取解密明文，供其他模块运行时使用（如 LLM）。
     * 找不到或解密失败返回 null（调用方应回退）。
     */
    public String getDecrypted(String credentialKey) {
        SysCredential c = mapper.selectOne(new LambdaQueryWrapper<SysCredential>()
                .eq(SysCredential::getCredentialKey, credentialKey)
                .eq(SysCredential::getEnabled, 1)
                .eq(SysCredential::getDeleted, 0));
        if (c == null) {
            return null;
        }
        try {
            return crypto.decrypt(c.getEncryptedValue());
        } catch (Exception e) {
            log.error("[Credential] 解密失败 key={}: {}", credentialKey, e.getMessage());
            return null;
        }
    }

    private void validateKey(String key) {
        if (!StringUtils.hasText(key)) {
            throw new BusinessException("凭证标识不能为空");
        }
        if (!key.matches("[A-Za-z0-9_\\-]+")) {
            throw new BusinessException("凭证标识只能含字母、数字、下划线、中划线");
        }
    }

    private String mask(String plain) {
        if (plain == null || plain.isEmpty()) {
            return "";
        }
        if (plain.length() <= 8) {
            return plain.charAt(0) + "****";
        }
        return plain.substring(0, 4) + "****" + plain.substring(plain.length() - 4);
    }
}
