package com.quant.platform.mp.controller;

import com.quant.platform.common.dto.ApiResponse;
import com.quant.platform.recommendation.domain.StrategyConfidence;
import com.quant.platform.recommendation.mapper.StrategyConfidenceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

/**
 * 策略置信度 API（小程序专用，直连 MySQL）
 */
@Slf4j
@RestController
@RequestMapping("/mp/strategy-confidence")
@RequiredArgsConstructor
public class MpStrategyConfidenceController {

    private final StrategyConfidenceMapper strategyConfidenceMapper;

    /**
     * 获取所有策略的最新置信度列表
     * GET /mp/strategy-confidence/latest-all
     */
    @GetMapping("/latest-all")
    public ResponseEntity<ApiResponse<List<StrategyConfidence>>> getAllLatest() {
        List<StrategyConfidence> list = strategyConfidenceMapper.findAllLatest();
        return ResponseEntity.ok(ApiResponse.success(list));
    }

    /**
     * 获取某策略的最新置信度
     * GET /mp/strategy-confidence?strategyId=35
     */
    @GetMapping
    public ResponseEntity<ApiResponse<StrategyConfidence>> getLatest(@RequestParam Long strategyId) {
        Optional<StrategyConfidence> opt = strategyConfidenceMapper.findLatestByStrategyId(strategyId);
        if (opt.isPresent()) {
            return ResponseEntity.ok(ApiResponse.success(opt.get()));
        }
        // 返回空对象（前端显示"暂无数据"）
        StrategyConfidence empty = new StrategyConfidence();
        empty.setStrategyId(strategyId);
        empty.setLevel("UNTRAINED");
        return ResponseEntity.ok(ApiResponse.success(empty));
    }
}
