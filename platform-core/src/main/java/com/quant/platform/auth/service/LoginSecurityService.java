package com.quant.platform.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.quant.platform.auth.entity.SysLoginFail;
import com.quant.platform.auth.mapper.SysLoginFailMapper;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录安全防护：
 * 1) 失败计数 + 锁定（账号维度 U:username|ip 与 IP 维度 I:ip 双维度）
 * 2) 渐进式图形验证码（内存存储，无 Redis 依赖）
 * 阈值从 application.yml 的 login-security.* 读取。
 */
@Service
public class LoginSecurityService {

    private final SysLoginFailMapper failMapper;

    @Value("${login-security.account-lock-threshold:5}")
    private int accountLockThreshold;
    @Value("${login-security.account-lock-minutes:15}")
    private int accountLockMinutes;
    @Value("${login-security.ip-lock-threshold:20}")
    private int ipLockThreshold;
    @Value("${login-security.ip-lock-minutes:15}")
    private int ipLockMinutes;
    @Value("${login-security.captcha-threshold:3}")
    private int captchaThreshold;
    @Value("${login-security.captcha-expire-seconds:300}")
    private int captchaExpireSeconds;

    /** 去掉易混淆字符 0/O/1/I/l 的字符集 */
    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private final Map<String, CaptchaEntry> captchaStore = new ConcurrentHashMap<>();

    public LoginSecurityService(SysLoginFailMapper failMapper) {
        this.failMapper = failMapper;
    }

    // ===== 维度键 =====
    public static String accountKey(String username, String ip) {
        return "U:" + (username == null ? "" : username) + "|" + (ip == null ? "" : ip);
    }

    public static String ipKey(String ip) {
        return "I:" + (ip == null ? "" : ip);
    }

    private boolean isIpKey(String key) {
        return key.startsWith("I:");
    }

    // ===== 锁定查询 =====
    /** 剩余锁定秒数，0 表示未锁定 */
    public long getLockRemainSeconds(String key) {
        SysLoginFail f = failMapper.selectOne(queryByKey(key));
        if (f == null || f.getLockedUntil() == null) {
            return 0;
        }
        long remain = java.time.Duration.between(LocalDateTime.now(), f.getLockedUntil()).getSeconds();
        return remain > 0 ? remain : 0;
    }

    /** 是否已达到需要验证码的失败次数阈值 */
    public boolean isCaptchaRequired(String key) {
        SysLoginFail f = failMapper.selectOne(queryByKey(key));
        return f != null && f.getFailCount() != null && f.getFailCount() >= captchaThreshold;
    }

    // ===== 失败记录 =====
    public void recordFailure(String key) {
        LocalDateTime now = LocalDateTime.now();
        int threshold = isIpKey(key) ? ipLockThreshold : accountLockThreshold;
        int minutes = isIpKey(key) ? ipLockMinutes : accountLockMinutes;

        SysLoginFail existing = failMapper.selectOne(queryByKey(key));
        if (existing == null) {
            SysLoginFail f = new SysLoginFail();
            f.setLockKey(key);
            f.setFailCount(1);
            f.setFirstFailTime(now);
            f.setLastFailTime(now);
            f.setLockedUntil(1 >= threshold ? now.plusMinutes(minutes) : null);
            failMapper.insert(f);
        } else {
            int next = (existing.getFailCount() == null ? 0 : existing.getFailCount()) + 1;
            LocalDateTime lockedUntil = (next >= threshold) ? now.plusMinutes(minutes) : existing.getLockedUntil();
            failMapper.update(null, new LambdaUpdateWrapper<SysLoginFail>()
                    .eq(SysLoginFail::getLockKey, key)
                    .set(SysLoginFail::getFailCount, next)
                    .set(SysLoginFail::getLastFailTime, now)
                    .set(SysLoginFail::getLockedUntil, lockedUntil));
        }
    }

    /** 登录成功后清空该维度的失败计数 */
    public void clear(String key) {
        failMapper.delete(queryByKey(key));
    }

    private LambdaQueryWrapper<SysLoginFail> queryByKey(String key) {
        return new LambdaQueryWrapper<SysLoginFail>().eq(SysLoginFail::getLockKey, key);
    }

    // ===== 图形验证码 =====
    @Data
    private static class CaptchaEntry {
        private String code;
        private LocalDateTime expireAt;
    }

    /** 生成验证码，返回 id 与 base64 PNG（data url） */
    public CaptchaResult generateCaptcha() {
        cleanupExpiredCaptcha();
        String code = randomCode(4);
        String id = UUID.randomUUID().toString().replace("-", "");
        CaptchaEntry entry = new CaptchaEntry();
        entry.setCode(code);
        entry.setExpireAt(LocalDateTime.now().plusSeconds(captchaExpireSeconds));
        captchaStore.put(id, entry);

        CaptchaResult result = new CaptchaResult();
        result.setCaptchaId(id);
        result.setImage("data:image/png;base64," + renderBase64(code));
        return result;
    }

    /** 校验验证码（大小写不敏感，一次性消费） */
    public boolean validateCaptcha(String id, String code) {
        if (id == null || code == null) {
            return false;
        }
        CaptchaEntry entry = captchaStore.get(id);
        if (entry == null) {
            return false;
        }
        if (LocalDateTime.now().isAfter(entry.getExpireAt())) {
            captchaStore.remove(id);
            return false;
        }
        boolean ok = entry.getCode().equalsIgnoreCase(code.trim());
        if (ok) {
            captchaStore.remove(id);
        }
        return ok;
    }

    private void cleanupExpiredCaptcha() {
        LocalDateTime now = LocalDateTime.now();
        captchaStore.entrySet().removeIf(en -> now.isAfter(en.getValue().getExpireAt()));
    }

    private String randomCode(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(CHARS.charAt((int) (Math.random() * CHARS.length())));
        }
        return sb.toString();
    }

    private String renderBase64(String code) {
        int w = 110, h = 40;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        // 干扰线
        g.setColor(Color.LIGHT_GRAY);
        for (int i = 0; i < 6; i++) {
            g.drawLine((int) (Math.random() * w), (int) (Math.random() * h),
                    (int) (Math.random() * w), (int) (Math.random() * h));
        }
        // 字符
        g.setFont(new Font("Arial", Font.BOLD, 28));
        for (int i = 0; i < code.length(); i++) {
            g.setColor(new Color((int) (Math.random() * 120), (int) (Math.random() * 120), (int) (Math.random() * 120)));
            g.drawString(String.valueOf(code.charAt(i)), 12 + i * 24, 30 + (int) (Math.random() * 4));
        }
        g.dispose();
        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", os);
            return Base64.getEncoder().encodeToString(os.toByteArray());
        } catch (Exception ex) {
            throw new RuntimeException("生成验证码图片失败", ex);
        }
    }

    @Data
    public static class CaptchaResult {
        private String captchaId;
        private String image;
    }
}
