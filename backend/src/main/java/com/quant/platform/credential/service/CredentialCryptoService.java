package com.quant.platform.credential.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 凭证加解密服务（AES-256-CBC）
 * 主密钥取自配置 credential.aes-key（环境变量 CREDENTIAL_AES_KEY）；
 * 未配置时使用内置开发密钥并强烈告警（伪安全，生产必须配置）。
 */
@Slf4j
@Service
public class CredentialCryptoService {

    /** 仅当未配置 CREDENTIAL_AES_KEY 时使用的开发密钥（不安全，仅本地开发） */
    private static final String DEV_MASTER_KEY = "quant-platform-dev-only-aes-master-key";

    private final byte[] key;

    public CredentialCryptoService(@Value("${credential.aes-key:}") String aesKey) {
        String master = (aesKey == null || aesKey.isEmpty()) ? DEV_MASTER_KEY : aesKey;
        if (aesKey == null || aesKey.isEmpty()) {
            log.warn("[CredentialCrypto] 未配置 credential.aes-key，使用内置开发密钥，凭证仅为伪安全；"
                    + "生产环境请通过环境变量 CREDENTIAL_AES_KEY 设置强密钥（建议 32 字节随机串）！");
        }
        this.key = sha256(master);
    }

    private static byte[] sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public String encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            byte[] iv = new byte[16];
            new SecureRandom().nextBytes(iv);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            byte[] enc = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + enc.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(enc, 0, out, iv.length, enc.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new RuntimeException("凭证加密失败", e);
        }
    }

    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isEmpty()) {
            return null;
        }
        try {
            byte[] data = Base64.getDecoder().decode(cipherText);
            byte[] iv = new byte[16];
            System.arraycopy(data, 0, iv, 0, 16);
            byte[] enc = new byte[data.length - 16];
            System.arraycopy(data, 16, enc, 0, enc.length);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            byte[] plain = cipher.doFinal(enc);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("[CredentialCrypto] 凭证解密失败: {}", e.getMessage());
            return null;
        }
    }
}
