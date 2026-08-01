package com.quant.platform.common.event;

import com.quant.platform.backtest.service.FactorStyleAttributionService;
import com.quant.platform.factor.ic.service.FactorIcService;
import com.quant.platform.factor.service.FactorMetaCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 因子状态变更事件监听器（P3-12 服务解耦）
 * 监听 FactorStatusChangedEvent，当因子被降级或复活时：
 * 1. 清除因子元数据缓存（FactorMetaCacheService）
 * 2. 记录日志
 * 监听 FactorComputeCompletedEvent，因子计算完成后：
 * 3. 触发 FF3 因子收益增量计算并写入 factor_premium
 * 4. 触发 IC 历史增量刷新（factor_ic_record），供选股/回测 ICW 使用
 * 通过事件机制解耦，FactorHealthMonitor 不需要直接依赖 FactorMetaCacheService。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FactorStatusChangedEventListener {

    private final FactorMetaCacheService factorMetaCacheService;
    private final FactorStyleAttributionService factorStyleAttributionService;
    private final FactorIcService factorIcService;

    @EventListener
    public void onFactorStatusChanged(FactorStatusChangedEvent event) {
        String factorCode = event.getFactorCode();
        String oldStatus = event.getOldStatus();
        String newStatus = event.getNewStatus();

        log.info("[Event] 因子状态变更: {} {} → {} (原因: {})",
            factorCode, oldStatus, newStatus, event.getReason());

        // 刷新因子元数据缓存（因子状态变更后需要重新加载）
        try {
            factorMetaCacheService.refresh();
            log.debug("[Event] 已刷新因子元数据缓存 (因子{}状态变更)", factorCode);
        } catch (Exception e) {
            log.warn("[Event] 刷新因子缓存失败: {}", e.getMessage());
        }
    }

    /**
     * P3-12: 监听因子计算完成事件，自动触发后续任务：
     * 1) FF3 因子收益增量计算（factor_premium 同步更新）
     * 2) IC 历史增量刷新（factor_ic_record，供选股/回测 ICW 使用）
     * 因子值就绪后再算 IC，IC 刷新异步执行不阻塞事件。
     */
    @EventListener
    public void onFactorComputeCompleted(FactorComputeCompletedEvent event) {
        log.info("[Event] 因子计算完成: date={}, count={}, success={}",
            event.getComputeDate(), event.getFactorCount(), event.isSuccess());

        if (!event.isSuccess()) {
            log.info("[Event] 因子计算失败，跳过后续补算");
            return;
        }

        try {
            factorStyleAttributionService.computeDailyFF3Premium();
        } catch (Exception e) {
            log.warn("[Event] FF3 因子收益补算失败: {}", e.getMessage());
        }

        // 触发 IC 历史增量刷新（因子值就绪后异步算 IC，不阻塞事件处理）
        try {
            factorIcService.refreshIcIncrementallyAsync();
        } catch (Exception e) {
            log.warn("[Event] IC 增量刷新触发失败: {}", e.getMessage());
        }
    }
}
