package com.erp.mdm.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * MDM 本地缓存层（1.8 集成同步，业务逻辑4：TTL 30 分钟 + 事件驱动即时失效）。
 * 按 bizType 分桶：点查按 entityId 精准失效，列表/树按 bizType 整类失效。
 * 写路径直连不缓存。零依赖自实现（ConcurrentHashMap + 时间戳 TTL）。
 */
@Slf4j
@Component
public class MdmReadCache {

    private static final long TTL_MS = 30 * 60 * 1000L;

    private record Entry(Object value, long expireAt) {
    }

    private final Map<String, Map<String, Entry>> buckets = new ConcurrentHashMap<>();

    private Map<String, Entry> bucket(String bizType) {
        return buckets.computeIfAbsent(bizType.toUpperCase(), k -> new ConcurrentHashMap<>());
    }

    /** 点查缓存读取：命中返回缓存值，未命中执行 loader 并放入缓存（loader 返回 null 不缓存） */
    @SuppressWarnings("unchecked")
    public <T> T get(String bizType, String key, Supplier<T> loader) {
        Map<String, Entry> c = bucket(bizType);
        Entry e = c.get(key);
        if (e != null && System.currentTimeMillis() < e.expireAt()) {
            return (T) e.value();
        }
        if (e != null) {
            c.remove(key);
        }
        T loaded = loader.get();
        if (loaded != null) {
            c.put(key, new Entry(loaded, System.currentTimeMillis() + TTL_MS));
        }
        return loaded;
    }

    /** 事件到达：该类缓存整类失效（点查 entityId 精准删除后仍整清，保证列表/树一致） */
    public void invalidate(String bizType, String entityId) {
        Map<String, Entry> c = buckets.get(bizType == null ? "" : bizType.toUpperCase());
        if (c == null) {
            return;
        }
        if (entityId != null && !entityId.isBlank()) {
            c.remove(entityId);
        }
        c.clear();
        log.debug("mdm cache invalidated: {} entityId={}", bizType, entityId);
    }

    /** 缓存强刷（1.8.2 ADMIN 操作）：清全部 MDM 缓存桶 */
    public void invalidateAllTypes() {
        buckets.values().forEach(Map::clear);
    }

    /** 统计快照（1.8.2 展示用） */
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        buckets.forEach((k, c) -> {
            long now = System.currentTimeMillis();
            long alive = c.values().stream().filter(e -> now < e.expireAt()).count();
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("size", alive);
            out.put(k, s);
        });
        return out;
    }
}
