package com.erp.inv;

import com.erp.schedule.ExpiryLockScheduler;
import com.erp.service.inv.BatchRecommendService;
import com.erp.service.inv.ExpiryReportService;
import com.erp.service.system.NoticeService;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 每日调度链单测（spec expiry-management 需求①，任务 3.2）：
 * 扫描成功 → 生成报告；扫描失败 → 通知运维且不生成报告；
 * 报告失败 → 独立通知运维（不影响次日）。
 */
class ExpirySchedulerUnitTest {

    @Test
    void scanSuccessTriggersReport() {
        BatchRecommendService scan = mock(BatchRecommendService.class);
        ExpiryReportService report = mock(ExpiryReportService.class);
        NoticeService notice = mock(NoticeService.class);
        when(scan.scanExpiryLock()).thenReturn(2);

        new ExpiryLockScheduler(scan, report, notice).dailyExpiryLockScan();

        verify(report).generateDailyReport();
        verify(notice, never()).push(eq("ROLE_INTF_OPS"), any(), anyString(), anyString(),
                anyString(), anyString());
    }

    @Test
    void scanFailureNotifiesOpsAndSkipsReport() {
        BatchRecommendService scan = mock(BatchRecommendService.class);
        ExpiryReportService report = mock(ExpiryReportService.class);
        NoticeService notice = mock(NoticeService.class);
        when(scan.scanExpiryLock()).thenThrow(new RuntimeException("db down"));

        new ExpiryLockScheduler(scan, report, notice).dailyExpiryLockScan();

        verify(notice).push(eq("ROLE_INTF_OPS"), any(), contains("效期锁定扫描失败"),
                anyString(), eq("EXPIRY_LOCK_SCAN"), anyString());
        verify(report, never()).generateDailyReport();
    }

    @Test
    void reportFailureNotifiesOpsIndependently() {
        BatchRecommendService scan = mock(BatchRecommendService.class);
        ExpiryReportService report = mock(ExpiryReportService.class);
        NoticeService notice = mock(NoticeService.class);
        when(scan.scanExpiryLock()).thenReturn(0);
        doThrow(new RuntimeException("serialize fail")).when(report).generateDailyReport();

        new ExpiryLockScheduler(scan, report, notice).dailyExpiryLockScan();

        verify(notice).push(eq("ROLE_INTF_OPS"), any(), contains("效期预警报告生成失败"),
                anyString(), eq("EXPIRY_REPORT_FAIL"), anyString());
    }
}
