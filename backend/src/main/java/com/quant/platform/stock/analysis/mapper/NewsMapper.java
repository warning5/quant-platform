package com.quant.platform.stock.analysis.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 新闻事件 Mapper（MySQL stock_news）
 */
@Mapper
public interface NewsMapper {

    /**
     * 查询个股新闻列表（支持过滤）
     * @param code 股票代码
     * @param newsType 情感类型（positive/negative/neutral，空=不限）
     * @param eventTag 事件标签前缀匹配（空=不限）
     * @param limit 返回条数上限
     * @return List<Map> 含 title/content/source/publish_date/news_type/sentiment_score/event_tag/url
     */
    List<Map<String, Object>> selectNewsByCode(
            @Param("code") String code,
            @Param("newsType") String newsType,
            @Param("eventTag") String eventTag,
            @Param("limit") int limit
    );

    /**
     * 查询利好新闻（positive）
     */
    default List<Map<String, Object>> selectPositiveNews(String code, int limit) {
        return selectNewsByCode(code, "positive", null, limit);
    }

    /**
     * 查询风险新闻（negative）
     */
    default List<Map<String, Object>> selectNegativeNews(String code, int limit) {
        return selectNewsByCode(code, "negative", null, limit);
    }

    /**
     * 按事件标签查询
     */
    List<Map<String, Object>> selectNewsByEventTag(
            @Param("code") String code,
            @Param("eventTag") String eventTag,
            @Param("limit") int limit
    );

    /**
     * 统计个股新闻汇总（用于评分）
     * @return map 含 positive_count/negative_count/neutral_count/total_count/latest_date
     */
    Map<String, Object> selectNewsSummary(@Param("code") String code);

    /**
     * 统计近N天利好/风险新闻数量（用于评分）
     * @param days 近N天
     */
    Map<String, Object> selectNewsStats30d(@Param("code") String code, @Param("days") int days);

    /**
     * 查询个股最新新闻（用于评分引擎）
     * @param limit 返回最近N条
     */
    List<Map<String, Object>> selectLatestNews(@Param("code") String code, @Param("limit") int limit);
}
