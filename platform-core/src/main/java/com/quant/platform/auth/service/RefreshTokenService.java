package com.quant.platform.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Refresh Token 管理（#29 静默刷新）。
 *
 * <p>设计：access token 缩短到 30 分钟，前端用此 refreshToken 调 /auth/refresh 无感换新。
 * refreshToken 为一次性凭证——消费即作废并轮换新值，降低泄露风险。
 *
 * <p>存储：当前为进程内 ConcurrentHashMap（单实例足够）。若部署多实例，请替换为 Redis，
 * 否则各实例不共享 refresh 状态（TODO: 多实例时接入 Redis）。
 */
@Service
public class RefreshTokenService {

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    @Value("${auth.refresh-token-ttl-seconds:604800}")
    private long ttlSeconds;

    /** 生成 refreshToken 并登记（关联 loginId） */
    public String create(Object loginId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        store.put(token, new Entry(String.valueOf(loginId), Instant.now().getEpochSecond() + ttlSeconds));
        return token;
    }

    /**
     * 消费 refreshToken（一次性）：校验存在且未过期后删除并返回 loginId。
     * 返回 null 表示无效或已过期。
     */
    public String consume(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        Entry e = store.remove(token);
        if (e == null) {
            return null;
        }
        if (Instant.now().getEpochSecond() > e.expireAt) {
            return null;
        }
        return e.loginId;
    }

    /** 删除指定 refreshToken（登出时调用） */
    public void remove(String token) {
        if (token != null && !token.isBlank()) {
            store.remove(token);
        }
    }

    /** 清理过期项（可定时调用，避免内存无限增长） */
    public void sweep() {
        long now = Instant.now().getEpochSecond();
        store.entrySet().removeIf(en -> en.getValue().expireAt < now);
    }

    private static class Entry {
        final String loginId;
        long expireAt;

        Entry(String loginId, long expireAt) {
            this.loginId = loginId;
            this.expireAt = expireAt;
        }
    }
}
