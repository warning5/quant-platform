package com.quant.platform.dataupdate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quant.platform.stock.entity.StockInfo;
import com.quant.platform.stock.mapper.StockInfoMapper;
import com.quant.platform.stock.service.ClickHouseStockService;
import com.quant.platform.calendar.service.TradeCalendarService;
import com.quant.platform.factor.domain.FactorDefinition;
import com.quant.platform.factor.mapper.FactorDefinitionMapper;
import com.quant.platform.factor.service.FactorService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.quant.platform.common.enums.JobStatus;
@Slf4j
@Service
@RequiredArgsConstructor
public class DataUpdateScriptService {
    private final com.quant.platform.stock.mapper.StockInfoMapper stockInfoMapper;
@Value("${quant.data-update.python-path:python}")
        private String pythonPath;
@Value("${quant.data-update.default-start-days:3}")
        private int defaultStartDays;
    public void addCommonArgs(List<String> cmd, DataUpdateRequest request) {
        // 日期参数：没选时默认最近 defaultStartDays 天
        String startDate = request.getStartDate();
        String endDate = request.getEndDate();
        if ((startDate == null || startDate.isEmpty()) && (endDate == null || endDate.isEmpty())) {
            java.time.LocalDate today = java.time.LocalDate.now();
            java.time.LocalDate from = today.minusDays(defaultStartDays);
            startDate = from.toString();
            endDate = today.toString();
        }
        if (startDate != null && !startDate.isEmpty()) {
            cmd.add("--start-date");
            cmd.add(startDate);
        }
        if (endDate != null && !endDate.isEmpty()) {
            cmd.add("--end-date");
            cmd.add(endDate);
        }
        if (request.isDailyOnly()) cmd.add("--daily-only");
        if (request.isInfoOnly()) cmd.add("--info-only");
        if (request.isResume()) cmd.add("--resume");
        if (request.isForce()) cmd.add("--force");
        if (request.getLimit() != null && request.getLimit() > 0) {
            cmd.add("--limit");
            cmd.add(request.getLimit().toString());
        }
        if (request.getBatchSize() != null && request.getBatchSize() > 0) {
            cmd.add("--batch-size");
            cmd.add(request.getBatchSize().toString());
        }
        if (request.getDelay() != null && request.getDelay() > 0) {
            cmd.add("--delay");
            cmd.add(request.getDelay().toString());
        }
        // 股票池筛选
        String pool = request.getStockPool();
        if (pool != null && !"ALL".equals(pool)) {
            cmd.add("--pool");
            cmd.add(pool);
        }
    }

    public List<String> buildCommand(DataUpdateRequest request) {
        // infoOnly 模式：只执行 stock_info 更新脚本
        if (request.isInfoOnly()) {
            List<String> cmd = new ArrayList<>();
            cmd.add(pythonPath);
            cmd.add("-u");
            cmd.add("update_stock_info_daily.py");
            return cmd;
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(pythonPath);
        cmd.add("-u");  // 强制 unbuffered stdout，解决管道模式下行缓冲失效问题

        String updateType = request.getUpdateType();

        // 指数日线（只传日期和 code 参数，不支持 resume/limit 等）
        if ("INDEX".equals(updateType)) {
            cmd.add("update_index_daily_baostock.py");
            // 日期参数
            String startDate = request.getStartDate();
            String endDate = request.getEndDate();
            if ((startDate == null || startDate.isEmpty()) && (endDate == null || endDate.isEmpty())) {
                java.time.LocalDate today = java.time.LocalDate.now();
                java.time.LocalDate from = today.minusDays(defaultStartDays);
                startDate = from.toString();
                endDate = today.toString();
            }
            if (startDate != null && !startDate.isEmpty()) {
                cmd.add("--start-date");
                cmd.add(startDate);
            }
            if (endDate != null && !endDate.isEmpty()) {
                cmd.add("--end-date");
                cmd.add(endDate);
            }
            // force 参数
            if (request.isForce()) {
                cmd.add("--force");
            }
            return cmd;
        }

        // 分红除权
        if ("DIVIDEND".equals(updateType)) {
            cmd.add("update_dividend_baostock.py");
            if (request.isResume()) cmd.add("--resume");
            // update_dividend_baostock.py 不支持 --force 参数
            // 全量/增量通过 --resume 区分：有 --resume 跳过已有数据，无则全量重新采集
            if (request.getLimit() != null && request.getLimit() > 0) {
                cmd.add("--limit");
                cmd.add(request.getLimit().toString());
            }
            return cmd;
        }

        // 前复权因子刷新（除权除息后重刷历史qfq价格）
        if ("QFQ_REFRESH".equals(updateType)) {
            cmd.add("refresh_qfq_history.py");
            // days参数：查最近N天除权股票（调度默认1天，手动默认7天）
            int days = 7; // 默认7天（手动触发）
            if (request.getLimit() != null && request.getLimit() > 0) {
                days = request.getLimit();
            }
            cmd.add("--days");
            cmd.add(String.valueOf(days));
            // 不限制历史范围：除权除息后 qfq 因子会 retroactive 更新全部历史，必须从上市日拉到今天
            String startDate = request.getStartDate();
            if (startDate != null && !startDate.isEmpty()) {
                cmd.add("--start-date");
                cmd.add(startDate);
            }
            String endDate = request.getEndDate();
            if (endDate != null && !endDate.isEmpty()) {
                cmd.add("--end-date");
                cmd.add(endDate);
            }
            String singleCode = request.getSingleCode();
            if (singleCode != null && !singleCode.isEmpty()) {
                cmd.add("--code");
                cmd.add(singleCode);
            }
            // 超时15s（Baostock正常响应<5s，15s足够）
            cmd.add("--timeout");
            cmd.add("15");
            return cmd;
        }

        // 内外盘数据（调用 update_stock_data.py --bidask-only）
        if ("BIDASK".equals(updateType)) {
            cmd.add("update_stock_data.py");
            cmd.add("--bidask-only");
            // 单只股票
            String singleCode = request.getSingleCode();
            if (singleCode != null && !singleCode.isEmpty()) {
                cmd.add("--code");
                cmd.add(singleCode);
            }
            // 日期参数透传
            String startDate = request.getStartDate();
            String endDate = request.getEndDate();
            if (startDate != null && !startDate.isEmpty()) {
                cmd.add("--start-date");
                cmd.add(startDate);
            }
            if (endDate != null && !endDate.isEmpty()) {
                cmd.add("--end-date");
                cmd.add(endDate);
            }
            return cmd;
        }

        // 财务数据
        if ("FINANCIAL".equals(updateType)) {
            cmd.add("update_financial_data.py");
            // 单只股票模式：只跑 ths + sina 两个步骤（跳过 yjbb 批量步骤）
            String singleCode = request.getSingleCode();
            if (singleCode != null && !singleCode.isEmpty()) {
                // 不在这里返回，继续构建命令，由 executeTask 分两阶段执行
                cmd.add("--step");
                cmd.add("ths");
                cmd.add("--code");
                cmd.add(singleCode);
            } else {
                if (request.getYearStart() != null) {
                    cmd.add("--year-start");
                    cmd.add(request.getYearStart().toString());
                }
                if (request.getYearEnd() != null) {
                    cmd.add("--year-end");
                    cmd.add(request.getYearEnd().toString());
                }
            }
            if (request.isForce()) {
                cmd.add("--force");
            }
            return cmd;
        }

        // 情绪数据
        if ("SENTIMENT".equals(updateType)) {
            // WESTOCK 模式：只用 westock-data 跑资金流向，跳过其他所有子模块
            if ("WESTOCK".equalsIgnoreCase(request.getMoneyflowSource())) {
                cmd.add("update_sentiment_data.py");
                cmd.add("--moneyflow-westock");
                String startDate = request.getStartDate();
                String endDate = request.getEndDate();
                if ((startDate == null || startDate.isEmpty()) && (endDate == null || endDate.isEmpty())) {
                    java.time.LocalDate today = java.time.LocalDate.now();
                    java.time.LocalDate from = today.minusDays(3);
                    startDate = from.toString();
                    endDate = today.toString();
                }
                if (startDate != null && !startDate.isEmpty()) {
                    cmd.add("--start-date");
                    cmd.add(startDate);
                }
                if (endDate != null && !endDate.isEmpty()) {
                    cmd.add("--end-date");
                    cmd.add(endDate);
                }
                if (request.getSentimentCodes() != null && !request.getSentimentCodes().isEmpty()) {
                    cmd.add("--codes");
                    cmd.add(request.getSentimentCodes());
                }
                log.info("[DataUpdate] WESTOCK 模式：仅更新资金流向，日期 {} ~ {}", startDate, endDate);
                return cmd;
            }
            // EM（东方财富）模式：跑东财实时/历史资金流向，跳过其他所有子模块
            if ("EM".equalsIgnoreCase(request.getMoneyflowSource())) {
                cmd.add("update_sentiment_data.py");
                boolean isHist = "hist".equalsIgnoreCase(request.getEmMoneyflowMode());
                cmd.add(isHist ? "--em-moneyflow-hist" : "--em-moneyflow");
                if (request.getSentimentCodes() != null && !request.getSentimentCodes().isEmpty()) {
                    cmd.add("--codes");
                    cmd.add(request.getSentimentCodes());
                }
                if (request.isForce()) cmd.add("--force");
                log.info("[DataUpdate] EM（东方财富）模式：{}，codes={}", isHist ? "历史120天" : "实时全市场", request.getSentimentCodes());
                return cmd;
            }
            // AKSHARE 模式（默认）：原有逻辑
            cmd.add("update_sentiment_data.py");
            String startDate = request.getStartDate();
            String endDate = request.getEndDate();
            if (startDate != null && !startDate.isEmpty()) {
                cmd.add("--start-date");
                cmd.add(startDate);
            }
            if (endDate != null && !endDate.isEmpty()) {
                cmd.add("--end-date");
                cmd.add(endDate);
            }
            if (request.getSentimentCodes() != null && !request.getSentimentCodes().isEmpty()) {
                cmd.add("--codes");
                cmd.add(request.getSentimentCodes());
            }
            // 未勾选的数据源传 --skip-xxx 参数
            if (!request.isFetchLhb()) {
                cmd.add("--skip-lhb");
                cmd.add("--skip-lhb-inst");
            }
            if (!request.isFetchMargin()) {
                cmd.add("--skip-margin");
                cmd.add("--skip-margin-detail");
            }
            if (!request.isFetchSurvey()) cmd.add("--skip-survey");
            if (!request.isFetchBlockTrade()) cmd.add("--skip-block");
            if (!request.isFetchActivity()) cmd.add("--skip-activity");
            if (!request.isFetchZtPool()) cmd.add("--skip-zt");
            if (!request.isFetchMoneyflow()) cmd.add("--skip-moneyflow");
            if (!request.isFetchNotice()) cmd.add("--skip-notice");
            if (request.isFetchFundHolder()) cmd.add("--fund-holder");
            if (request.isFetchShareholder()) cmd.add("--shareholder");
            if (request.isFetchNews()) cmd.add("--news");
            // 注意：--bond-yield 和 --shenwan-index 不是 update_sentiment_data.py 的参数，
            // 这两个脚本在 executeTask 中作为独立任务串行执行
            if (request.isForce()) cmd.add("--force");
            return cmd;
        }

        // 研报数据
        if ("RESEARCH".equals(updateType)) {
            cmd.add("update_research_report.py");
            if (request.isForce()) {
                cmd.add("--all");
            }
            // 日期范围
            String startDate = request.getStartDate();
            String endDate = request.getEndDate();
            if (startDate != null && !startDate.isEmpty()) {
                cmd.add("--start-date");
                cmd.add(startDate);
            }
            if (endDate != null && !endDate.isEmpty()) {
                cmd.add("--end-date");
                cmd.add(endDate);
            }
            String singleCode = request.getSingleCode(); // 新增字段：单只股票代码
            if (singleCode != null && !singleCode.isEmpty()) {
                cmd.add(singleCode);
            }
            return cmd;
        }

        // 筹码分布增量更新(方案C): 日线更新后自动追算(读 stock_cyq_daily 最新快照, 仅推进新交易日)
        if ("CYQ".equals(request.getUpdateType())) {
            cmd.add("cyq_service.py");
            cmd.add("--incremental");
            // 不传 --end-date: Python 默认取 stock_daily 最新交易日, 保证追到最新数据
            return cmd;
        }

        // 股票日线
        if ("TENCENT_ALL".equals(request.getSource())) {
            // 腾讯全市场（沪深+北交所），使用统一 data_provider 模块
            cmd.add("update_stock_daily.py");
            cmd.add("--source");
            cmd.add("tencent");
        } else if ("TENCENT".equals(request.getSource()) || "BJ".equals(request.getMarket())) {
            cmd.add("update_bj_stock_daily_qq.py");
        } else if ("BAOSTOCK".equals(request.getSource())) {
            // BAOSTOCK 数据源覆盖 SH + SZ，返回 null 走 executeAllMarkets 分别调用
            return null;
        } else if ("SH".equals(request.getMarket())) {
            cmd.add("update_stock_daily_baostock.py");
            cmd.add("--market");
            cmd.add("SH");
        } else if ("SZ".equals(request.getMarket())) {
            cmd.add("update_stock_daily_baostock.py");
            cmd.add("--market");
            cmd.add("SZ");
        } else {
            return null; // ALL → executeAllMarkets（含自动降级）
        }

        addCommonArgs(cmd, request);
        return cmd;
    }

    public void applyStockPool(LambdaQueryWrapper<StockInfo> wrapper, String pool) {
        switch (pool) {
            case "SH300":
                // 沪深300: 大盘股，简化用市值前300
                wrapper.orderByDesc(StockInfo::getTotalMarketCap).last("LIMIT 300");
                break;
            case "SZ50":
                wrapper.likeRight(StockInfo::getCode, "000").or().likeRight(StockInfo::getCode, "60")
                        .orderByDesc(StockInfo::getTotalMarketCap).last("LIMIT 50");
                break;
            case "ZZ500":
                wrapper.orderByDesc(StockInfo::getTotalMarketCap).last("LIMIT 800");
                break;
            case "ZZ1000":
                wrapper.orderByDesc(StockInfo::getTotalMarketCap).last("LIMIT 1000");
                break;
            case "STAR50":
                wrapper.likeRight(StockInfo::getCode, "688");
                break;
            default:
                break;
        }
    }

    public int estimateTotalStocks(DataUpdateRequest request) {
        LambdaQueryWrapper<StockInfo> wrapper = new LambdaQueryWrapper<>();

        // 分红除权 + 前复权刷新 只覆盖 SH+SZ
        if ("DIVIDEND".equals(request.getUpdateType())
                || "QFQ_REFRESH".equals(request.getUpdateType())) {
            wrapper.in(StockInfo::getMarket, "SH", "SZ");
        } else if ("BAOSTOCK".equals(request.getSource())) {
            wrapper.in(StockInfo::getMarket, "SH", "SZ");
        } else if ("TENCENT_ALL".equals(request.getSource())) {
            wrapper.in(StockInfo::getMarket, "SH", "SZ", "BJ");
        } else if ("CYQ".equals(request.getUpdateType())) {
            wrapper.in(StockInfo::getMarket, "SH", "SZ", "BJ");
        } else if ("TENCENT".equals(request.getSource()) || "BJ".equals(request.getMarket())) {
        } else if (!"ALL".equals(request.getMarket())) {
            wrapper.eq(StockInfo::getMarket, request.getMarket());
        }

        if (request.isExcludeSt()) {
            wrapper.and(w -> w.eq(StockInfo::getIsSt, 0).or().isNull(StockInfo::getIsSt));
        }

        // 股票池筛选
        if (!"ALL".equals(request.getStockPool())) {
            applyStockPool(wrapper, request.getStockPool());
        }

        return Math.max(stockInfoMapper.selectCount(wrapper).intValue(), 1);
    }

    public String extractDateFromLine(String line) {
        java.util.regex.Matcher dm = java.util.regex.Pattern.compile("(\\d{4}-\\d{2}-\\d{2})").matcher(line);
        return dm.find() ? dm.group(1) : null;
    }
    void configurePythonEnv(ProcessBuilder pb) {
        Map<String, String> env = pb.environment();
        env.put("PYTHONIOENCODING", "utf-8");
        env.putIfAbsent("DB_BACKEND", "clickhouse");
    }

}
