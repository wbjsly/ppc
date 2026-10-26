package com.erp.service.impl.eam;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.eam.EamAssetCardDao;
import com.erp.dao.eam.EamRepairReportDao;
import com.erp.dao.eam.EamWorkOrderDao;
import com.erp.entity.eam.EamAssetCard;
import com.erp.entity.eam.EamRepairReport;
import com.erp.entity.eam.EamWorkOrder;
import com.erp.service.eam.EamRepairReportService;
import com.erp.service.eam.EamSafetyLockService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报修单（add-asset-maintenance，design D2，FR-4.13-3-7/8）。
 */
@Slf4j
@Service
public class EamRepairReportServiceImpl implements EamRepairReportService {

    private final EamRepairReportDao reportDao;
    private final EamWorkOrderDao woDao;
    private final EamAssetCardDao cardDao;
    private final EamSafetyLockService safetyLockService;
    private final com.erp.service.eam.EamWorkOrderService woService;

    public EamRepairReportServiceImpl(EamRepairReportDao reportDao,
                                      EamWorkOrderDao woDao,
                                      EamAssetCardDao cardDao,
                                      @Lazy EamSafetyLockService safetyLockService,
                                      @Lazy com.erp.service.eam.EamWorkOrderService woService) {
        this.reportDao = reportDao;
        this.woDao = woDao;
        this.cardDao = cardDao;
        this.safetyLockService = safetyLockService;
        this.woService = woService;
    }

    @Override
    public Page<EamRepairReport> page(long current, long size, String cardNo, String status) {
        LambdaQueryWrapper<EamRepairReport> qw = new LambdaQueryWrapper<EamRepairReport>()
                .like(cardNo != null && !cardNo.isBlank(), EamRepairReport::getCardNo, cardNo)
                .eq(status != null && !status.isBlank(), EamRepairReport::getStatus, status)
                .orderByDesc(EamRepairReport::getCreateDate);
        return reportDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    @Transactional
    public Map<String, Object> submit(EamRepairReport report) {
        if (report.getCardId() == null || report.getCardId().isBlank()) {
            throw new ServiceException(422, "资产卡片必选");
        }
        EamAssetCard card = cardDao.selectById(report.getCardId());
        if (card == null) {
            throw new ServiceException(422, "资产卡片不存在：" + report.getCardId());
        }
        if (report.getFaultSymptom() == null || report.getFaultSymptom().isBlank()) {
            throw new ServiceException(422, "故障现象必填");
        }
        if (report.getFaultTime() == null) {
            throw new ServiceException(422, "发现时间必填");
        }
        report.setReportNo(nextReportNo());
        report.setCardNo(card.getCardNo());
        report.setStatus("PENDING");
        report.setCreateBy(SecurityUtils.getCurrentUserId());
        reportDao.insert(report);

        // 安全风险 → 自动挂锁（FR-4.13-3-7 异常处理：同步触发安全停机流程）
        if ("1".equals(report.getSafetyRisk())) {
            safetyLockService.lock(report.getCardId(),
                    "报修单 " + report.getReportNo() + " 标记安全风险：" + report.getFaultSymptom(),
                    null);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("report", report);
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> convertToWorkOrder(String reportId, String assignee,
                                                  String resolvePlan, String outsourceVendor) {
        EamRepairReport report = reportDao.selectById(reportId);
        if (report == null) {
            throw new ServiceException(422, "报修单不存在：" + reportId);
        }
        if (!"PENDING".equals(report.getStatus())) {
            throw new ServiceException(409, "报修单当前状态不可转工单：" + report.getStatus());
        }
        if (assignee == null || assignee.isBlank()) {
            throw new ServiceException(422, "须指派维修人员");
        }
        if (resolvePlan == null || !List.of("SELF", "OUTSOURCE").contains(resolvePlan)) {
            throw new ServiceException(422, "维修方案必须为 SELF/OUTSOURCE");
        }
        if ("OUTSOURCE".equals(resolvePlan) && (outsourceVendor == null || outsourceVendor.isBlank())) {
            throw new ServiceException(422, "外协维修须填写外协单位");
        }
        // BR-4.13-16：安全停机未解除 → 不可派工（L1）
        safetyLockService.assertNoActiveLock(report.getCardId());
        // BR-4.13-19：同故障大修评估未归档 → 冻结同类工单创建
        woService.assertNoOverhaulFreeze(report.getCardId(), report.getFaultSymptom());

        EamWorkOrder wo = new EamWorkOrder();
        wo.setWoNo(nextWoNo());
        wo.setWoType("REPAIR");
        wo.setSrcType("REPORT");
        wo.setReportId(report.getId());
        wo.setCardId(report.getCardId());
        wo.setCardNo(report.getCardNo());
        wo.setFaultSymptom(report.getFaultSymptom());
        wo.setFaultTime(report.getFaultTime());
        wo.setImpactScope(report.getImpactScope());
        wo.setPlanDesc(report.getDetail());
        wo.setResolvePlan(resolvePlan);
        wo.setOutsourceVendor(outsourceVendor);
        wo.setAssignee(assignee);
        wo.setStatus("ASSIGNED");
        wo.setCreateBy(SecurityUtils.getCurrentUserId());
        woDao.insert(wo);

        EamRepairReport upd = new EamRepairReport();
        upd.setId(reportId);
        upd.setStatus("CONVERTED");
        upd.setWoNo(wo.getWoNo());
        upd.setConfirmBy(SecurityUtils.getCurrentUserId());
        upd.setConfirmDate(LocalDateTime.now());
        reportDao.updateById(upd);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("wo", wo);
        out.put("report", report);
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> reject(String reportId, String reason) {
        EamRepairReport report = reportDao.selectById(reportId);
        if (report == null) {
            throw new ServiceException(422, "报修单不存在：" + reportId);
        }
        if (!"PENDING".equals(report.getStatus())) {
            throw new ServiceException(409, "报修单当前状态不可驳回：" + report.getStatus());
        }
        if (reason == null || reason.isBlank()) {
            throw new ServiceException(422, "驳回原因必填");
        }
        report.setStatus("REJECTED");
        report.setRejectReason(reason);
        report.setConfirmBy(SecurityUtils.getCurrentUserId());
        report.setConfirmDate(LocalDateTime.now());
        reportDao.updateById(report);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("report", report);
        return out;
    }

    // ================= 内部 =================

    /** 报修单号 RB + yyyyMMdd + - + 4 位流水 */
    private String nextReportNo() {
        String prefix = "RB" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-";
        Integer max = reportDao.selectMaxSeq(prefix);
        return prefix + String.format("%04d", (max == null ? 0 : max) + 1);
    }

    /** 工单号 WO + yyyyMMdd + - + 4 位流水 */
    private String nextWoNo() {
        String prefix = "WO" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-";
        Integer max = woDao.selectMaxSeq(prefix);
        return prefix + String.format("%04d", (max == null ? 0 : max) + 1);
    }
}
