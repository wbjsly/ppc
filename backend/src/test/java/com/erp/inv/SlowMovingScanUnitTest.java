package com.erp.inv;

import com.erp.schedule.StockDayCloseScheduler;
import com.erp.service.inv.InvReportService;
import com.erp.service.inv.StockSnapshotService;
import com.erp.service.system.NoticeService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 呆滞扫描调度挂接（change add-inventory-reports，spec 呆滞每日推送，任务 6.3）：
 * 日结成功后调用扫描；扫描异常不进日结重试、不影响日结任务（独立 try 隔离）。
 */
class SlowMovingScanUnitTest {

    @Test
    void scanRunsAfterDayClose() {
        StockSnapshotService snap = mock(StockSnapshotService.class);
        NoticeService notice = mock(NoticeService.class);
        InvReportService report = mock(InvReportService.class);
        when(snap.runDayClose()).thenReturn(java.util.Map.of("inserted", 3));
        StockDayCloseScheduler scheduler = new StockDayCloseScheduler(snap, notice, report, 3);

        scheduler.dailyDayClose();

        verify(snap, times(1)).runDayClose();
        verify(report, times(1)).dailySlowMovingScan();
        verify(notice, never()).push(anyString(), any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void scanFailureDoesNotBlockDayClose() {
        StockSnapshotService snap = mock(StockSnapshotService.class);
        NoticeService notice = mock(NoticeService.class);
        InvReportService report = mock(InvReportService.class);
        when(snap.runDayClose()).thenReturn(java.util.Map.of("inserted", 1));
        doThrow(new RuntimeException("scan boom")).when(report).dailySlowMovingScan();
        StockDayCloseScheduler scheduler = new StockDayCloseScheduler(snap, notice, report, 3);

        // 扫描异常被隔离：日结一次成功即返回（不触发重试、不发运维告警）
        assertDoesNotThrow(() -> scheduler.dailyDayClose());
        verify(snap, times(1)).runDayClose();
        verify(notice, never()).push(anyString(), any(), anyString(), anyString(), anyString(), any());
    }
}
