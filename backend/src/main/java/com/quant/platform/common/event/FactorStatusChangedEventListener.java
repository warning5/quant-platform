package com.quant.platform.common.event;

import com.quant.platform.factor.service.FactorMetaCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 因子状态变更事件监听器（P3-12 服务解耦）
 *
 * 监听 FactorStatusChangedEvent，当因子被降级或复活时：
 * 1. 清除因子元数据缓存（FactorMetaCacheService）
 * 2. 记录日志
 *
 * 通过事件机制解耦，FactorHealthMonitor 不需要直接依赖 FactorMetaCacheService。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FactorStatusChangedEventListener {

    private final FactorMetaCacheService factorMetaCacheService;

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
}
