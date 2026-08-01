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
import java.util.Properties;

/**
 * ClickHouse 配置
 * 仅在 clickhouse.enabled=true 时创建 JdbcTemplate Bean
 *
 * 安全：密码通过 Properties 传递，不拼入 JDBC URL（避免日志泄露）
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "clickhouse")
public class ClickHouseConfig {

    private String host = "localhost";
    private int port = 8123;
    private String database = "stock";
    private String username = "default";
    private String password = "";  // 从 application.yml 读取（环境变量 CLICKHOUSE_PASSWORD）
    private boolean enabled = true;

    /**
     * 获取 JDBC URL（不含 user/password，避免日志泄露）
     * compress=0 禁用 LZ4 压缩，避免 native 库缺失问题
     * connection_timeout=30000 / socket_timeout=300000 防止长查询被中断
     */
    public String getJdbcUrl() {
        return String.format("jdbc:clickhouse://%s:%d/%s?compress=0&connection_timeout=30000&socket_timeout=300000",
                host, port, database);
    }

    @Bean
    @ConditionalOnProperty(name = "clickhouse.enabled", havingValue = "true")
    public DataSource clickHouseDataSource() throws java.sql.SQLException {
        Properties props = new Properties();
        props.setProperty("user", username);
        if (password != null && !password.isEmpty()) {
            props.setProperty("password", password);
        }
        return new ClickHouseDataSource(getJdbcUrl(), props);
    }

    @Bean
    @ConditionalOnProperty(name = "clickhouse.enabled", havingValue = "true")
    public JdbcTemplate clickHouseJdbcTemplate(@Qualifier("clickHouseDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }
}
