package com.erp.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.hr.HrSecondmentDao;
import com.erp.entity.hr.HrSecondment;
import com.erp.service.hr.HrSecondmentService;
import com.erp.service.hr.HrSecondmentVoucherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;

/**
 * 外派借调每日任务（add-employee-secondment）：
 * ①已批待生效单到生效日 → 生效执行（幂等）；
 * ②到期前 15 天返岗提醒 + 过期未返岗告警（02:50）；
 * ③每月 1 日 03:10 生成上月分摊凭证（BR-4.7-20，跑上月避开在途天数争议）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HrSecondmentDailyTask {

    private final HrSecondmentDao secDao;
    private final HrSecondmentService secondmentService;
    private final HrSecondmentVoucherService voucherService;

    /** 到期生效 + 提醒/告警扫描 */
    @Scheduled(cron = "0 50 2 * * ?")
    public void dailyScan() {
        // ① 已批待生效 → 到期执行（effect 幂等且自带未到生效日校验）
        for (HrSecondment s : secDao.selectList(new LambdaQueryWrapper<HrSecondment>()
                .eq(HrSecondment::getDelFlag, "0")
                .eq(HrSecondment::getStatus, HrSecondment.ST_APPROVED))) {
            try {
                secondmentService.effect(s.getId());
            } catch (Exception e) {
                // 未到生效日（ ServiceException 422）静默跳过；其他告警
                log.info("secondment effect skip/failed {}: {}", s.getSecNo(), e.getMessage());
            }
        }
        // ② 提醒 + 过期告警
        try {
            var out = secondmentService.remindScan();
            if (((Number) out.get("reminded")).intValue() > 0
                    || ((Number) out.get("expiredAlerted")).intValue() > 0) {
                log.info("secondment daily scan: {}", out);
            }
        } catch (Exception e) {
            log.error("secondment remind scan failed: {}", e.getMessage());
        }
    }

    /** 每月 1 日：生成上月分摊凭证（幂等锚点，重复触发安全） */
    @Scheduled(cron = "0 10 3 1 * ?")
    public void monthlyAllocation() {
        try {
            var out = voucherService.monthlyGenerate(YearMonth.now().minusMonths(1).toString().replace("-", ""));
            log.info("secondment monthly allocation: generated={} expiredSkipped={} failed={}",
                    out.get("generated"), out.get("skippedExpired"), out.get("failed"));
        } catch (Exception e) {
            log.error("secondment monthly allocation failed: {}", e.getMessage());
        }
    }
}
