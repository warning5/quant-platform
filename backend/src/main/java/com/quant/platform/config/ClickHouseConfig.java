package com.quant.platform.config;

import com.clickhouse.jdbc.ClickHouseDataSource;
import lombok.Data;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * ClickHouse 配置
 * 仅在 clickhouse.enabled=true 时创建 JdbcTemplate Bean
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
     * connection_timeout=30000 / socket_timeout=300000 防止长查询被中断
     */
    public String getJdbcUrl() {
        return String.format("jdbc:clickhouse://%s:%d/%s?user=%s&password=%s&compress=0&connection_timeout=30000&socket_timeout=300000",
                host, port, database, username, password);
    }

    @Bean
    @ConditionalOnProperty(name = "clickhouse.enabled", havingValue = "true")
    public DataSource clickHouseDataSource() throws java.sql.SQLException {
        return new ClickHouseDataSource(getJdbcUrl());
    }

    @Bean
    @ConditionalOnProperty(name = "clickhouse.enabled", havingValue = "true")
    public JdbcTemplate clickHouseJdbcTemplate(@Qualifier("clickHouseDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }
}
