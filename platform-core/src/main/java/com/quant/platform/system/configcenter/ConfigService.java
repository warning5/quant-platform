package com.quant.platform.system.configcenter;

import com.quant.platform.system.monitor.CacheStats;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 参数配置服务
 * - 用 JdbcTemplate 直查（参考 DictService 缓存模式）。
 * - 全量缓存按 configKey 索引；新增/改/删时 evict 缓存，下次读取强制回源 DB，实现热更新无需重启。
 * - 提供 {@link #getValue(String)} 供其他平台能力在运行时读取（如限流阈值 / 全局 cron 开关）。
 */
@Slf4j
@Service
public class ConfigService {

    private final JdbcTemplate jdbcTemplate;
    /** 配置缓存：key=configKey，value=未删除且启用项 */
    private final Map<String, SysConfig> cache = new ConcurrentHashMap<>();
    private volatile boolean loaded = false;
    /** 缓存运行时统计（供 PlatformMonitorController 暴露给监控面板） */
    private final CacheStats cacheStats = new CacheStats("config", () -> cache.size());

    private static final RowMapper<SysConfig> MAPPER = (rs, i) -> {
        SysConfig c = new SysConfig();
        c.setId(rs.getLong("id"));
        c.setConfigKey(rs.getString("config_key"));
        c.setConfigValue(rs.getString("config_value"));
        c.setConfigGroup(rs.getString("config_group"));
        c.setConfigLabel(rs.getString("config_label"));
        c.setConfigType(rs.getString("config_type"));
        c.setEnabled(rs.getInt("enabled"));
        c.setSort(rs.getInt("sort"));
        c.setRemark(rs.getString("remark"));
        c.setCreateTime(toLdt(rs.getTimestamp("create_time")));
        c.setUpdateTime(toLdt(rs.getTimestamp("update_time")));
        return c;
    };

    public ConfigService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 启动时全量加载到缓存（@PostConstruct 由 Spring 调用） */
    @PostConstruct
    public void loadAll() {
        List<SysConfig> list = jdbcTemplate.query(
                "SELECT * FROM sys_config WHERE deleted = 0 AND enabled = 1 ORDER BY config_group, sort", MAPPER);
        cache.clear();
        for (SysConfig c : list) {
            cache.put(c.getConfigKey(), c);
        }
        loaded = true;
        cacheStats.markLoaded();
        log.info("[ConfigService] 加载系统参数 {} 条", cache.size());
    }

    /** 暴露缓存运行时统计（供监控面板） */
    public CacheStats.Snapshot getCacheStats() {
        return cacheStats.snapshot();
    }

    /** 读取配置值（带类型转换兜底）。未命中返回 defaultValue */
    public String getValue(String key) {
        if (!loaded) loadAll();
        SysConfig c = cache.get(key);
        if (c == null) {
            cacheStats.recordMiss();
            return null;
        }
        cacheStats.recordHit();
        return c.getConfigValue();
    }

    public String getValue(String key, String defaultValue) {
        String v = getValue(key);
        return v == null ? defaultValue : v;
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String v = getValue(key);
        return v == null ? defaultValue : Boolean.parseBoolean(v);
    }

    public int getInt(String key, int defaultValue) {
        String v = getValue(key);
        if (v == null) return defaultValue;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** 列表（管理页用，含禁用项） */
    public List<SysConfig> listAll() {
        return jdbcTemplate.query(
                "SELECT * FROM sys_config WHERE deleted = 0 ORDER BY config_group, sort", MAPPER);
    }

    public void save(SysConfig c) {
        if (c.getId() == null) {
            jdbcTemplate.update(
                    "INSERT INTO sys_config(config_key, config_value, config_group, config_label, config_type, enabled, sort, remark) "
                            + "VALUES (?,?,?,?,?,?,?,?)",
                    c.getConfigKey(), c.getConfigValue(), c.getConfigGroup(), c.getConfigLabel(),
                    c.getConfigType(), c.getEnabled() == null ? 1 : c.getEnabled(),
                    c.getSort() == null ? 0 : c.getSort(), c.getRemark());
        } else {
            jdbcTemplate.update(
                    "UPDATE sys_config SET config_value=?, config_group=?, config_label=?, config_type=?, "
                            + "enabled=?, sort=?, remark=? WHERE id=?",
                    c.getConfigValue(), c.getConfigGroup(), c.getConfigLabel(), c.getConfigType(),
                    c.getEnabled() == null ? 1 : c.getEnabled(), c.getSort() == null ? 0 : c.getSort(),
                    c.getRemark(), c.getId());
        }
        evict();
    }

    public void delete(Long id) {
        jdbcTemplate.update("UPDATE sys_config SET deleted = 1 WHERE id = ?", id);
        evict();
    }

    /** 失效缓存，下次读取回源 */
    public void evict() {
        cache.clear();
        loaded = false;
        cacheStats.markEvicted();
    }

    private static LocalDateTime toLdt(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
