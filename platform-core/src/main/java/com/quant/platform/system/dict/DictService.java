package com.quant.platform.system.dict;

import com.quant.platform.system.monitor.CacheStats;
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
 * 字典服务
 * - 用 JdbcTemplate 直查（参考 NotificationConfigService 的缓存模式），避免引入 MyBatis 实体层。
 * - 按 dictType 缓存启用项；新增/改/删数据项时 evict 对应类型缓存，下次查询强制回源 DB，
 *   实现"前端加一级枚举 → 业务自动感知、无需重启"。
 */
@Slf4j
@Service
public class DictService {

    private final JdbcTemplate jdbcTemplate;
    /** 字典项缓存：key=dictType，value=该类型下【启用】项列表 */
    private final Map<String, List<SysDictData>> cache = new ConcurrentHashMap<>();
    /** 缓存运行时统计（供 PlatformMonitorController 暴露给监控面板） */
    private final CacheStats cacheStats = new CacheStats("dict", () -> cache.size());

    private static final RowMapper<SysDictData> DATA_MAPPER = (rs, i) -> {
        SysDictData d = new SysDictData();
        d.setId(rs.getLong("id"));
        d.setDictType(rs.getString("dict_type"));
        d.setDictValue(rs.getString("dict_value"));
        d.setDictLabel(rs.getString("dict_label"));
        d.setSort(rs.getInt("sort"));
        d.setColor(rs.getString("color"));
        d.setExtJson(rs.getString("ext_json"));
        d.setRemark(rs.getString("remark"));
        d.setStatus(rs.getInt("status"));
        d.setCreateTime(toLdt(rs.getTimestamp("create_time")));
        d.setUpdateTime(toLdt(rs.getTimestamp("update_time")));
        return d;
    };

    private static final RowMapper<SysDictType> TYPE_MAPPER = (rs, i) -> {
        SysDictType t = new SysDictType();
        t.setId(rs.getLong("id"));
        t.setDictType(rs.getString("dict_type"));
        t.setTypeName(rs.getString("type_name"));
        t.setDescription(rs.getString("description"));
        t.setStatus(rs.getInt("status"));
        t.setSort(rs.getInt("sort"));
        t.setCreateTime(toLdt(rs.getTimestamp("create_time")));
        t.setUpdateTime(toLdt(rs.getTimestamp("update_time")));
        return t;
    };

    public DictService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static LocalDateTime toLdt(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    /** 取某类型全部【启用】项（带缓存）；新增/改/删会 evict */
    public List<SysDictData> listByType(String dictType) {
        List<SysDictData> hit = cache.get(dictType);
        if (hit != null) {
            cacheStats.recordHit();
            return hit;
        }
        cacheStats.recordMiss();
        // computeIfAbsent 保证并发安全：只有一个线程会执行 queryEnabled
        return cache.computeIfAbsent(dictType, k -> {
            List<SysDictData> loaded = queryEnabled(k);
            cacheStats.markLoaded();
            return loaded;
        });
    }

    /** 暴露缓存运行时统计（供监控面板） */
    public CacheStats.Snapshot getCacheStats() {
        return cacheStats.snapshot();
    }

    /** 取某类型【全部】项（含禁用），供管理页编辑用，不缓存 */
    public List<SysDictData> listDataAll(String dictType) {
        return queryAll(dictType);
    }

    public List<SysDictType> listTypes() {
        return jdbcTemplate.query(
            "SELECT id, dict_type, type_name, description, status, sort, create_time, update_time " +
            "FROM sys_dict_type WHERE deleted = 0 ORDER BY sort ASC, id ASC", TYPE_MAPPER);
    }

    private List<SysDictData> queryEnabled(String dictType) {
        try {
            return jdbcTemplate.query(
                "SELECT id, dict_type, dict_value, dict_label, sort, color, ext_json, remark, status, create_time, update_time " +
                "FROM sys_dict_data WHERE dict_type = ? AND deleted = 0 AND status = 1 ORDER BY sort ASC, id ASC",
                DATA_MAPPER, dictType);
        } catch (Exception e) {
            log.warn("[Dict] 查询字典项失败 dictType={}: {}", dictType, e.getMessage());
            return List.of();
        }
    }

    private List<SysDictData> queryAll(String dictType) {
        return jdbcTemplate.query(
            "SELECT id, dict_type, dict_value, dict_label, sort, color, ext_json, remark, status, create_time, update_time " +
            "FROM sys_dict_data WHERE dict_type = ? AND deleted = 0 ORDER BY sort ASC, id ASC",
            DATA_MAPPER, dictType);
    }

    public void saveType(SysDictType t) {
        jdbcTemplate.update(
            "INSERT INTO sys_dict_type (dict_type, type_name, description, status, sort, create_time, update_time, deleted) " +
            "VALUES (?, ?, ?, ?, ?, NOW(), NOW(), 0)",
            t.getDictType(), t.getTypeName(), t.getDescription(),
            t.getStatus() == null ? 1 : t.getStatus(),
            t.getSort() == null ? 0 : t.getSort());
    }

    public void updateType(SysDictType t) {
        jdbcTemplate.update(
            "UPDATE sys_dict_type SET type_name=?, description=?, status=?, sort=?, update_time=NOW() WHERE dict_type=?",
            t.getTypeName(), t.getDescription(),
            t.getStatus() == null ? 1 : t.getStatus(),
            t.getSort() == null ? 0 : t.getSort(),
            t.getDictType());
        evict(t.getDictType());
    }

    public void saveData(SysDictData d) {
        jdbcTemplate.update(
            "INSERT INTO sys_dict_data (dict_type, dict_value, dict_label, sort, color, ext_json, remark, status, create_time, update_time, deleted) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW(), 0)",
            d.getDictType(), d.getDictValue(), d.getDictLabel(),
            d.getSort() == null ? 0 : d.getSort(),
            d.getColor(), d.getExtJson(), d.getRemark(),
            d.getStatus() == null ? 1 : d.getStatus());
        evict(d.getDictType());
    }

    public void updateData(SysDictData d) {
        jdbcTemplate.update(
            "UPDATE sys_dict_data SET dict_label=?, sort=?, color=?, ext_json=?, remark=?, status=? WHERE id=?",
            d.getDictLabel(), d.getSort(), d.getColor(), d.getExtJson(), d.getRemark(),
            d.getStatus() == null ? 1 : d.getStatus(), d.getId());
        evict(d.getDictType());
    }

    public void deleteData(Long id) {
        List<String> found = jdbcTemplate.query(
            "SELECT dict_type FROM sys_dict_data WHERE id = ?", (rs, i) -> rs.getString("dict_type"), id);
        jdbcTemplate.update("UPDATE sys_dict_data SET deleted = 1, update_time = NOW() WHERE id = ?", id);
        if (!found.isEmpty()) evict(found.get(0));
    }

    public void deleteType(String dictType) {
        jdbcTemplate.update("UPDATE sys_dict_type SET deleted = 1, update_time = NOW() WHERE dict_type = ?", dictType);
        jdbcTemplate.update("UPDATE sys_dict_data SET deleted = 1, update_time = NOW() WHERE dict_type = ?", dictType);
        evict(dictType);
    }

    private void evict(String dictType) {
        if (dictType != null) cache.remove(dictType);
    }
}
