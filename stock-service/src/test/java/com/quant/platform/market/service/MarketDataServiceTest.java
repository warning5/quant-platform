package com.quant.platform.market.service;

import com.quant.platform.stock.mapper.StockInfoMapper;
import com.quant.platform.stock.service.ClickHouseStockService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * P1/T4 校验：codeMarketMap / codeMarketCapMap 必须声明为 volatile 且初始化为 ConcurrentHashMap，
 * 保证 @PostConstruct 单写、运行期多读的发布可见性。
 */
class MarketDataServiceTest {

    @Test
    void marketMapsAreVolatileConcurrentHashMap() throws Exception {
        MarketDataService service = new MarketDataService(
                mock(ClickHouseStockService.class),
                mock(StockInfoMapper.class),
                mock(JdbcTemplate.class));

        assertVolatileConcurrentHashMap(service, "codeMarketMap");
        assertVolatileConcurrentHashMap(service, "codeMarketCapMap");
    }

    private void assertVolatileConcurrentHashMap(Object target, String fieldName) throws Exception {
        Field field = MarketDataService.class.getDeclaredField(fieldName);
        field.setAccessible(true);

        assertTrue(Modifier.isVolatile(field.getModifiers()),
                fieldName + " 应声明为 volatile（发布可见性）");
        Object value = field.get(target);
        assertNotNull(value, fieldName + " 不应为 null");
        assertTrue(value instanceof ConcurrentHashMap,
                fieldName + " 应初始化为 ConcurrentHashMap，实际为 " + value.getClass().getName());
    }
}
