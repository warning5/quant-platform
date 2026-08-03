package com.quant.platform.config;

import com.clickhouse.jdbc.ClickHouseDataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

/**
 * ClickHouse 配置
 * 仅在 clickhouse.enabled=true 时创建 JdbcTemplate Bean
 *
 * 安全：密码通过 Properties 传递，不拼入 JDBC URL（避免日志泄露）
 * 连接池：使用 HikariCP 包装 ClickHouseDataSource，避免每次查询创建新连接
 */
@Slf4j
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

    /** HikariCP 连接池配置 */
    private int poolMaximumSize = 10;
    private int poolMinimumIdle = 2;
    private long poolConnectionTimeout = 30_000L;
    private long poolIdleTimeout = 600_000L;
    private long poolMaxLifetime = 1_800_000L;

    /** 池化 DataSource（由 Spring 注入，clickHouseDataSource Bean 创建后赋值） */
    private DataSource pooledDataSource;

    /**
     * 获取 JDBC URL（不含 user/password，避免日志泄露）
     * compress=0 禁用 LZ4 压缩，避免 native 库缺失问题
     * connection_timeout=30000 / socket_timeout=300000 防止长查询被中断
     */
    public String getJdbcUrl() {
        return String.format("jdbc:clickhouse://%s:%d/%s?compress=0&connection_timeout=30000&socket_timeout=300000",
                host, port, database);
    }

    /**
     * 创建 HikariCP 池化 DataSource（包装 ClickHouseDataSource）
     * <p>
     * ClickHouse JDBC 的 ClickHouseDataSource 本身不池化连接，每次 getConnection() 创建新 HTTP 连接。
     * HikariCP 在其上提供连接复用、空闲连接回收、连接泄漏检测等能力。
     */
    @Bean
    @ConditionalOnProperty(name = "clickhouse.enabled", havingValue = "true")
    public DataSource clickHouseDataSource() throws java.sql.SQLException {
        Properties props = new Properties();
        props.setProperty("user", username);
        if (password != null && !password.isEmpty()) {
            props.setProperty("password", password);
        }
        // 底层 ClickHouse DataSource（非池化）
        ClickHouseDataSource chDs = new ClickHouseDataSource(getJdbcUrl(), props);

        // HikariCP 池化配置
        HikariConfig hkConfig = new HikariConfig();
        hkConfig.setDataSource(chDs);
        hkConfig.setPoolName("ClickHouse-Pool");
        hkConfig.setMaximumPoolSize(poolMaximumSize);
        hkConfig.setMinimumIdle(poolMinimumIdle);
        hkConfig.setConnectionTimeout(poolConnectionTimeout);
        hkConfig.setIdleTimeout(poolIdleTimeout);
        hkConfig.setMaxLifetime(poolMaxLifetime);
        hkConfig.setConnectionTestQuery("SELECT 1");

        HikariDataSource poolingDs = new HikariDataSource(hkConfig);
        this.pooledDataSource = poolingDs;
        log.info("ClickHouse HikariCP 连接池已初始化: poolSize={}, minIdle={}, jdbcUrl={}",
                poolMaximumSize, poolMinimumIdle, getJdbcUrl());
        return poolingDs;
    }

    /**
     * 从连接池获取连接（供各 Service 调用）
     */
    public Connection getConnection() throws SQLException {
        if (pooledDataSource == null) {
            throw new SQLException("ClickHouse DataSource 未初始化（clickhouse.enabled=false 或 Bean 未创建）");
        }
        return pooledDataSource.getConnection();
    }

    @Bean
    @ConditionalOnProperty(name = "clickhouse.enabled", havingValue = "true")
    public JdbcTemplate clickHouseJdbcTemplate(@Qualifier("clickHouseDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }
}
