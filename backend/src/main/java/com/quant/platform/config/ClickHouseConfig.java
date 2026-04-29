package com.quant.platform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * ClickHouse 配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "clickhouse")
public class ClickHouseConfig {

    private String host = "localhost";

    private int port = 8123;

    private String database = "stock";

    private String username = "default";

    private String password = "123456";

    private boolean enabled = true;

    /**
     * 获取 JDBC URL
     * compress=0 禁用 LZ4 压缩，避免 native 库缺失问题
     */
    public String getJdbcUrl() {
        return String.format("jdbc:clickhouse://%s:%d/%s?user=%s&password=%s&compress=0",
                host, port, database, username, password);
    }
}
