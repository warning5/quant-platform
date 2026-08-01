package com.quant.platform.dataperm.task;

import com.quant.platform.dataperm.mapper.OrphanCheckMapper;
import com.quant.platform.dataperm.mapper.ResourceMetaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 孤儿资源对账（方案C 安全网）。
 * 每日凌晨扫描业务表有、resource_meta 无的资源，补 owner=admin(1) 的 PRIVATE 行，
 * 防止创建时拦截器漏触发导致资源连创建者自己都看不到。
 * 同一逻辑覆盖历史存量数据迁移。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrphanReconcileTask {

    private final OrphanCheckMapper orphanMapper;
    private final ResourceMetaMapper metaMapper;

    @Scheduled(cron = "0 30 3 * * ?")
    public void reconcile() {
        reconcile("STRATEGY", orphanMapper.strategyOrphans());
        reconcile("FACTOR", orphanMapper.factorOrphans());
        reconcile("BACKTEST", orphanMapper.backtestOrphans());
        reconcile("PAPER_TRADING", orphanMapper.paperOrphans());
    }

    private void reconcile(String type, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        int count = 0;
        for (Long id : ids) {
            // INSERT IGNORE 保证 (resource_type, resource_id) 唯一键幂等
            metaMapper.insertIgnore(type, id, 1L, 1L);
            count++;
        }
        if (count > 0) {
            log.info("[数据权限对账] 类型={} 补录孤儿资源 {} 条", type, count);
        }
    }
}
