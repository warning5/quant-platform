package com.quant.platform.common.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LimitUpUtils 涨跌停阈值计算单元测试
 * 纯逻辑测试，不依赖外部服务
 */
@DisplayName("LimitUpUtils 涨跌停阈值")
class LimitUpUtilsTest {

    // ==================== isStName ====================

    @Test
    @DisplayName("isStName - ST 股票名称识别")
    void testIsStName() {
        assertTrue(LimitUpUtils.isStName("ST天宝"));
        assertTrue(LimitUpUtils.isStName("*ST康得"));
        assertTrue(LimitUpUtils.isStName("ST金刚"));
    }

    @Test
    @DisplayName("isStName - 非 ST 股票名称")
    void testIsStNameFalse() {
        assertFalse(LimitUpUtils.isStName("贵州茅台"));
        assertFalse(LimitUpUtils.isStName("中国平安"));
        assertFalse(LimitUpUtils.isStName(null));
        assertFalse(LimitUpUtils.isStName(""));
    }

    // ==================== getLimitUpThreshold ====================

    @Test
    @DisplayName("主板 - 普通 9.8%, ST 4.8%")
    void testMainBoardThreshold() {
        assertEquals(9.8, LimitUpUtils.getLimitUpThreshold("600001", null, false));
        assertEquals(4.8, LimitUpUtils.getLimitUpThreshold("600001", null, true));
        assertEquals(9.8, LimitUpUtils.getLimitUpThreshold("000001", null, false));
        assertEquals(4.8, LimitUpUtils.getLimitUpThreshold("000001.SZ", null, true));
    }

    @Test
    @DisplayName("科创板 688 - 始终 19.5%")
    void testStarMarketThreshold() {
        assertEquals(19.5, LimitUpUtils.getLimitUpThreshold("688001", null, false));
        assertEquals(19.5, LimitUpUtils.getLimitUpThreshold("688001", null, true), "科创板ST仍20%");
        assertEquals(19.5, LimitUpUtils.getLimitUpThreshold("688001.SH", null, false));
    }

    @Test
    @DisplayName("创业板 300/301 - 改革前 9.8%, 改革后 19.5%")
    void testGemThreshold() {
        LocalDate beforeReform = LocalDate.of(2020, 8, 23);
        LocalDate afterReform = LocalDate.of(2020, 8, 24);

        // 改革前
        assertEquals(9.8, LimitUpUtils.getLimitUpThreshold("300001", beforeReform, false));
        assertEquals(4.8, LimitUpUtils.getLimitUpThreshold("300001", beforeReform, true), "改革前创业板ST 5%");

        // 改革后
        assertEquals(19.5, LimitUpUtils.getLimitUpThreshold("300001", afterReform, false));
        assertEquals(19.5, LimitUpUtils.getLimitUpThreshold("300001", afterReform, true), "改革后创业板ST仍20%");

        // 301 开头
        assertEquals(19.5, LimitUpUtils.getLimitUpThreshold("301001", afterReform, false));
    }

    @Test
    @DisplayName("北交所 - 始终 29.5%")
    void testBseThreshold() {
        assertEquals(29.5, LimitUpUtils.getLimitUpThreshold("430001", null, false));
        assertEquals(29.5, LimitUpUtils.getLimitUpThreshold("830001", null, false));
        assertEquals(29.5, LimitUpUtils.getLimitUpThreshold("870001", null, false));
        assertEquals(29.5, LimitUpUtils.getLimitUpThreshold("920001", null, false));
    }

    // ==================== getLimitDownThreshold ====================

    @Test
    @DisplayName("跌停阈值为负值")
    void testLimitDownThreshold() {
        assertEquals(-9.8, LimitUpUtils.getLimitDownThreshold("600001", null, false));
        assertEquals(-4.8, LimitUpUtils.getLimitDownThreshold("600001", null, true));
        assertEquals(-19.5, LimitUpUtils.getLimitDownThreshold("688001", null, false));
        assertEquals(-29.5, LimitUpUtils.getLimitDownThreshold("430001", null, false));
    }

    // ==================== isLimitUp / isLimitDown ====================

    @Test
    @DisplayName("isLimitUp - 主板涨停")
    void testIsLimitUp() {
        assertTrue(LimitUpUtils.isLimitUp(9.9, "600001", null, false));
        assertTrue(LimitUpUtils.isLimitUp(10.02, "600001", null, false));
        assertFalse(LimitUpUtils.isLimitUp(9.7, "600001", null, false));
    }

    @Test
    @DisplayName("isLimitUp - ST 股涨停")
    void testIsLimitUpSt() {
        assertTrue(LimitUpUtils.isLimitUp(4.9, "600001", null, true));
        assertFalse(LimitUpUtils.isLimitUp(4.7, "600001", null, true));
    }

    @Test
    @DisplayName("isLimitDown - 跌停")
    void testIsLimitDown() {
        assertTrue(LimitUpUtils.isLimitDown(-9.9, "600001", null, false));
        assertTrue(LimitUpUtils.isLimitDown(-10.0, "600001", null, false));
        assertFalse(LimitUpUtils.isLimitDown(-9.7, "600001", null, false));
    }

    @Test
    @DisplayName("GEM_REFORM_DATE 常量正确")
    void testGemReformDate() {
        assertEquals(LocalDate.of(2020, 8, 24), LimitUpUtils.GEM_REFORM_DATE);
    }
}
