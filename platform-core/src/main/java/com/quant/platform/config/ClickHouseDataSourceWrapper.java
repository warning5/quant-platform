package com.quant.platform.config;

import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * ClickHouse DataSource 代理：在 HikariCP 之上叠加「不可达负缓存」。
 *
 * <p>背景：原先负缓存只写在 {@link ClickHouseConfig#getConnection()} 里，但 Spring JdbcTemplate
 * 直接调用底层 DataSource.getConnection()，会绕过该负缓存，导致 CH 不可达时 JdbcTemplate 查询
 * 每次仍阻塞到 connectionTimeout（实测每请求卡 5~10s）。本代理接管所有 getConnection() 调用
 * （无论来自 ClickHouseConfig 还是 JdbcTemplate），统一经过负缓存：窗口内已知不可达则直接
 * 快速失败，由上层 ClickHouseStockService 捕获并回退 MySQL，避免每次请求都阻塞到连接超时。</p>
 */
@Slf4j
public class ClickHouseDataSourceWrapper implements DataSource {

    private final DataSource delegate;

    /**
     * CH 不可达负缓存窗口（毫秒）。一旦探测到 CH 不可达，窗口内 getConnection() 直接快速失败，
     * 避免每次请求都阻塞到 connectionTimeout。窗口不宜过长：过期后才会重新探测，过长会拖慢
     * CH 恢复后的重新可用。
     */
    private static final long CH_UNAVAILABLE_CACHE_MS = 10_000L;
    private volatile boolean chAvailable = true;
    private volatile long chUnavailableSince = 0L;

    public ClickHouseDataSourceWrapper(DataSource delegate) {
        this.delegate = delegate;
    }

    @Override
    public Connection getConnection() throws SQLException {
        // 负缓存：窗口内已知不可达，直接快速失败触发降级
        if (!chAvailable && (System.currentTimeMillis() - chUnavailableSince) < CH_UNAVAILABLE_CACHE_MS) {
            throw new SQLException("ClickHouse 当前不可达（负缓存未过期），快速失败以触发 MySQL 降级");
        }
        try {
            Connection conn = delegate.getConnection();
            chAvailable = true;
            chUnavailableSince = 0L;
            return conn;
        } catch (Exception e) {
            // 覆盖 SQLException 与 ClickHouse 驱动层可能抛出的 RuntimeException：
            // 只要连不上就置负缓存，确保窗口内后续 getConnection() 直接快速失败走降级。
            chAvailable = false;
            chUnavailableSince = System.currentTimeMillis();
            log.warn("[ClickHouse] 获取连接失败，标记为不可用并走降级: {}", e.toString());
            if (e instanceof SQLException) {
                throw (SQLException) e;
            }
            throw new SQLException("ClickHouse 获取连接失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return getConnection();
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getLogger("ClickHouseDataSourceWrapper");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return delegate.isWrapperFor(iface);
    }
}
