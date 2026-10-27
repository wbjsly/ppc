package com.erp.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.pm.PmIpAgreementDao;
import com.erp.dao.pm.PmIpAssetDao;
import com.erp.dao.pm.PmProjectDao;
import com.erp.entity.pm.PmIpAgreement;
import com.erp.entity.pm.PmIpAsset;
import com.erp.entity.pm.PmProject;
import com.erp.service.system.NoticeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 知识产权每日扫描（add-ip-management，04:30，design D5 两合一）。
 * A) 存量 ACTIVE 项目无生效 IP 协议 → L4 提醒 PMO+法务补签（BR-4.8-24 存量口径，不回溯卡控）。
 * B) 保护期届满 <= 90 天 → L4 提醒研发负责人+法务（续展/放弃决策）。
 * 去重：NoticeService.push 按 BIZ_TYPE+BIZ_ID 未读去重；补签 bizId 带日期（每日一次），
 * 届满 bizId 带月份（每月一次），避免每日轰炸。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PmIpDailyTask {

    /** 保护期届满提醒提前天数 */
    private static final int EXPIRE_WARN_DAYS = 90;

    private final PmProjectDao projectDao;
    private final PmIpAgreementDao agreementDao;
    private final PmIpAssetDao assetDao;
    private final NoticeService noticeService;

    @Scheduled(cron = "0 30 4 * * ?")
    public void run() {
        try {
            remindMissingAgreement();
        } catch (Exception e) {
            log.error("ip missing-agreement remind failed", e);
        }
        try {
            remindExpiring();
        } catch (Exception e) {
            log.error("ip expire remind failed", e);
        }
    }

    /** A) 存量 ACTIVE 项目无生效协议 → 提醒补签 */
    private void remindMissingAgreement() {
        LocalDate today = LocalDate.now();
        String dayKey = today.format(DateTimeFormatter.BASIC_ISO_DATE);
        List<PmProject> actives = projectDao.selectList(new LambdaQueryWrapper<PmProject>()
                .eq(PmProject::getDelFlag, "0")
                .eq(PmProject::getStatus, PmProject.ST_ACTIVE));
        for (PmProject p : actives) {
            Long has = agreementDao.selectCount(new LambdaQueryWrapper<PmIpAgreement>()
                    .eq(PmIpAgreement::getProjectId, p.getId())
                    .eq(PmIpAgreement::getEffective, "1")
                    .eq(PmIpAgreement::getDelFlag, "0"));
            if (has == null || has == 0) {
                noticeService.push("ROLE_PMO", null,
                        "项目缺 IP 归属协议：" + p.getName(),
                        "在项项目「" + p.getName() + "」尚未签订 IP 归属协议，成果登记将受影响，请法务补签（BR-4.8-24）",
                        "PmIpMissingAgreement", p.getId() + ":" + dayKey);
                noticeService.push("ROLE_LEGAL", null,
                        "项目缺 IP 归属协议：" + p.getName(),
                        "在项项目「" + p.getName() + "」尚未签订 IP 归属协议，请尽快补签",
                        "PmIpMissingAgreementLegal", p.getId() + ":" + dayKey);
            }
        }
    }

    /** B) 保护期届满 <= 90 天 → 提醒续展 */
    private void remindExpiring() {
        LocalDate today = LocalDate.now();
        LocalDate limit = today.plusDays(EXPIRE_WARN_DAYS);
        String monthKey = today.format(DateTimeFormatter.ofPattern("yyyyMM"));
        List<PmIpAsset> expiring = assetDao.selectList(new LambdaQueryWrapper<PmIpAsset>()
                .eq(PmIpAsset::getDelFlag, "0")
                .isNotNull(PmIpAsset::getProtectExpireDate)
                .le(PmIpAsset::getProtectExpireDate, limit)
                .ge(PmIpAsset::getProtectExpireDate, today));
        for (PmIpAsset a : expiring) {
            long daysLeft = java.time.temporal.ChronoUnit.DAYS.between(today, a.getProtectExpireDate());
            noticeService.push("ROLE_RD_LEAD", null,
                    "IP 保护期将届满：" + a.getName(),
                    "成果「" + a.getName() + "」（" + a.getType() + "）保护期将于 "
                            + a.getProtectExpireDate() + " 届满（剩 " + daysLeft + " 天），请评估续展或放弃",
                    "PmIpExpiring", a.getId() + ":" + monthKey);
            noticeService.push("ROLE_LEGAL", null,
                    "IP 保护期将届满：" + a.getName(),
                    "成果「" + a.getName() + "」保护期将于 " + a.getProtectExpireDate() + " 届满，请协助评估",
                    "PmIpExpiringLegal", a.getId() + ":" + monthKey);
        }
    }
}
