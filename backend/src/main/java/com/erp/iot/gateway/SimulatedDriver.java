package com.erp.iot.gateway;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * 进程内仿真驱动（add-iot-mobile proposal 偏差 1）：
 * 承载物理链路回环——帧发送即收到回环响应；证书校验（CERT-* 前缀且非 REVOKED）；
 * 可注入故障次数（断线重连退避测试）。
 * 未来接真实 broker 时仅替换本 Driver，适配器编解码逻辑不动。
 */
@Component
public final class SimulatedDriver {

    /** 单次注入故障计数（测试断线重连退避） */
    private final Map<String, AtomicInteger> failBudget = new ConcurrentHashMap<>();
    /** 累计重连尝试计数（测试观测） */
    private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();

    /** 回环发送：校验证书 → 模拟 1~2ms 时延 → 返回响应帧 */
    public Result roundTrip(String certSn, byte[] request) {
        if (certSn == null || !certSn.startsWith("CERT-") || certSn.contains("REVOKED")) {
            return Result.rejected("证书无效或已吊销（C-4.11-18 L1 拒绝）");
        }
        // 注入故障：消耗预算 → 模拟链路失败
        AtomicInteger budget = failBudget.computeIfAbsent(certSn, k -> new AtomicInteger(0));
        if (budget.get() > 0 && budget.decrementAndGet() >= 0) {
            return Result.timeout("链路故障（仿真注入）");
        }
        long t0 = System.nanoTime();
        // 回环时延（1~2ms 模拟网络）
        try {
            Thread.sleep(1L + (System.nanoTime() % 2));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long ackMs = Math.max(1, (System.nanoTime() - t0) / 1_000_000);
        // 响应帧：首字节 = 请求首字节 + 0x10（回执约定），尾部原样回环
        byte[] response = new byte[request.length + 1];
        response[0] = (byte) (request[0] + 0x10);
        System.arraycopy(request, 0, response, 1, request.length);
        return Result.ack(response, ackMs);
    }

    /** 注入 n 次链路故障（重连退避测试） */
    public void injectFailures(String certSn, int n) {
        failBudget.computeIfAbsent(certSn, k -> new AtomicInteger(0)).set(n);
    }

    /** 记录一次重连尝试 */
    public void markAttempt(String deviceNo) {
        attempts.computeIfAbsent(deviceNo, k -> new AtomicInteger(0)).incrementAndGet();
    }

    public int attemptsOf(String deviceNo) {
        AtomicInteger a = attempts.get(deviceNo);
        return a == null ? 0 : a.get();
    }

    /** 回环结果 */
    public record Result(boolean ok, byte[] frame, long ackMs, String error) {
        static Result ack(byte[] frame, long ms) {
            return new Result(true, frame, ms, null);
        }

        static Result rejected(String why) {
            return new Result(false, null, 0, why);
        }

        static Result timeout(String why) {
            return new Result(false, null, 0, why);
        }
    }
}
