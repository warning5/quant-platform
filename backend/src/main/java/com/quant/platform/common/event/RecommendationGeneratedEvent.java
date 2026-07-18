package com.quant.platform.common.event;

import org.springframework.context.ApplicationEvent;

import java.time.LocalDate;

/**
 * 推荐生成完成事件（P3-12 服务解耦）
 *
 * 当推荐系统生成推荐后发布此事件。
 * 下游服务（如追踪记录、通知推送、组合风控）可监听此事件。
 */
public class RecommendationGeneratedEvent extends ApplicationEvent {

    private final LocalDate recommendDate;
    private final Long strategyId;
    private final String weightMode;
    private final int recommendationCount;
    private final boolean success;

    public RecommendationGeneratedEvent(Object source, LocalDate recommendDate, Long strategyId,
                                        String weightMode, int recommendationCount, boolean success) {
        super(source);
        this.recommendDate = recommendDate;
        this.strategyId = strategyId;
        this.weightMode = weightMode;
        this.recommendationCount = recommendationCount;
        this.success = success;
    }

    public LocalDate getRecommendDate() { return recommendDate; }
    public Long getStrategyId() { return strategyId; }
    public String getWeightMode() { return weightMode; }
    public int getRecommendationCount() { return recommendationCount; }
    public boolean isSuccess() { return success; }
}
