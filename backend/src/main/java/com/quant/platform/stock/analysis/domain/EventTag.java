package com.quant.platform.stock.analysis.domain;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 新闻事件标签枚举
 * 用于 NewsEventParser 的 LLM 解析结果分类，以及策略层面的事件筛选。
 *
 * <p>方向(direction)说明：
 * <ul>
 *   <li>BULLISH - 利好</li>
 *   <li>BEARISH - 利空</li>
 *   <li>NEUTRAL - 中性</li>
 * </ul>
 */
public enum EventTag {

    BUYBACK("BUYBACK", "回购", Direction.BULLISH),
    INCREASE("INCREASE", "增持", Direction.BULLISH),
    DECREASE("DECREASE", "减持", Direction.BEARISH),
    EARN_PRE("EARN_PRE", "业绩预增", Direction.BULLISH),
    EARN_WARN("EARN_WARN", "业绩预减/预警", Direction.BEARISH),
    EARN_BEAT("EARN_BEAT", "超预期", Direction.BULLISH),
    EARN_MISS("EARN_MISS", "不及预期", Direction.BEARISH),
    RESTRUCT("RESTRUCT", "重组/并购", Direction.NEUTRAL),
    UNLOCK("UNLOCK", "解禁", Direction.BEARISH),
    INCENTIVE("INCENTIVE", "股权激励", Direction.BULLISH),
    DIVIDEND("DIVIDEND", "分红", Direction.BULLISH),
    OTHER("OTHER", "其他", Direction.NEUTRAL);

    /** 存入 DB event_tag 列的值 */
    private final String code;
    /** 中文标签名 */
    private final String label;
    /** 利好/利空/中性方向 */
    private final Direction direction;

    EventTag(String code, String label, Direction direction) {
        this.code = code;
        this.label = label;
        this.direction = direction;
    }

    public String getCode() { return code; }
    public String getLabel() { return label; }
    public Direction getDirection() { return direction; }

    public boolean isBullish() { return direction == Direction.BULLISH; }
    public boolean isBearish() { return direction == Direction.BEARISH; }

    // ── 静态查找 ──────────────────────────────────────────

    /** 所有合法的 code 值集合 */
    private static final Set<String> VALID_CODES = Arrays.stream(values())
            .map(EventTag::getCode)
            .collect(Collectors.toUnmodifiableSet());

    /** 利好标签集合 */
    private static final Set<EventTag> BULLISH = Arrays.stream(values())
            .filter(EventTag::isBullish)
            .collect(Collectors.toUnmodifiableSet());

    /** 利空标签集合 */
    private static final Set<EventTag> BEARISH = Arrays.stream(values())
            .filter(EventTag::isBearish)
            .collect(Collectors.toUnmodifiableSet());

    /** 利好标签 code 集合（供 DB 查询比对） */
    private static final Set<String> BULLISH_CODES = BULLISH.stream()
            .map(EventTag::getCode)
            .collect(Collectors.toUnmodifiableSet());

    /** 利空标签 code 集合（供 DB 查询比对） */
    private static final Set<String> BEARISH_CODES = BEARISH.stream()
            .map(EventTag::getCode)
            .collect(Collectors.toUnmodifiableSet());

    /**
     * 根据 code 字符串查找枚举值，找不到返回 OTHER
     */
    public static EventTag fromCode(String code) {
        if (code == null || code.isEmpty()) return OTHER;
        for (EventTag tag : values()) {
            if (tag.code.equals(code)) return tag;
        }
        return OTHER;
    }

    /** code 是否在合法白名单内 */
    public static boolean isValidCode(String code) {
        return VALID_CODES.contains(code);
    }

    public static Set<String> getValidCodes() { return VALID_CODES; }
    public static Set<String> getBullishCodes() { return BULLISH_CODES; }
    public static Set<String> getBearishCodes() { return BEARISH_CODES; }

    /**
     * 生成 LLM Prompt 中可用的标签说明文本
     * 例如: "- BUYBACK: 回购（公司回购股票）\n- INCREASE: 增持（股东/高管增持）\n..."
     */
    public static String toPromptText() {
        StringBuilder sb = new StringBuilder();
        for (EventTag tag : values()) {
            sb.append("- ").append(tag.code).append(": ").append(tag.getLabel());
            // 补充说明
            String hint = switch (tag) {
                case BUYBACK -> "（公司回购股票）";
                case INCREASE -> "（股东/高管增持）";
                case DECREASE -> "（股东/高管减持）";
                case EARN_PRE -> "（业绩预告/快报显示增长）";
                case EARN_BEAT -> "（实际业绩超市场预期）";
                case UNLOCK -> "（限售股解禁）";
                case DIVIDEND -> "（高送转/分红预案）";
                case OTHER -> "其他无法归类的";
                default -> "";
            };
            sb.append(hint).append('\n');
        }
        return sb.toString();
    }

    // ── 内部枚举 ──────────────────────────────────────────

    /** 方向 */
    public enum Direction {
        BULLISH, BEARISH, NEUTRAL
    }
}
