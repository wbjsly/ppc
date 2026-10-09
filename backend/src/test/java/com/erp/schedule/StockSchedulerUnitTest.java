package com.erp.schedule;

import com.erp.service.inv.StockSnapshotService;
import com.erp.service.system.NoticeService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 调度器单测（spec stock-snapshot，任务 5.3）：不依赖真实 cron——
 * 直接调调度方法，断言重试计数、失败通知（ROLE_INTF_OPS）与异常不外抛。
 */
class StockSchedulerUnitTest {

    private final StockSnapshotService snapshotService = mock(StockSnapshotService.class);
    private final NoticeService noticeService = mock(NoticeService.class);

    /** 日结成功：调用一次、无失败通知 */
    @Test
    void dayCloseSuccessNoNotice() {
        when(snapshotService.runDayClose()).thenReturn(Map.of("inserted", 3, "skipped", false));
        StockDayCloseScheduler scheduler =
                new StockDayCloseScheduler(snapshotService, noticeService, 3);

        scheduler.dailyDayClose();

        verify(snapshotService, times(1)).runDayClose();
        verify(noticeService, never()).push(anyString(), any(), anyString(), anyString(),
                anyString(), anyString());
    }

    /** 日结连续失败：重试至 retryMax 次后通知运维（BR-4.4-18 / 偏差 D6） */
    @Test
    void dayCloseRetriesThenNotifiesOps() {
        when(snapshotService.runDayClose())
                .thenThrow(new RuntimeException("连接超时"));
        StockDayCloseScheduler scheduler =
                new StockDayCloseScheduler(snapshotService, noticeService, 3);

        assertDoesNotThrow(scheduler::dailyDayClose);

        verify(snapshotService, times(3)).runDayClose();
        ArgumentCaptor<String> role = ArgumentCaptor.forClass(String.class);
        verify(noticeService).push(role.capture(), isNull(), contains("日结失败"),
                contains("连续 3 次"), eq("STOCK_DAYCLOSE"), anyString());
        assertTrue(role.getValue().contains("ROLE_INTF_OPS"), role.getValue());
    }

    /** 校验调度：业务异常吞掉不外抛（次日重试），成功透传 */
    @Test
    void checkSwallowsException() {
        when(snapshotService.runCheckReport()).thenThrow(new RuntimeException("db down"));
        StockCheckScheduler scheduler = new StockCheckScheduler(snapshotService);
        assertDoesNotThrow(scheduler::dailyCheck);

        // 已桩 thenThrow 的方法重桩须用 doReturn（when() 会立即触发旧桩）
        org.mockito.Mockito.doReturn(Map.of("skipped", false))
                .when(snapshotService).runCheckReport();
        assertDoesNotThrow(scheduler::dailyCheck);
        verify(snapshotService, times(2)).runCheckReport();
    }
}
