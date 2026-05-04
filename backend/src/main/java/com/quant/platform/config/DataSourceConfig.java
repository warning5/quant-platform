package com.quant.platform.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * MySQL DataSource + JdbcTemplate 配置
 * 显式声明 @Primary 确保 MyBatis-Plus 和直接注入 JdbcTemplate 的组件使用 MySQL 而非 ClickHouse
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource dataSource(org.springframework.core.env.Environment env) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(env.getRequiredProperty("spring.datasource.url"));
        ds.setUsername(env.getRequiredProperty("spring.datasource.username"));
        ds.setPassword(env.getRequiredProperty("spring.datasource.password"));
        ds.setDriverClassName(env.getRequiredProperty("spring.datasource.driver-class-name"));
        return ds;
    }

    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(@Qualifier("dataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }
}
