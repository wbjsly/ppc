package com.erp.schedule;

import com.erp.service.inv.StockSnapshotService;
import com.erp.service.system.NoticeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 库存日结调度（spec stock-snapshot，FR-4.4-2-6 / BR-4.4-18；design D6）。
 * 每日 00:05 为昨日生成余额快照（插入即终态、按日幂等）；
 * 失败按 app.inv.dayclose-retry-max 重试，仍失败通知运维（ROLE_INTF_OPS，偏差 D6）。
 * 跨日过账门闩不在本轮（偏差 D4）。
 */
@Slf4j
@Component
public class StockDayCloseScheduler {

    private final StockSnapshotService stockSnapshotService;
    private final NoticeService noticeService;
    private final int retryMax;

    public StockDayCloseScheduler(StockSnapshotService stockSnapshotService,
                                  NoticeService noticeService,
                                  @Value("${app.inv.dayclose-retry-max:3}") int retryMax) {
        this.stockSnapshotService = stockSnapshotService;
        this.noticeService = noticeService;
        this.retryMax = retryMax;
    }

    /** 每日 00:05（BR-4.4-18 重试语义：进程内连续重试至 retryMax 次） */
    @Scheduled(cron = "0 5 0 * * ?")
    public void dailyDayClose() {
        Exception last = null;
        for (int attempt = 1; attempt <= Math.max(1, retryMax); attempt++) {
            try {
                stockSnapshotService.runDayClose();
                return;
            } catch (Exception e) {
                last = e;
                log.warn("stock day close attempt {}/{} failed: {}",
                        attempt, retryMax, e.getMessage());
            }
        }
        // 仍失败 → 通知运维 + error 日志（BR-4.4-18；跨日过账门闩记偏差 D4 后续）
        log.error("stock day close failed after {} attempts", retryMax, last);
        noticeService.push("ROLE_INTF_OPS", null,
                "库存日结失败：" + LocalDate.now(),
                "连续 " + retryMax + " 次执行失败：" + (last == null ? "未知异常" : last.getMessage())
                        + "。请排查后关注次日重跑。",
                "STOCK_DAYCLOSE", LocalDate.now().toString());
    }
}
