package com.quant.platform.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 通知配置服务
 * 从 notification_config 表读取配置（缓存，更新时刷新），供 NotificationService 使用，
 * 支持前端动态切换告警渠道、测试推送。
 */
@Slf4j
@Service
public class NotificationConfigService {

    private final JdbcTemplate jdbcTemplate;
    /** 配置缓存（单行表 id=1），避免长时间每次读取 DB */
    private final AtomicReference<NotificationConfig> cache = new AtomicReference<>();

    private static final RowMapper<NotificationConfig> ROW_MAPPER = new RowMapper<>() {
        @Override
        public NotificationConfig mapRow(ResultSet rs, int rowNum) throws SQLException {
            NotificationConfig c = new NotificationConfig();
            c.setId(rs.getInt("id"));
            c.setChannel(rs.getString("channel"));
            c.setServerchanSendkey(rs.getString("serverchan_sendkey"));
            c.setWecomWebhookUrl(rs.getString("wecom_webhook_url"));
            c.setDingtalkWebhookUrl(rs.getString("dingtalk_webhook_url"));
            c.setDingtalkSecret(rs.getString("dingtalk_secret"));
            c.setEnabled(rs.getInt("enabled") == 1);
            return c;
        }
    };

    public NotificationConfigService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        refresh();
    }

    /** 强制刷新缓存 */
    public synchronized void refresh() {
        try {
            NotificationConfig c = jdbcTemplate.queryForObject(
                "SELECT id, channel, serverchan_sendkey, wecom_webhook_url, dingtalk_webhook_url, " +
                "dingtalk_secret, enabled FROM notification_config WHERE id = 1", ROW_MAPPER);
            cache.set(c);
            log.info("[NotificationConfig] 已加载通知配置: channel={}, enabled={}", c.getChannel(), c.isEnabled());
        } catch (Exception e) {
            log.warn("[NotificationConfig] 读取通知配置失败，使用默认(关闭): {}", e.getMessage());
            NotificationConfig def = new NotificationConfig();
            def.setId(1);
            def.setChannel("none");
            def.setEnabled(false);
            cache.set(def);
        }
    }

    /** 获取当前配置（带缓存） */
    public NotificationConfig get() {
        NotificationConfig c = cache.get();
        if (c == null) {
            refresh();
            c = cache.get();
        }
        return c;
    }

    /** 是否启用告警 */
    public boolean isEnabled() {
        return get().isEnabled();
    }

    /** 保存配置并刷新缓存 */
    public synchronized void save(NotificationConfig cfg) {
        jdbcTemplate.update(
            "UPDATE notification_config SET channel=?, serverchan_sendkey=?, wecom_webhook_url=?, " +
            "dingtalk_webhook_url=?, dingtalk_secret=?, enabled=? WHERE id=1",
            cfg.getChannel(),
            cfg.getServerchanSendkey(),
            cfg.getWecomWebhookUrl(),
            cfg.getDingtalkWebhookUrl(),
            cfg.getDingtalkSecret(),
            Boolean.TRUE.equals(cfg.getEnabled()) ? 1 : 0);
        refresh();
    }
}
