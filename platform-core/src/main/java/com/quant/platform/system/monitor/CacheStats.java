package com.quant.platform.system.monitor;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

/**
 * 进程内缓存运行时统计（轻量级，不依赖 Micrometer）。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>hits / misses 用 {@link AtomicLong}，高并发读写无锁</li>
 *   <li>size 来自外部 ConcurrentHashMap（用 IntSupplier 注入），避免破坏封装</li>
 *   <li>lastLoadedAt / loaded 用 volatile 写，后台线程读 snapshot 一致即可</li>
 *   <li>{@link #snapshot()} 一次性快照所有指标返回不可变 record</li>
 * </ul>
 * 用法：缓存服务持有一个实例，get 路径上调 recordHit/miss，加载完成调 markLoaded()；
 * 监控面板通过 snapshot() 读取。
 */
public class CacheStats {

    private final String name;
    private final IntSupplier sizeSupplier;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private volatile long lastLoadedAt;
    private volatile boolean loaded;

    public CacheStats(String name, IntSupplier sizeSupplier) {
        this.name = name;
        this.sizeSupplier = sizeSupplier;
    }

    /** 缓存命中 */
    public void recordHit() {
        hits.incrementAndGet();
    }

    /** 缓存未命中（需要回源 DB） */
    public void recordMiss() {
        misses.incrementAndGet();
    }

    /** 标记一次加载完成（首次或 evict 后重新加载） */
    public void markLoaded() {
        lastLoadedAt = System.currentTimeMillis();
        loaded = true;
    }

    /** 标记全量失效（用于整体 clear 后置为未加载态；下次访问会再次 markLoaded） */
    public void markEvicted() {
        loaded = false;
    }

    public boolean isLoaded() {
        return loaded;
    }

    /** 取当前快照（命中/未命中/命中率/大小/最后加载时间/已加载状态） */
    public Snapshot snapshot() {
        long h = hits.get();
        long m = misses.get();
        long total = h + m;
        double hitRate = total == 0 ? 0.0 : (double) h / (double) total;
        int size = sizeSupplier == null ? 0 : Math.max(0, sizeSupplier.getAsInt());
        return new Snapshot(name, size, h, m, hitRate, lastLoadedAt, loaded);
    }

    public record Snapshot(
            String name,
            int size,
            long hits,
            long misses,
            double hitRate,
            long lastLoadedAt,
            boolean loaded
    ) {}
}
