package com.erp.schedule;

import com.erp.service.inv.BatchRecommendService;
import com.erp.service.inv.ExpiryReportService;
import com.erp.service.system.NoticeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 效期锁定每日扫描（spec outbound-strategy，BR-4.4-20 / C-4.4-03；design D4）。
 * 每日 00:30（与日结 00:05 错峰）全量重算 EXPIRY_LOCK_FLAG：过线置 1、回位清 0（双向自愈）；
 * 豁免与人工锁按 expiry-management 三类口径（design D2）。
 * 扫描只落标记位；推荐/试算侧另有实时计算兜底，出库过账拦截在引擎校验链第 ④ 条。
 * 扫描成功后追加生成当日效期预警报告（spec expiry-management 需求①，design D5）。
 */
@Slf4j
@Component
public class ExpiryLockScheduler {

    private final BatchRecommendService batchRecommendService;
    private final ExpiryReportService expiryReportService;
    private final NoticeService noticeService;

    public ExpiryLockScheduler(BatchRecommendService batchRecommendService,
                               ExpiryReportService expiryReportService,
                               NoticeService noticeService) {
        this.batchRecommendService = batchRecommendService;
        this.expiryReportService = expiryReportService;
        this.noticeService = noticeService;
    }

    /** 每日 00:30 扫描；失败通知运维（BR-4.4-18 通知范式复用） */
    @Scheduled(cron = "0 30 0 * * ?")
    public void dailyExpiryLockScan() {
        try {
            int changed = batchRecommendService.scanExpiryLock();
            log.info("expiry lock scan finished, {} batch(es) changed", changed);
        } catch (Exception e) {
            log.error("expiry lock scan failed", e);
            noticeService.push("ROLE_INTF_OPS", null,
                    "效期锁定扫描失败：" + LocalDate.now(),
                    "每日效期锁定扫描执行异常：" + e.getMessage()
                            + "。推荐侧仍有实时计算兜底，但标记位未刷新，请尽快排查。",
                    "EXPIRY_LOCK_SCAN", LocalDate.now().toString());
            return;   // 扫描失败不生成报告（数据未刷新，报告失真）
        }
        // 扫描后生成当日预警报告（失败独立通知，不影响次日）
        try {
            expiryReportService.generateDailyReport();
        } catch (Exception e) {
            log.error("expiry report generation failed", e);
            noticeService.push("ROLE_INTF_OPS", null,
                    "效期预警报告生成失败：" + LocalDate.now(),
                    "扫描已完成但报告生成异常：" + e.getMessage() + "。请尽快排查（可手工补生成）。",
                    "EXPIRY_REPORT_FAIL", LocalDate.now().toString());
        }
    }
}
