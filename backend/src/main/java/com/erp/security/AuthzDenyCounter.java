package com.erp.security;

import com.erp.service.AuthzIncidentService;
import com.erp.service.SysParamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 403 拒绝滑动窗口计数（add-authz-detection-console design D2/D3，spec authz-detection）：
 * 内存 5 分钟滚动窗口（重启丢窗口可接受）；阈值读系统参数（30s 缓存）；
 * 达阈值内联调 trigger（幂等由事件表活动行锚定）。
 * 两路汇聚：GlobalExceptionHandler 路 A（HTTP 200+body 403）与 AuthzDenyFilter 路 B（HTTP 403）。
 * hit() 全程 try-catch——计数失败绝不吞业务异常。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuthzDenyCounter {

    private final AuthzIncidentService incidentService;
    private final SysParamService sysParamService;

    private static final long WINDOW_MS = 5 * 60 * 1000L;
    private static final long THRESHOLD_CACHE_MS = 30_000L;

    private final ConcurrentHashMap<String, Deque<Long>> windows = new ConcurrentHashMap<>();
    private volatile int cachedThreshold = -1;
    private volatile long thresholdLoadedAt = 0;

    /** 记录一次 403 拒绝；达阈值触发越权事件（幂等） */
    public void hit(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            Deque<Long> dq = windows.computeIfAbsent(userId, k -> new ArrayDeque<>());
            synchronized (dq) {
                dq.addLast(now);
                long cutoff = now - WINDOW_MS;
                while (!dq.isEmpty() && dq.peekFirst() < cutoff) {
                    dq.pollFirst();
                }
                int hits = dq.size();
                int threshold = threshold();
                if (hits >= threshold) {
                    boolean fresh = incidentService.trigger(userId, hits, threshold);
                    if (fresh) {
                        log.warn("authz burst triggered: userId={} hits={} threshold={}", userId, hits, threshold);
                    }
                    // 触发后清窗：存续期幂等由事件表锚定，避免每 hit 都打 trigger 查询
                    dq.clear();
                }
            }
        } catch (Exception e) {
            log.warn("authz deny count failed (swallowed): {}", e.getMessage());
        }
    }

    private int threshold() {
        long now = System.currentTimeMillis();
        if (cachedThreshold < 0 || now - thresholdLoadedAt > THRESHOLD_CACHE_MS) {
            synchronized (this) {
                if (cachedThreshold < 0 || now - thresholdLoadedAt > THRESHOLD_CACHE_MS) {
                    cachedThreshold = sysParamService.getInt("AUTHZ_DENY_BURST_THRESHOLD", 10);
                    thresholdLoadedAt = now;
                }
            }
        }
        return cachedThreshold;
    }

    /** 测试用：清空计数窗口 */
    public void reset() {
        windows.clear();
    }
}
