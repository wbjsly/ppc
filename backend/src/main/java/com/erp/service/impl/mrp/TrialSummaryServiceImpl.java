package com.erp.service.impl.mrp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.mrp.MrpMoDao;
import com.erp.dao.mrp.MrpMoOpDao;
import com.erp.dao.mrp.MrpReportingDao;
import com.erp.dao.mrp.TrialDataDao;
import com.erp.dao.mrp.TrialSummaryDao;
import com.erp.dao.system.ApprovalInstanceDao;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpMoOp;
import com.erp.entity.mrp.TrialData;
import com.erp.entity.mrp.TrialSummary;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.mrp.ReportingService;
import com.erp.service.mrp.TrialSummaryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 试产总结单实现（change add-trial-production-management，design D10）。
 * 编号 TS-YYYYMMDD-NNN；指标服务端计算（YIELD = 合格÷报工投入、
 * HOURS_ACHIEVE = 标准工时Σ÷实测工时Σ），请求体指标字段忽略；
 * TrialReview 单 SEQ 三并行节点（工艺/质量/计划）全部通过才 APPROVED。
 */
@Slf4j
@Service
public class TrialSummaryServiceImpl implements TrialSummaryService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final TrialSummaryDao summaryDao;
    private final MrpMoDao moDao;
    private final MrpMoOpDao moOpDao;
    private final TrialDataDao trialDataDao;
    private final ReportingService reportingService;
    private final ApprovalInstanceDao approvalInstanceDao;
    private final ApprovalEngine approvalEngine;
    private final com.erp.service.SysParamService sysParam;

    public TrialSummaryServiceImpl(TrialSummaryDao summaryDao, MrpMoDao moDao,
                                   MrpMoOpDao moOpDao, TrialDataDao trialDataDao,
                                   @org.springframework.context.annotation.Lazy ReportingService reportingService,
                                   ApprovalInstanceDao approvalInstanceDao,
                                   @org.springframework.context.annotation.Lazy ApprovalEngine approvalEngine,
                                   com.erp.service.SysParamService sysParam) {
        this.summaryDao = summaryDao;
        this.moDao = moDao;
        this.moOpDao = moOpDao;
        this.trialDataDao = trialDataDao;
        this.reportingService = reportingService;
        this.approvalInstanceDao = approvalInstanceDao;
        this.approvalEngine = approvalEngine;
        this.sysParam = sysParam;
    }

    @Override
    @Transactional
    public TrialSummary save(TrialSummary head) {
        requireWriter("保存试产总结");
        if (head == null || head.getMoId() == null || head.getMoId().isBlank()) {
            throw new ServiceException(422, "试产工单 ID 必填");
        }
        MrpMo mo = moDao.selectById(head.getMoId());
        if (mo == null || !MrpMo.MO_TYPE_TRIAL.equals(mo.getMoType())) {
            throw new ServiceException(422, "仅试产工单可建总结单");
        }
        TrialSummary row = byMo(mo.getId());
        boolean created = row == null;
        if (created) {
            row = new TrialSummary();
            row.setMoId(mo.getId());
            row.setMoNo(mo.getMoNo());
            row.setTrialStage(mo.getTrialStage());
            row.setStatus(TrialSummary.ST_DRAFT);
        } else if (!TrialSummary.ST_DRAFT.equals(row.getStatus())
                && !TrialSummary.ST_REJECTED.equals(row.getStatus())) {
            throw new ServiceException(422,
                    "仅草稿/已驳回状态可修改（当前 " + row.getStatus() + "）");
        }

        // 指标服务端计算（spec「禁手填」——head 携带的指标字段一律忽略）
        recomputeMetrics(row, mo);
        row.setIssues(head.getIssues() == null ? row.getIssues() : head.getIssues());
        if (row.getStatus() == null) {
            row.setStatus(TrialSummary.ST_DRAFT);
        }
        if (created) {
            insertResolvingConflict(row);
            log.info("试产总结建档：{} 工单={} 良率={}", row.getSummaryNo(), mo.getMoNo(), row.getYieldPct());
        } else {
            summaryDao.updateById(row);
        }
        return row;
    }

    @Override
    public TrialSummary get(String summaryNo) {
        if (summaryNo == null || summaryNo.isBlank()) {
            throw new ServiceException(422, "总结单号必填");
        }
        TrialSummary row = summaryDao.selectOne(new LambdaQueryWrapper<TrialSummary>()
                .eq(TrialSummary::getSummaryNo, summaryNo.trim()).last("LIMIT 1"));
        if (row == null) {
            throw new ServiceException(422, "试产总结单不存在：" + summaryNo);
        }
        return row;
    }

    @Override
    public TrialSummary byMo(String moId) {
        return summaryDao.selectOne(new LambdaQueryWrapper<TrialSummary>()
                .eq(TrialSummary::getMoId, moId).last("LIMIT 1"));
    }

    @Override
    @Transactional
    public TrialSummary submit(String summaryNo) {
        requireWriter("提交试产总结评审");
        TrialSummary row = get(summaryNo);
        if (TrialSummary.ST_PENDING.equals(row.getStatus())) {
            throw new ServiceException(422, "总结单重复提交（当前 PENDING）：" + summaryNo);
        }
        if (!TrialSummary.ST_DRAFT.equals(row.getStatus())
                && !TrialSummary.ST_REJECTED.equals(row.getStatus())) {
            throw new ServiceException(422,
                    "仅草稿/已驳回状态可提交评审（当前 " + row.getStatus() + "）：" + summaryNo);
        }
        // 提交时按最新数据刷新指标（阶段闸口以提交态为准）
        MrpMo mo = moDao.selectById(row.getMoId());
        if (mo != null) {
            recomputeMetrics(row, mo);
        }
        // PVT 良率闸口（design D10：YIELD < TRIAL_PVT_YIELD_MIN → 422）
        if ("PVT".equals(row.getTrialStage())) {
            BigDecimal min = new BigDecimal(sysParam.getValue("TRIAL_PVT_YIELD_MIN", "95"));
            if (row.getYieldPct() == null || row.getYieldPct().compareTo(min) < 0) {
                throw new ServiceException(422, "PVT 良率未达标：当前 "
                        + pct(row.getYieldPct()) + "%，要求 ≥ " + min.stripTrailingZeros().toPlainString()
                        + "%（TRIAL_PVT_YIELD_MIN），不得提交转量产评审");
            }
        }
        // 幂等：已有 PENDING 实例直接复用
        ApprovalInstance pending = approvalInstanceDao.selectOne(
                new LambdaQueryWrapper<ApprovalInstance>()
                        .eq(ApprovalInstance::getBizType, BIZ_TRIAL_REVIEW)
                        .eq(ApprovalInstance::getBizId, summaryNo)
                        .eq(ApprovalInstance::getStatus, "PENDING")
                        .last("LIMIT 1"));
        // 单 SEQ 三并行节点（design D10：内层同组 = 并行，全部通过才 APPROVED）
        ApprovalInstance inst = pending != null ? pending : approvalEngine.submit(
                BIZ_TRIAL_REVIEW, summaryNo,
                "试产总结评审：" + summaryNo + "（" + row.getTrialStage() + " "
                        + row.getMoNo() + " 良率 " + pct(row.getYieldPct()) + "%）",
                null, List.of(List.of(
                        ApprovalNodeSpec.sign("ROLE_PROCESS_MGR", "工艺主管"),
                        ApprovalNodeSpec.sign("ROLE_QUALITY_MGR", "质量主管"),
                        ApprovalNodeSpec.sign("ROLE_PLAN_MGR", "计划主管"))));
        summaryDao.update(null, new LambdaUpdateWrapper<TrialSummary>()
                .eq(TrialSummary::getId, row.getId())
                .in(TrialSummary::getStatus, TrialSummary.ST_DRAFT, TrialSummary.ST_REJECTED)
                .set(TrialSummary::getStatus, TrialSummary.ST_PENDING)
                .set(TrialSummary::getApprovalId, inst.getId())
                .set(TrialSummary::getSubmitBy, currentOperator())
                .set(TrialSummary::getSubmitAt, LocalDateTime.now()));
        log.info("试产总结提交评审：{} apprNo={} 良率={}", summaryNo, inst.getApprNo(), row.getYieldPct());
        return get(summaryNo);
    }

    @Override
    public List<TrialSummary> list(String trialStage, String status) {
        requireWriter("查询试产总结");
        return summaryDao.selectList(new LambdaQueryWrapper<TrialSummary>()
                .eq(trialStage != null && !trialStage.isBlank(), TrialSummary::getTrialStage,
                        trialStage == null ? null : trialStage.trim().toUpperCase())
                .eq(status != null && !status.isBlank(), TrialSummary::getStatus, status)
                .orderByDesc(TrialSummary::getCreateDate));
    }

    @Override
    @Transactional
    public Map<String, Object> onApproval(String summaryNo, boolean approved, String opinion,
                                          String operator) {
        TrialSummary row = get(summaryNo);
        if (!TrialSummary.ST_PENDING.equals(row.getStatus())) {
            return Map.of("summaryNo", summaryNo, "status", row.getStatus(), "idempotent", true);
        }
        if (approved) {
            summaryDao.update(null, new LambdaUpdateWrapper<TrialSummary>()
                    .eq(TrialSummary::getId, row.getId())
                    .eq(TrialSummary::getStatus, TrialSummary.ST_PENDING)
                    .set(TrialSummary::getStatus, TrialSummary.ST_APPROVED)
                    .set(TrialSummary::getApproveBy, operator)
                    .set(TrialSummary::getApproveAt, LocalDateTime.now()));
            log.info("试产总结评审通过：{} by {}", summaryNo, operator);
        } else {
            summaryDao.update(null, new LambdaUpdateWrapper<TrialSummary>()
                    .eq(TrialSummary::getId, row.getId())
                    .eq(TrialSummary::getStatus, TrialSummary.ST_PENDING)
                    .set(TrialSummary::getStatus, TrialSummary.ST_REJECTED)
                    .set(TrialSummary::getRejectReason, opinion == null ? "" : opinion));
            log.info("试产总结评审驳回：{} 意见={} by {}", summaryNo, opinion, operator);
        }
        return Map.of("summaryNo", summaryNo, "approved", approved);
    }

    // ================= 私有助手 =================

    /**
     * 指标重算（spec「服务端计算忽略客户端值」）：
     * YIELD = 合格产出 ÷ 报工投入 × 100（末道工序累计，5.7 口径 goodQty/inputQty）；
     * HOURS_ACHIEVE = 标准工时Σ ÷ 实测工时Σ × 100（工序快照 vs 采集表）。
     */
    private void recomputeMetrics(TrialSummary row, MrpMo mo) {
        List<MrpMoOp> ops = moOpDao.selectList(new LambdaQueryWrapper<MrpMoOp>()
                .eq(MrpMoOp::getMoId, mo.getId()).orderByDesc(MrpMoOp::getOpSeq));
        Integer lastOp = ops.isEmpty() ? null : ops.get(0).getOpSeq();
        Map<String, Object> totals = lastOp == null
                ? Map.of("goodQty", BigDecimal.ZERO, "inputQty", BigDecimal.ZERO)
                : reportingService.confirmedTotals(mo.getId(), lastOp);
        BigDecimal good = (BigDecimal) totals.get("goodQty");
        BigDecimal input = (BigDecimal) totals.get("inputQty");
        row.setQualifiedQty(good == null ? BigDecimal.ZERO : good);
        row.setInputQty(input == null ? BigDecimal.ZERO : input);
        row.setYieldPct(divPct(row.getQualifiedQty(), row.getInputQty()));

        BigDecimal stdHours = BigDecimal.ZERO;
        for (MrpMoOp op : ops) {
            stdHours = stdHours.add(nvl(op.getSetupHours())).add(nvl(op.getRunHours()));
        }
        BigDecimal actualHours = BigDecimal.ZERO;
        for (TrialData d : trialDataDao.selectList(new LambdaQueryWrapper<TrialData>()
                .eq(TrialData::getMoId, mo.getId()))) {
            actualHours = actualHours.add(nvl(d.getActualHours()));
        }
        row.setStdHours(stdHours);
        row.setActualHours(actualHours);
        row.setHoursAchievePct(divPct(stdHours, actualHours));
    }

    /** 百分比（分母 0 → null，前端显示「—」） */
    private static BigDecimal divPct(BigDecimal num, BigDecimal den) {
        if (den == null || den.signum() == 0) {
            return null;
        }
        return num.multiply(new BigDecimal("100")).divide(den, 4, RoundingMode.HALF_UP);
    }

    private static String pct(BigDecimal v) {
        return v == null ? "—" : v.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** TS-YYYYMMDD-NNN 编号（冲突递增） */
    private void insertResolvingConflict(TrialSummary row) {
        String prefix = "TS-" + LocalDate.now().format(DAY) + "-";
        long today = summaryDao.selectCount(new LambdaQueryWrapper<TrialSummary>()
                .likeRight(TrialSummary::getSummaryNo, prefix));
        for (int attempt = 0; attempt < 99; attempt++) {
            row.setSummaryNo(prefix + String.format("%03d", today + 1 + attempt));
            try {
                summaryDao.insert(row);
                return;
            } catch (org.springframework.dao.DuplicateKeyException e) {
                today++;
            }
        }
        throw new ServiceException(422, "试产总结单号分配冲突超限，请稍后重试");
    }

    /** 总结侧写权限（design D12：申请/转开/建单/采集/总结 = 计划两方 + 工艺两方 + PM + ADMIN） */
    private void requireWriter(String action) {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder
                        .getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new ServiceException(401, action + "需要登录");
        }
        for (String want : new String[]{"ROLE_PLANNER", "ROLE_PLAN_MGR", "ROLE_PROCESS_ENG",
                "ROLE_PROCESS_MGR", "ROLE_PM", "ROLE_ADMIN"}) {
            if (auth.getAuthorities().contains(
                    new org.springframework.security.core.authority.SimpleGrantedAuthority(want))) {
                return;
            }
        }
        throw new ServiceException(403, "无权限：" + action);
    }

    private String currentOperator() {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder
                        .getContext().getAuthentication();
        return auth == null ? "system" : auth.getName();
    }
}
