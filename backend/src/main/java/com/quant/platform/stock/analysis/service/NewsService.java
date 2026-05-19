package com.quant.platform.stock.analysis.service;

import com.quant.platform.stock.analysis.mapper.NewsMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 新闻事件分析服务
 * 从 stock_news 表读取数据，提供新闻列表和评分计算
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NewsService {

    private final NewsMapper newsMapper;

    // 事件标签中文映射
    private static final Map<String, String> TAG_LABELS = new LinkedHashMap<>();
    static {
        TAG_LABELS.put("PERFORMANCE", "业绩");
        TAG_LABELS.put("EXPANSION", "扩产/建厂");
        TAG_LABELS.put("INDIA_HOT", "印度高温");
        TAG_LABELS.put("POLICY_RISK", "政策风险");
        TAG_LABELS.put("RAW_MATERIAL", "原材料");
        TAG_LABELS.put("M_A", "并购定增");
        TAG_LABELS.put("UNLOCK", "解禁减持");
        TAG_LABELS.put("INCENTIVE", "股权激励");
        TAG_LABELS.put("GOODWILL", "商誉");
        TAG_LABELS.put("FUND", "资金流向");
    }

    // 利好关键词（增强情感判断）
    private static final Set<String> POSITIVE_KEYWORDS = Set.of(
            "增持", "买入", "超预期", "业绩大增", "扭亏", "创新高", "中标",
            "订单", "扩产", "建厂", "高温", "热销", "强劲", "加速", "突破",
            "超配", "强势", "首超", "超10倍", "同比增长", "环比增长"
    );

    // 风险关键词（增强情感判断）
    private static final Set<String> NEGATIVE_KEYWORDS = Set.of(
            "减持", "卖出", "下调", "不及预期", "亏损", "预警", "警示",
            "调查", "处罚", "违约", "诉讼", "商誉减值", "终止", "违规",
            "被查", "ST", "涉嫌", "业绩下滑", "造假", "暴雷"
    );

    /**
     * 获取个股新闻分析（前端展示用）
     * @param code 股票代码
     * @return 包含利好/风险/中性新闻列表 + 汇总评分
     */
    public Map<String, Object> getNewsAnalysis(String code) {
        Map<String, Object> result = new LinkedHashMap<>();

        // 近30天汇总
        Map<String, Object> stats30d = newsMapper.selectNewsStats30d(code, 30);
        // 全部历史汇总
        Map<String, Object> summary = newsMapper.selectNewsSummary(code);

        int positive30d = ((Number) stats30d.getOrDefault("positive_30d", 0)).intValue();
        int negative30d = ((Number) stats30d.getOrDefault("negative_30d", 0)).intValue();
        int tagged30d = ((Number) stats30d.getOrDefault("tagged_30d", 0)).intValue();

        result.put("total30d", ((Number) stats30d.getOrDefault("total_30d", 0)).intValue());
        result.put("positive30d", positive30d);
        result.put("negative30d", negative30d);
        result.put("tagged30d", tagged30d);
        result.put("latestDate", summary.get("latest_date"));

        // 计算情感偏向分数 (-1 ~ 1)
        int total30 = positive30d + negative30d;
        double sentimentBias = 0.0;
        if (total30 > 0) {
            sentimentBias = Math.round((double) (positive30d - negative30d) / total30 * 100.0) / 100.0;
        }
        result.put("sentimentBias", sentimentBias);

        // 利好新闻（最多10条）
        List<Map<String, Object>> positiveNews = newsMapper.selectNewsByCode(code, "positive", null, 10);
        // 风险新闻（最多10条）
        List<Map<String, Object>> negativeNews = newsMapper.selectNewsByCode(code, "negative", null, 10);
        // 最新中性新闻（最多5条）
        List<Map<String, Object>> neutralNews = newsMapper.selectNewsByCode(code, "neutral", null, 5);

        result.put("positiveNews", enrichNewsList(positiveNews, true));
        result.put("negativeNews", enrichNewsList(negativeNews, false));
        result.put("neutralNews", enrichNews(neutralNews));

        // 事件标签统计（近30天有标签的新闻）
        result.put("eventTags", buildEventTagStats(code, 30));

        // 新闻面评分（满分10分）
        result.put("newsScore", calcNewsScore(positive30d, negative30d, tagged30d, sentimentBias));

        return result;
    }

    /**
     * 按事件标签查询新闻
     * @param code 股票代码
     * @param eventTag 事件标签（如 PERFORMANCE）
     * @return 新闻列表
     */
    public List<Map<String, Object>> getNewsByTag(String code, String eventTag) {
        return enrichNews(newsMapper.selectNewsByEventTag(code, eventTag, 20));
    }

    /**
     * 获取个股新闻事件信号（供评分引擎使用）
     * @return map 含 score/newsSignal/detail
     */
    public Map<String, Object> getNewsSignal(String code) {
        Map<String, Object> analysis = getNewsAnalysis(code);

        Map<String, Object> signal = new LinkedHashMap<>();
        signal.put("score", analysis.get("newsScore"));
        signal.put("signal", analysis.get("sentimentBias"));
        signal.put("positive30d", analysis.get("positive30d"));
        signal.put("negative30d", analysis.get("negative30d"));
        signal.put("total30d", analysis.get("total30d"));
        signal.put("tagged30d", analysis.get("tagged30d"));
        signal.put("eventTags", analysis.get("eventTags"));

        // 简述
        int pos = ((Number) analysis.getOrDefault("positive30d", 0)).intValue();
        int neg = ((Number) analysis.getOrDefault("negative30d", 0)).intValue();
        String desc;
        if (pos > neg * 2) {
            desc = "近30天利好新闻偏多（" + pos + " vs " + neg + "）";
        } else if (neg > pos * 2) {
            desc = "近30天风险新闻偏多（" + neg + " vs " + pos + "）";
        } else if (pos > 0 || neg > 0) {
            desc = "近30天利好" + pos + "条，风险" + neg + "条，情绪中性";
        } else {
            desc = "近30天无重大新闻事件";
        }
        signal.put("detail", desc);

        return signal;
    }

    // ── 私有方法 ────────────────────────────────────────────────

    /** 丰富利好/风险新闻（加星级和简述） */
    private List<Map<String, Object>> enrichNewsList(List<Map<String, Object>> news, boolean positive) {
        for (Map<String, Object> item : news) {
            double score = ((Number) item.getOrDefault("sentiment_score", 0.0)).doubleValue();
            item.put("star", sentimentToStar(Math.abs(score)));
            item.put("summary", extractNewsSummary((String) item.get("title"), positive));
            // 事件标签中文
            String tag = (String) item.get("event_tag");
            if (tag != null && !tag.isEmpty()) {
                item.put("tagLabel", formatTagLabel(tag));
            }
        }
        return news;
    }

    /** 丰富中性新闻 */
    private List<Map<String, Object>> enrichNews(List<Map<String, Object>> news) {
        for (Map<String, Object> item : news) {
            String tag = (String) item.get("event_tag");
            if (tag != null && !tag.isEmpty()) {
                item.put("tagLabel", formatTagLabel(tag));
            }
        }
        return news;
    }

    /** 情感分数转星级（1-5星） */
    private int sentimentToStar(double score) {
        if (score >= 0.8) return 5;
        if (score >= 0.6) return 4;
        if (score >= 0.4) return 3;
        if (score >= 0.2) return 2;
        return 1;
    }

    /** 从标题提取一句话摘要 */
    private String extractNewsSummary(String title, boolean positive) {
        if (title == null || title.isEmpty()) return "";
        // 取标题前40字符
        return title.length() > 40 ? title.substring(0, 40) + "…" : title;
    }

    /** 事件标签中文格式化 */
    private String formatTagLabel(String tags) {
        if (tags == null || tags.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String tag : tags.split(",")) {
            String label = TAG_LABELS.getOrDefault(tag.trim(), tag.trim());
            if (sb.length() > 0) sb.append(" / ");
            sb.append(label);
        }
        return sb.toString();
    }

    /** 构建事件标签统计 */
    private List<Map<String, Object>> buildEventTagStats(String code, int days) {
        Map<String, Object> stats30d = newsMapper.selectNewsStats30d(code, days);
        List<Map<String, Object>> result = new ArrayList<>();
        List<Map<String, Object>> recentNews = newsMapper.selectLatestNews(code, 200);

        // 统计各标签出现次数
        Map<String, Integer> tagCount = new LinkedHashMap<>();
        for (Map<String, Object> news : recentNews) {
            String tag = (String) news.get("event_tag");
            if (tag != null && !tag.isEmpty()) {
                for (String t : tag.split(",")) {
                    tagCount.merge(t.trim(), 1, Integer::sum);
                }
            }
        }

        for (Map.Entry<String, Integer> e : tagCount.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tag", e.getKey());
            item.put("label", TAG_LABELS.getOrDefault(e.getKey(), e.getKey()));
            item.put("count", e.getValue());
            result.add(item);
        }
        return result;
    }

    /** 新闻面评分计算（满分10分） */
    private int calcNewsScore(int positive, int negative, int tagged, double sentimentBias) {
        int score = 0;
        // 近30天有新闻 +1
        if (positive + negative > 0) score += 1;
        // 利好新闻多 +3
        if (positive > negative) score += 3;
        // 利好远超风险 +2
        else if (positive > 0 && negative == 0) score += 2;
        // 有重大事件标签（PERFORMANCE/EXPANSION等） +2
        if (tagged > 0) score += 2;
        // 情感偏向强烈 +2
        if (sentimentBias > 0.5) score += 2;
        else if (sentimentBias < -0.5) score -= 1;
        return Math.max(0, Math.min(10, score));
    }
}
