package com.erp.service.impl.eam;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.eam.EamAssetCardDao;
import com.erp.dao.eam.EamLeaseContractDao;
import com.erp.dao.eam.EamLeaseScheduleDao;
import com.erp.eam.LeaseScheduleEngine;
import com.erp.eam.LeaseScheduleEngine.ScheduleRow;
import com.erp.entity.eam.EamAssetCard;
import com.erp.entity.eam.EamLeaseContract;
import com.erp.entity.eam.EamLeaseSchedule;
import com.erp.service.eam.EamLeaseService;
import com.erp.service.fin.GlVoucherService;
import com.erp.service.fin.GlVoucherService.FinVoucherLineSpec;
import com.erp.service.system.NoticeService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 租赁全周期实现（add-asset-transfer-lease，spec asset-transfer-lease，design D5/D6）。
 * PV/摊余计划委托 {@link LeaseScheduleEngine} 纯函数；月度处理月标签幂等；
 * 重新计量走审批单签回调；到期终止退租核销/购买转自有。
 */
@Slf4j
@Service
public class EamLeaseServiceImpl implements EamLeaseService {

    private static final String ACCT_ROU = "1621";          // 使用权资产
    private static final String ACCT_LIAB = "2701";         // 租赁负债
    private static final String ACCT_INTEREST = "6603";     // 租赁利息费用
    private static final String ACCT_BANK = "1002";         // 银行存款
    private static final String ACCT_CLEARING = "1606";     // 固定资产清理
    private static final String ACCT_FIXED = "1601";        // 固定资产
    private static final String ACCT_ACCUM = "1602";        // 累计折旧
    private static final String ACCT_AR = "1122";           // 应收（非正常损坏赔偿挂账，design D6 简化）
    private static final String PARAM_DISCOUNT = "LEASE_DEFAULT_DISCOUNT_RATE";
    private static final String PARAM_TOLERANCE = "TOLERANCE_DEFAULT";
    /** 月度金额环比异常阈值（利息+租金合计较上期偏离超此比例 → L4） */
    private static final BigDecimal MONTH_DEVIATION = new BigDecimal("0.30");

    private final EamLeaseContractDao contractDao;
    private final EamLeaseScheduleDao scheduleDao;
    private final EamAssetCardDao cardDao;
    private final GlVoucherService voucherService;
    private final NoticeService noticeService;
    private final com.erp.service.SysParamService sysParamService;
    private final com.erp.service.approval.ApprovalEngine approvalEngine;

    public EamLeaseServiceImpl(EamLeaseContractDao contractDao,
                               EamLeaseScheduleDao scheduleDao,
                               EamAssetCardDao cardDao,
                               GlVoucherService voucherService,
                               NoticeService noticeService,
                               com.erp.service.SysParamService sysParamService,
                               com.erp.service.approval.ApprovalEngine approvalEngine) {
        this.contractDao = contractDao;
        this.scheduleDao = scheduleDao;
        this.cardDao = cardDao;
        this.voucherService = voucherService;
        this.noticeService = noticeService;
        this.sysParamService = sysParamService;
        this.approvalEngine = approvalEngine;
    }

    // ================= 分页 / 详情 / CRUD =================

    @Override
    public Page<EamLeaseContract> page(long current, long size, String status, String keyword) {
        LambdaQueryWrapper<EamLeaseContract> qw = new LambdaQueryWrapper<EamLeaseContract>()
                .eq(hasText(status), EamLeaseContract::getStatus, status)
                .and(hasText(keyword), w -> w
                        .like(EamLeaseContract::getLseNo, keyword)
                        .or().like(EamLeaseContract::getLessor, keyword)
                        .or().like(EamLeaseContract::getAssetDesc, keyword))
                .orderByDesc(EamLeaseContract::getCreateDate);
        return contractDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    public Map<String, Object> detail(String id) {
        EamLeaseContract c = require(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contract", c);
        out.put("rouCard", c.getRouCardId() == null ? null : cardDao.selectById(c.getRouCardId()));
        List<EamLeaseSchedule> rows = scheduleDao.selectByContract(id);
        out.put("schedule", rows);
        out.put("snapshots", scheduleDao.selectSnapshots(id));
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> create(EamLeaseContract req) {
        List<String> missing = new ArrayList<>();
        if (!hasText(req.getLessor())) {
            missing.add("出租方");
        }
        if (!hasText(req.getAssetDesc())) {
            missing.add("租赁资产描述");
        }
        if (req.getStartDate() == null || req.getEndDate() == null) {
            missing.add("租赁期限");
        }
        if (req.getPaymentAmount() == null || req.getPaymentAmount().signum() <= 0) {
            missing.add("租金金额");
        }
        if (req.getPayCycleMonths() == null || req.getPayCycleMonths() <= 0) {
            missing.add("租金支付条款（付款周期）");
        }
        if (!missing.isEmpty()) {
            throw new ServiceException(422, "租赁合同关键条款不完整，阻断录入：" + String.join("、", missing));
        }
        if (!req.getEndDate().isAfter(req.getStartDate())) {
            throw new ServiceException(422, "租赁期止必须晚于起始日");
        }
        int months = (int) (ChronoUnit.MONTHS.between(req.getStartDate(), req.getEndDate())) + 1;
        EamLeaseContract c = new EamLeaseContract();
        c.setLseNo(nextLseNo());
        c.setLessor(req.getLessor());
        c.setAssetDesc(req.getAssetDesc());
        c.setStartDate(req.getStartDate());
        c.setEndDate(req.getEndDate());
        c.setTermsMonths(months);
        c.setPaymentAmount(req.getPaymentAmount());
        c.setPayCycleMonths(req.getPayCycleMonths());
        c.setDiscountRate(req.getDiscountRate());
        c.setRateBasis(req.getRateBasis());
        c.setRenewOption(req.getRenewOption());
        c.setBuyOption(req.getBuyOption() == null ? "0" : req.getBuyOption());
        c.setBuyPrice(req.getBuyPrice());
        c.setIndexFloatFlag(req.getIndexFloatFlag() == null ? "0" : req.getIndexFloatFlag());
        c.setInitDirectCost(nz(req.getInitDirectCost()));
        c.setPrepaidRent(nz(req.getPrepaidRent()));
        c.setLeaseIncentive(nz(req.getLeaseIncentive()));
        c.setExemptReason(req.getExemptReason());
        c.setStatus("DRAFT");
        c.setEstimatedFlag("0");
        c.setCreateBy(SecurityUtils.getCurrentUserId());
        contractDao.insert(c);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contract", c);
        out.put("suggest", suggestOf(c));
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> update(EamLeaseContract req) {
        EamLeaseContract old = require(req.getId());
        if (!"DRAFT".equals(old.getStatus()) && !"CLASSIFIED".equals(old.getStatus())) {
            throw new ServiceException(422, "仅草稿/已分类可编辑（使用权确认前可改条款，当前 "
                    + old.getStatus() + "）");
        }
        EamLeaseContract upd = new EamLeaseContract();
        upd.setId(old.getId());
        upd.setVerNo(old.getVerNo());
        upd.setLessor(req.getLessor());
        upd.setAssetDesc(req.getAssetDesc());
        upd.setPaymentAmount(req.getPaymentAmount());
        upd.setPayCycleMonths(req.getPayCycleMonths());
        upd.setDiscountRate(req.getDiscountRate());
        upd.setRateBasis(req.getRateBasis());
        upd.setRenewOption(req.getRenewOption());
        upd.setBuyOption(req.getBuyOption());
        upd.setBuyPrice(req.getBuyPrice());
        upd.setIndexFloatFlag(req.getIndexFloatFlag());
        upd.setInitDirectCost(req.getInitDirectCost());
        upd.setPrepaidRent(req.getPrepaidRent());
        upd.setLeaseIncentive(req.getLeaseIncentive());
        upd.setExemptReason(req.getExemptReason());
        upd.setRemark(req.getRemark());
        upd.setUpdateBy(SecurityUtils.getCurrentUserId());
        if (contractDao.updateById(upd) == 0) {
            throw new ServiceException(409, "合同更新冲突，请刷新后重试");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contract", contractDao.selectById(old.getId()));
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> delete(String id) {
        EamLeaseContract c = require(id);
        if (!"DRAFT".equals(c.getStatus())) {
            throw new ServiceException(422, "仅草稿可删除（当前 " + c.getStatus() + "）");
        }
        contractDao.deleteById(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("deleted", true);
        return out;
    }

    // ================= 分类判断与确认 =================

    @Override
    public Map<String, Object> suggestClassify(String id) {
        EamLeaseContract c = require(id);
        return suggestOf(c);
    }

    /** 辅助判断（FR-4.13-4-7）：≤12 月 → EXEMPT 候选（短期豁免）；否则 ROU 候选 */
    private Map<String, Object> suggestOf(EamLeaseContract c) {
        Map<String, Object> out = new LinkedHashMap<>();
        String suggestion;
        String basis;
        if (c.getTermsMonths() != null && c.getTermsMonths() <= 12) {
            suggestion = "EXEMPT";
            basis = "租期 " + c.getTermsMonths() + " 个月 ≤ 12 个月，短期租赁豁免适用（BR-4.13-25），"
                    + "租金直线计入当期损益";
        } else {
            suggestion = "ROU";
            basis = "租期 " + c.getTermsMonths() + " 个月 > 12 个月且已识别租赁资产，使用权资产适用（BR-4.13-24），"
                    + "建议确认使用权资产与租赁负债";
        }
        out.put("suggest", suggestion);
        out.put("basis", basis);
        out.put("manualConfirmRequired", true);
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> classify(String id, String classify, String basis, boolean disputeByFinMgr) {
        EamLeaseContract c = require(id);
        if ("DRAFT".equals(c.getStatus()) || "CLASSIFIED".equals(c.getStatus())) {
            // 可重分类
        } else {
            throw new ServiceException(422, "当前状态不可分类（" + c.getStatus() + "）");
        }
        String cls = classify == null ? "" : classify.trim().toUpperCase();
        if (!List.of("ROU", "FINANCE", "OPERATING", "EXEMPT").contains(cls)) {
            throw new ServiceException(422, "分类取值域：ROU/FINANCE/OPERATING/EXEMPT");
        }
        if (!hasText(basis)) {
            throw new ServiceException(422, "分类判断依据必填（留痕）");
        }
        if (disputeByFinMgr) {
            // 分类争议由财务经理确认留痕
            requireRole("ROLE_FIN_MGR", "确认争议分类");
            basis = "[财务经理确认] " + basis;
        }
        EamLeaseContract upd = new EamLeaseContract();
        upd.setId(c.getId());
        upd.setVerNo(c.getVerNo());
        upd.setClassify(cls);
        upd.setClassifyBasis(basis);
        upd.setConfirmBy(SecurityUtils.getCurrentUserId());
        upd.setConfirmDate(LocalDate.now());
        upd.setStatus("CLASSIFIED");
        upd.setUpdateBy(SecurityUtils.getCurrentUserId());
        contractDao.updateById(upd);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contract", contractDao.selectById(c.getId()));
        return out;
    }

    // ================= 使用权确认（BR-24/25）=================

    @Override
    @Transactional
    public Map<String, Object> confirm(String id) {
        EamLeaseContract c = require(id);
        if (!"CLASSIFIED".equals(c.getStatus())) {
            throw new ServiceException(422, "须先完成分类确认（当前 " + c.getStatus() + "）");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if ("EXEMPT".equals(c.getClassify())) {
            // BR-25 短期/低值豁免：不建卡不出计划，留痕（L5）
            if (!hasText(c.getExemptReason())) {
                throw new ServiceException(422, "豁免适用须填写豁免选择与理由（BR-4.13-25 L5 留痕）");
            }
            EamLeaseContract upd = new EamLeaseContract();
            upd.setId(c.getId());
            upd.setVerNo(c.getVerNo());
            upd.setStatus("CONFIRMED");
            upd.setUpdateBy(SecurityUtils.getCurrentUserId());
            contractDao.updateById(upd);
            out.put("contract", contractDao.selectById(c.getId()));
            out.put("exempt", true);
            out.put("note", "豁免合同不生成 ROU 卡片与摊余计划，月度处理按直线法出租金费用凭证");
            return out;
        }
        if (!"ROU".equals(c.getClassify())) {
            throw new ServiceException(422, "使用权确认仅支持 ROU 分类（当前 " + c.getClassify()
                    + "；FINANCE/OPERATING 本期不做使用权确认，偏差记录）");
        }
        // 折现率：内含优先，取不到用增量借款利率并留痕（FR-4.13-4-8）
        BigDecimal rate = c.getDiscountRate();
        String basis = c.getRateBasis();
        if (rate == null || rate.compareTo(BigDecimal.ZERO) <= 0) {
            rate = sysParamService.getRate(PARAM_DISCOUNT, BigDecimal.ZERO)
                    .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
            basis = "INCREMENTAL";
        } else if (!hasText(basis)) {
            basis = "INCLUSIVE";
        }
        if (rate.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ServiceException(422, "折现率缺失：请录入租赁内含利率或配置 " + PARAM_DISCOUNT);
        }

        // PV + 全周期摊余计划（引擎纯函数）
        BigDecimal pv = LeaseScheduleEngine.presentValue(c.getPaymentAmount(),
                c.getPayCycleMonths(), c.getTermsMonths(), rate);
        BigDecimal rouAmount = pv.add(nz(c.getInitDirectCost())).add(nz(c.getPrepaidRent()))
                .subtract(nz(c.getLeaseIncentive())).setScale(2, RoundingMode.HALF_UP);
        List<ScheduleRow> rows = LeaseScheduleEngine.generate(pv, c.getPaymentAmount(),
                c.getPayCycleMonths(), c.getTermsMonths(), rate,
                c.getStartDate().format(DateTimeFormatter.ofPattern("yyyyMM")));

        // ROU 建卡（LEASE_FLAG=1，次月起提进入 7.2 引擎；年限上取整年+末月截减，design D5）
        int lifeYears = (c.getTermsMonths() + 11) / 12;
        EamLeaseContract before = contractDao.selectById(c.getId());
        EamAssetCard card = new EamAssetCard();
        card.setCardNo(nextCardNo());
        card.setAssetName("ROU-" + c.getAssetDesc());
        card.setCategoryCode("ROU");
        card.setDeptName(SecurityUtils.getCurrentUserId());
        card.setAssetCost(rouAmount);
        card.setLifeYears(lifeYears);
        card.setResidualRate(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
        card.setResidualValue(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        card.setDepreciationMethod("STRAIGHT_LINE");
        card.setEffectiveDate(c.getStartDate());
        card.setStatus("IN_USE");
        card.setDeprStatus("NOT_DEPRECIATED");
        card.setAccumDepr(BigDecimal.ZERO);
        card.setLockFlag("1");
        card.setLeaseFlag("1");
        card.setLeaseContractId(c.getId());
        card.setLegalEntity(null);
        card.setCreateBy(SecurityUtils.getCurrentUserId());
        cardDao.insert(card);

        // 计划行落库
        for (ScheduleRow r : rows) {
            EamLeaseSchedule s = new EamLeaseSchedule();
            s.setContractId(c.getId());
            s.setLseNo(c.getLseNo());
            s.setSeq(r.seq());
            s.setPeriodTag(r.periodTag());
            s.setBeginBal(r.beginBal());
            s.setInterest(r.interest());
            s.setPayment(r.payment());
            s.setEndBal(r.endBal());
            s.setProcessedFlag("0");
            s.setSnapshotSeq(0);
            s.setCreateBy(SecurityUtils.getCurrentUserId());
            scheduleDao.insert(s);
        }

        EamLeaseContract upd = new EamLeaseContract();
        upd.setId(before.getId());
        upd.setVerNo(before.getVerNo());
        upd.setPv(pv);
        upd.setRouAmount(rouAmount);
        upd.setRouCardId(card.getId());
        upd.setDiscountRate(rate);
        upd.setRateBasis(basis);
        upd.setStatus("CONFIRMED");
        upd.setUpdateBy(SecurityUtils.getCurrentUserId());
        contractDao.updateById(upd);

        out.put("contract", contractDao.selectById(c.getId()));
        out.put("pv", pv);
        out.put("rouAmount", rouAmount);
        out.put("rouCard", card);
        out.put("scheduleRows", rows.size());
        return out;
    }

    @Override
    public Map<String, Object> schedule(String contractId) {
        EamLeaseContract c = require(contractId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contract", c);
        out.put("rows", scheduleDao.selectByContract(contractId));
        out.put("snapshots", scheduleDao.selectSnapshots(contractId));
        return out;
    }

    // ================= 月度处理（幂等）=================

    @Override
    @Transactional
    public Map<String, Object> monthlyProcess(String periodTag) {
        requirePeriod(periodTag);
        List<EamLeaseContract> contracts = contractDao.selectList(
                new LambdaQueryWrapper<EamLeaseContract>()
                        .eq(EamLeaseContract::getStatus, "CONFIRMED"));
        if (contracts.isEmpty()) {
            throw new ServiceException(422, "无已确认的租赁合同可处理");
        }
        int processedContracts = 0;
        int skippedAlready = 0;
        int interestVouchers = 0;
        int payVouchers = 0;
        int exemptVouchers = 0;
        List<String> notes = new ArrayList<>();
        for (EamLeaseContract c : contracts) {
            boolean isExempt = c.getRouCardId() == null;
            if (isExempt) {
                // 豁免合同：直线法月租金 = 每期付款 / 付款周期月数
                // 幂等锚 = CHANGE_JSON.exemptFlat（tag=vid 对；冲回时移除条目 → 放行重提）
                String flat = extractExempt(c.getChangeJson());
                if (flat.contains(periodTag + "=")) {
                    skippedAlready++;
                    continue;
                }
                BigDecimal monthlyRent = c.getPaymentAmount()
                        .divide(BigDecimal.valueOf(c.getPayCycleMonths()), 2, RoundingMode.HALF_UP);
                String summary = "短期租赁租金（豁免）：" + c.getLseNo() + " " + periodTag;
                var v = voucherService.create("LSE", periodEndDate(periodTag), summary,
                        "LEASE_RENT", c.getLseNo() + "@" + periodTag, null, List.of(
                        FinVoucherLineSpec.of(ACCT_INTEREST, "DR", monthlyRent, summary),
                        FinVoucherLineSpec.of(ACCT_BANK, "CR", monthlyRent, summary)));
                // 已处理月份入账（保留 pending 参数）
                EamLeaseContract jupd = new EamLeaseContract();
                jupd.setId(c.getId());
                jupd.setVerNo(c.getVerNo());
                jupd.setChangeJson(buildJson(extractPendingRaw(c.getChangeJson()),
                        appendExempt(flat, periodTag, v.getId())));
                jupd.setUpdateBy(SecurityUtils.getCurrentUserId());
                contractDao.updateById(jupd);
                exemptVouchers++;
                processedContracts++;
                notes.add(c.getLseNo() + " 豁免直线租金 " + monthlyRent + "（凭证 " + v.getId() + "）");
                continue;
            }
            // ROU 合同：取当月计划行
            List<EamLeaseSchedule> rows = scheduleDao.selectList(
                    new LambdaQueryWrapper<EamLeaseSchedule>()
                            .eq(EamLeaseSchedule::getContractId, c.getId())
                            .eq(EamLeaseSchedule::getPeriodTag, periodTag)
                            .eq(EamLeaseSchedule::getSnapshotSeq, 0));
            if (rows.isEmpty()) {
                continue;   // 该合同无当月行（未到/已过租期）
            }
            EamLeaseSchedule row = rows.get(0);
            if ("1".equals(row.getProcessedFlag())) {
                skippedAlready++;
                continue;   // 幂等跳过（整体重复触发见下方 422 判定）
            }
            // ① 利息凭证
            String intId = null;
            if (row.getInterest().signum() > 0) {
                String summary = "租赁负债利息：" + c.getLseNo() + " " + periodTag;
                var v = voucherService.create("LSE", periodEndDate(periodTag), summary,
                        "LEASE_INTEREST", c.getLseNo(), null, List.of(
                        FinVoucherLineSpec.of(ACCT_INTEREST, "DR", row.getInterest(), summary),
                        FinVoucherLineSpec.of(ACCT_LIAB, "CR", row.getInterest(), summary)));
                intId = v.getId();
                interestVouchers++;
            }
            // ② 付款核销凭证（无付款行跳过）
            String payId = null;
            if (row.getPayment().signum() > 0) {
                String summary = "租赁付款核销：" + c.getLseNo() + " " + periodTag;
                var v = voucherService.create("LSE", periodEndDate(periodTag), summary,
                        "LEASE_PAYMENT", c.getLseNo(), null, List.of(
                        FinVoucherLineSpec.of(ACCT_LIAB, "DR", row.getPayment(), summary),
                        FinVoucherLineSpec.of(ACCT_BANK, "CR", row.getPayment(), summary)));
                payId = v.getId();
                payVouchers++;
            }
            // ③ 回写计划行
            EamLeaseSchedule supd = new EamLeaseSchedule();
            supd.setId(row.getId());
            supd.setVerNo(row.getVerNo());
            supd.setProcessedFlag("1");
            supd.setProcessedAt(LocalDateTime.now());
            supd.setVoucherIntId(intId);
            supd.setVoucherPayId(payId);
            supd.setUpdateBy(SecurityUtils.getCurrentUserId());
            scheduleDao.updateById(supd);
            // ④ ROU 卡片折旧并入 7.2 当月计提（IN_USE + 起提月，无需接线，design D5）
            processedContracts++;
            notes.add(c.getLseNo() + " 利息 " + row.getInterest() + " / 付款 " + row.getPayment());
        }
        if (processedContracts == 0 && skippedAlready > 0) {
            throw new ServiceException(422, "该会计月租赁处理已完成（月标签幂等），如需重算请先冲回（" + periodTag + "）");
        }
        if (processedContracts == 0) {
            throw new ServiceException(422, "该会计月无可处理的租赁计划行（" + periodTag + "）");
        }
        // 金额环比异常 L4 预警（与上月合计比）
        pushDeviationNotice(periodTag, contracts);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("periodTag", periodTag);
        out.put("processedContracts", processedContracts);
        out.put("skippedAlready", skippedAlready);
        out.put("interestVouchers", interestVouchers);
        out.put("payVouchers", payVouchers);
        out.put("exemptVouchers", exemptVouchers);
        out.put("notes", notes);
        // ROU 折旧由 7.2 计提承担，此处仅利息/付款/豁免租金
        return out;
    }

    private void pushDeviationNotice(String periodTag, List<EamLeaseContract> contracts) {
        try {
            BigDecimal cur = sumPeriodAmount(periodTag, contracts);
            BigDecimal prev = sumPeriodAmount(prevPeriod(periodTag), contracts);
            if (prev.signum() > 0) {
                BigDecimal dev = cur.subtract(prev).abs().divide(prev, 4, RoundingMode.HALF_UP);
                if (dev.compareTo(MONTH_DEVIATION) > 0) {
                    noticeService.push("ROLE_FIN_MGR", null, "租赁月度金额环比异常（L4）",
                            periodTag + " 租赁利息+付款合计 " + cur + "，上期 " + prev
                                    + "，偏离 " + dev.multiply(BigDecimal.valueOf(100)).setScale(1)
                                    + "%，请复核",
                            "EAM_LEASE_ANOMALY", periodTag);
                }
            }
        } catch (Exception ex) {
            log.warn("租赁环比预警推送失败（不阻断）：{}", ex.getMessage());
        }
    }

    private BigDecimal sumPeriodAmount(String periodTag, List<EamLeaseContract> contracts) {
        BigDecimal total = BigDecimal.ZERO;
        for (EamLeaseContract c : contracts) {
            if (c.getRouCardId() == null) {
                continue;
            }
            for (EamLeaseSchedule r : scheduleDao.selectList(
                    new LambdaQueryWrapper<EamLeaseSchedule>()
                            .eq(EamLeaseSchedule::getContractId, c.getId())
                            .eq(EamLeaseSchedule::getPeriodTag, periodTag)
                            .eq(EamLeaseSchedule::getSnapshotSeq, 0))) {
                total = total.add(nz(r.getInterest())).add(nz(r.getPayment()));
            }
        }
        return total;
    }

    // ================= 冲回（整单全冲）=================

    @Override
    @Transactional
    public Map<String, Object> reverse(String periodTag) {
        requirePeriod(periodTag);
        List<EamLeaseContract> contracts = contractDao.selectList(
                new LambdaQueryWrapper<EamLeaseContract>()
                        .eq(EamLeaseContract::getStatus, "CONFIRMED"));
        int reversedVouchers = 0;
        int resetRows = 0;
        for (EamLeaseContract c : contracts) {
            if (c.getRouCardId() == null) {
                // 豁免合同红冲租金凭证（按 exemptFlat 锚定；冲回后移除条目放行重提）
                String flat = extractExempt(c.getChangeJson());
                String vid = lookupExempt(flat, periodTag);
                if (vid != null) {
                    voucherService.reverse(vid, "租赁冲回", c.getLseNo());
                    reversedVouchers++;
                    EamLeaseContract jupd = new EamLeaseContract();
                    jupd.setId(c.getId());
                    jupd.setVerNo(c.getVerNo());
                    jupd.setChangeJson(buildJson(extractPendingRaw(c.getChangeJson()),
                            removeExempt(flat, periodTag)));
                    jupd.setUpdateBy(SecurityUtils.getCurrentUserId());
                    contractDao.updateById(jupd);
                }
                continue;
            }
            for (EamLeaseSchedule row : scheduleDao.selectList(
                    new LambdaQueryWrapper<EamLeaseSchedule>()
                            .eq(EamLeaseSchedule::getContractId, c.getId())
                            .eq(EamLeaseSchedule::getPeriodTag, periodTag)
                            .eq(EamLeaseSchedule::getSnapshotSeq, 0)
                            .eq(EamLeaseSchedule::getProcessedFlag, "1"))) {
                if (hasText(row.getVoucherIntId())) {
                    voucherService.reverse(row.getVoucherIntId(), "租赁冲回", c.getLseNo());
                    reversedVouchers++;
                }
                if (hasText(row.getVoucherPayId())) {
                    voucherService.reverse(row.getVoucherPayId(), "租赁冲回", c.getLseNo());
                    reversedVouchers++;
                }
                EamLeaseSchedule supd = new EamLeaseSchedule();
                supd.setId(row.getId());
                supd.setVerNo(row.getVerNo());
                supd.setProcessedFlag("0");
                supd.setProcessedAt(null);
                supd.setVoucherIntId(null);
                supd.setVoucherPayId(null);
                supd.setUpdateBy(SecurityUtils.getCurrentUserId());
                scheduleDao.updateById(supd);
                resetRows++;
            }
        }
        if (reversedVouchers == 0) {
            throw new ServiceException(422, "该会计月无已处理的租赁行，无可冲回（" + periodTag + "）");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("periodTag", periodTag);
        out.put("reversedVouchers", reversedVouchers);
        out.put("resetRows", resetRows);
        return out;
    }

    // ================= 重新计量（BR-04）=================

    @Override
    @Transactional
    public Map<String, Object> remeasure(String contractId, BigDecimal revisedRate,
                                         Integer extraMonths, BigDecimal revisedPayment,
                                         String reason) {
        EamLeaseContract c = require(contractId);
        if (!"CONFIRMED".equals(c.getStatus())) {
            throw new ServiceException(422, "仅已确认合同可重新计量（当前 " + c.getStatus() + "）");
        }
        if (c.getRouCardId() == null) {
            throw new ServiceException(422, "豁免合同无使用权资产，不可重新计量");
        }
        if (extraMonths == null || extraMonths <= 0) {
            throw new ServiceException(422, "变更后剩余月数（新增月数）必填且大于 0");
        }
        if (!hasText(reason)) {
            throw new ServiceException(422, "重新计量须填写变更原因");
        }
        boolean hasFuture = scheduleDao.selectList(new LambdaQueryWrapper<EamLeaseSchedule>()
                .eq(EamLeaseSchedule::getContractId, contractId)
                .eq(EamLeaseSchedule::getSnapshotSeq, 0)
                .eq(EamLeaseSchedule::getProcessedFlag, "0")).stream()
                .anyMatch(r -> r.getSeq() > 0);
        if (!hasFuture) {
            throw new ServiceException(422, "无未处理计划行，不可重新计量");
        }
        // 待执行参数入 CHANGE_JSON（回调读取）
        String json = "{\"pending\":{\"revisedRate\":" + (revisedRate == null ? "null" : revisedRate.toPlainString())
                + ",\"extraMonths\":" + extraMonths
                + ",\"revisedPayment\":" + (revisedPayment == null ? "null" : revisedPayment.toPlainString())
                + ",\"reason\":\"" + (reason == null ? "" : reason.replace("\"", "'")) + "\"}}";
        EamLeaseContract upd = new EamLeaseContract();
        upd.setId(c.getId());
        upd.setVerNo(c.getVerNo());
        upd.setChangeJson(json);
        upd.setStatus("CONFIRMED");
        upd.setUpdateBy(SecurityUtils.getCurrentUserId());
        contractDao.updateById(upd);
        // 财务资产会计单签
        var inst = approvalEngine.submit("AssetLeaseRemeasure", c.getId(),
                "租赁重新计量审核：" + c.getLseNo() + " / 变更 " + extraMonths + " 月", null,
                List.of(List.of(com.erp.service.approval.ApprovalNodeSpec
                        .sign("ROLE_FIN_ASSET", "财务资产会计审核"))));
        EamLeaseContract aup = new EamLeaseContract();
        aup.setId(c.getId());
        aup.setVerNo(contractDao.selectById(c.getId()).getVerNo());
        aup.setApprId(inst.getId());
        contractDao.updateById(aup);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contractId", c.getId());
        out.put("apprId", inst.getId());
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> executeRemeasure(String contractId) {
        EamLeaseContract c = require(contractId);
        // 解析 pending 参数（CHANGE_JSON；Gson/Jackson 不引依赖，手拆定值 JSON）
        Pending p = parsePending(c.getChangeJson());
        if (p == null) {
            throw new ServiceException(422, "无待执行的重新计量请求");
        }
        BigDecimal rate = p.revisedRate != null ? p.revisedRate : nz(c.getDiscountRate());
        if (rate.compareTo(BigDecimal.ZERO) <= 0) {
            rate = sysParamService.getRate(PARAM_DISCOUNT, BigDecimal.ZERO)
                    .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
        }
        BigDecimal payment = p.revisedPayment != null ? p.revisedPayment : c.getPaymentAmount();

        // 上一已处理行（重算起点）：T0 或最后 processed 行
        List<EamLeaseSchedule> current = scheduleDao.selectByContract(contractId);
        EamLeaseSchedule lastProcessed = null;
        int processedCount = 0;
        for (EamLeaseSchedule r : current) {
            if (r.getSeq() > 0 && "1".equals(r.getProcessedFlag())) {
                lastProcessed = r;
                processedCount++;
            }
        }
        BigDecimal beginBal = lastProcessed == null
                ? (current.isEmpty() ? nz(c.getPv()) : current.get(0).getEndBal())
                : lastProcessed.getEndBal();
        int fromSeq = (lastProcessed == null ? 0 : lastProcessed.getSeq()) + 1;
        YearMonth fromMonth = lastProcessed == null
                ? YearMonth.from(c.getStartDate())
                : YearMonth.parse(lastProcessed.getPeriodTag().substring(0, 4) + "-"
                + lastProcessed.getPeriodTag().substring(4));
        int remaining = c.getTermsMonths() + p.extraMonths - processedCount;
        if (remaining <= 0) {
            throw new ServiceException(422, "变更后剩余月数无效（已处理 " + processedCount + " 月）");
        }
        // 旧行快照归档（SNAPSHOT_SEQ+1，同批同一快照号）
        int nextSnap = 0;
        for (EamLeaseSchedule r : current) {
            if (r.getSeq() >= fromSeq && "0".equals(r.getProcessedFlag())) {
                nextSnap = Math.max(nextSnap, r.getSnapshotSeq() == null ? 0 : r.getSnapshotSeq());
            }
        }
        nextSnap = nextSnap + 1;
        for (EamLeaseSchedule r : current) {
            if (r.getSeq() >= fromSeq && "0".equals(r.getProcessedFlag())) {
                EamLeaseSchedule s = new EamLeaseSchedule();
                s.setId(r.getId());
                s.setVerNo(r.getVerNo());
                s.setSnapshotSeq(nextSnap);
                s.setUpdateBy(SecurityUtils.getCurrentUserId());
                scheduleDao.updateById(s);
            }
        }
        // 剩余行重算
        List<ScheduleRow> rows = LeaseScheduleEngine.remeasure(beginBal, fromSeq,
                fromMonth.plusMonths(1).format(DateTimeFormatter.ofPattern("yyyyMM")),
                payment, c.getPayCycleMonths(), remaining, rate);
        for (ScheduleRow r : rows) {
            EamLeaseSchedule s = new EamLeaseSchedule();
            s.setContractId(contractId);
            s.setLseNo(c.getLseNo());
            s.setSeq(r.seq());
            s.setPeriodTag(r.periodTag());
            s.setBeginBal(r.beginBal());
            s.setInterest(r.interest());
            s.setPayment(r.payment());
            s.setEndBal(r.endBal());
            s.setProcessedFlag("0");
            s.setSnapshotSeq(0);
            s.setCreateBy(SecurityUtils.getCurrentUserId());
            scheduleDao.insert(s);
        }
        // ROU 卡片原值差额调整 + 调整凭证（借/贷 1621 vs 2701）
        BigDecimal newPv = LeaseScheduleEngine.presentValue(payment, c.getPayCycleMonths(),
                remaining, rate);
        BigDecimal delta = newPv.subtract(beginBal).setScale(2, RoundingMode.HALF_UP);
        String voucherId = null;
        if (delta.signum() != 0) {
            EamAssetCard card = cardDao.selectById(c.getRouCardId());
            String summary = "租赁重新计量调整：" + c.getLseNo() + " / 差额 " + delta;
            List<FinVoucherLineSpec> lines = delta.signum() > 0
                    ? List.of(FinVoucherLineSpec.of(ACCT_ROU, "DR", delta, summary),
                    FinVoucherLineSpec.of(ACCT_LIAB, "CR", delta, summary))
                    : List.of(FinVoucherLineSpec.of(ACCT_LIAB, "DR", delta.negate(), summary),
                    FinVoucherLineSpec.of(ACCT_ROU, "CR", delta.negate(), summary));
            var v = voucherService.create("LSE", LocalDate.now(), summary,
                    "LEASE_REMEASURE", c.getLseNo(), null, lines);
            voucherId = v.getId();
            EamAssetCard cupd = new EamAssetCard();
            cupd.setId(card.getId());
            cupd.setVerNo(card.getVerNo());
            cupd.setAssetCost(nz(card.getAssetCost()).add(delta).setScale(2, RoundingMode.HALF_UP));
            cupd.setUpdateBy(SecurityUtils.getCurrentUserId());
            cardDao.updateById(cupd);
        }
        // 合同落终态
        EamLeaseContract upd = new EamLeaseContract();
        upd.setId(c.getId());
        upd.setVerNo(contractDao.selectById(c.getId()).getVerNo());
        upd.setDiscountRate(rate);
        upd.setPaymentAmount(payment);
        upd.setTermsMonths(c.getTermsMonths() + p.extraMonths);
        upd.setEndDate(YearMonth.from(c.getEndDate()).plusMonths(p.extraMonths).atEndOfMonth());
        upd.setPv(nz(c.getPv()).add(delta).setScale(2, RoundingMode.HALF_UP));
        upd.setRouAmount(nz(c.getRouAmount()).add(delta).setScale(2, RoundingMode.HALF_UP));
        upd.setChangeJson(c.getChangeJson().replace("\"pending\"", "\"executed\""));
        upd.setUpdateBy(SecurityUtils.getCurrentUserId());
        contractDao.updateById(upd);
        log.info("租赁 {} 重新计量完成：剩余 {} 月，差额 {}，凭证 {}", c.getLseNo(), remaining, delta, voucherId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contractId", contractId);
        out.put("remainingMonths", remaining);
        out.put("delta", delta);
        out.put("adjustVoucherId", voucherId);
        out.put("snapshotSeq", nextSnap);
        return out;
    }

    // ================= 临期清单（BR-44）=================

    @Override
    public List<Map<String, Object>> expiryList() {
        List<Map<String, Object>> out = new ArrayList<>();
        LocalDate today = LocalDate.now();
        List<EamLeaseContract> contracts = contractDao.selectList(
                new LambdaQueryWrapper<EamLeaseContract>()
                        .in(EamLeaseContract::getStatus, "CONFIRMED", "MATURED"));
        for (EamLeaseContract c : contracts) {
            if (c.getEndDate() == null) {
                continue;
            }
            long months = ChronoUnit.MONTHS.between(today, c.getEndDate());
            if (months > 6 || months < 0) {
                continue;
            }
            String level = months <= 1 ? "M1" : "M6";
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("contractId", c.getId());
            row.put("lseNo", c.getLseNo());
            row.put("lessor", c.getLessor());
            row.put("assetDesc", c.getAssetDesc());
            row.put("endDate", c.getEndDate());
            row.put("monthsLeft", months);
            row.put("level", level);
            row.put("estimatedFlag", c.getEstimatedFlag());
            row.put("renewOption", c.getRenewOption());
            row.put("buyOption", c.getBuyOption());
            row.put("buyPrice", c.getBuyPrice());
            out.add(row);
            try {
                if ("M1".equals(level)) {
                    // 1 月未决策升级部门负责人 + 默示续租暂估标记（BR-44）
                    EamLeaseContract upd = new EamLeaseContract();
                    upd.setId(c.getId());
                    upd.setVerNo(c.getVerNo());
                    upd.setEstimatedFlag("1");
                    upd.setUpdateBy(SecurityUtils.getCurrentUserId());
                    contractDao.updateById(upd);
                    noticeService.push("ROLE_DEPT_HEAD", null, "租赁到期升级（剩 1 月未决策）",
                            c.getLseNo() + "（" + c.getAssetDesc() + "）剩 " + months
                                    + " 月到期且无决策单，已按原条款暂估计提（凭证标注\"暂估\"），请尽快决策续租/退租/购买",
                            "EAM_LEASE_EXPIRY", c.getId());
                } else {
                    noticeService.push("ROLE_ASSET_ADMIN", null, "租赁临期预警（剩 6 月）",
                            c.getLseNo() + "（" + c.getAssetDesc() + "）剩 " + months + " 月到期，请评估续租/退租/购买",
                            "EAM_LEASE_EXPIRY", c.getId());
                }
            } catch (Exception ex) {
                log.warn("临期通知推送失败（不阻断）：{}", ex.getMessage());
            }
        }
        return out;
    }

    // ================= 到期终止（BR-43）=================

    @Override
    @Transactional
    public Map<String, Object> terminate(String contractId, String type,
                                         BigDecimal damageAmount, String reason) {
        EamLeaseContract c = require(contractId);
        if (!"CONFIRMED".equals(c.getStatus()) && !"MATURED".equals(c.getStatus())) {
            throw new ServiceException(422, "仅已确认/到期合同可终止（当前 " + c.getStatus() + "）");
        }
        String t = type == null ? "" : type.trim().toUpperCase();
        if (!"RETURN".equals(t) && !"BUY".equals(t)) {
            throw new ServiceException(422, "终止类型取值域：RETURN 退租 / BUY 购买");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        BigDecimal liabilityBal = BigDecimal.ZERO;
        if (c.getRouCardId() != null) {
            // 租赁负债余额 = 最后已处理行期末（无已处理 → T0 期末 = PV）
            List<EamLeaseSchedule> current = scheduleDao.selectByContract(contractId);
            EamLeaseSchedule lastProcessed = null;
            for (EamLeaseSchedule r : current) {
                if (r.getSeq() > 0 && "1".equals(r.getProcessedFlag())) {
                    lastProcessed = r;
                }
            }
            liabilityBal = lastProcessed == null
                    ? (current.isEmpty() ? nz(c.getPv()) : current.get(0).getEndBal())
                    : lastProcessed.getEndBal();
            // 全部处理完时核销差异校验（终态 END_BAL 应≈0；BR-43 L1）
            boolean allProcessed = current.stream().filter(r -> r.getSeq() > 0)
                    .allMatch(r -> "1".equals(r.getProcessedFlag()));
            if (allProcessed && !current.isEmpty()) {
                BigDecimal finalBal = current.get(current.size() - 1).getEndBal();
                BigDecimal tolerance = sysParamService.getAmount(PARAM_TOLERANCE, BigDecimal.ZERO);
                if (finalBal.abs().compareTo(tolerance) > 0) {
                    throw new ServiceException(422, "BR-4.13-43 核销差异超容差 L1 阻断：租赁负债期末余额 "
                            + finalBal + "（容差 " + tolerance + "），请先处理差异明细");
                }
                liabilityBal = finalBal;
            }
        }

        if ("BUY".equals(t)) {
            if (!"1".equals(c.getBuyOption()) || c.getBuyPrice() == null || c.getBuyPrice().signum() <= 0) {
                throw new ServiceException(422, "该合同无有效购买选择权/约定价（BUY_OPTION/BUY_PRICE）");
            }
            // 购买转自有：新卡原值 = BUY_PRICE + 凭证 借固定资产/贷清理
            EamAssetCard rouCard = c.getRouCardId() == null ? null : cardDao.selectById(c.getRouCardId());
            EamAssetCard card = new EamAssetCard();
            card.setCardNo(nextCardNo());
            card.setAssetName("购-" + c.getAssetDesc());
            card.setCategoryCode(rouCard == null ? "ROU" : rouCard.getCategoryCode());
            card.setAssetCost(c.getBuyPrice());
            card.setLifeYears(rouCard == null ? 5 : rouCard.getLifeYears());
            card.setResidualRate(rouCard == null ? BigDecimal.ZERO : rouCard.getResidualRate());
            card.setResidualValue(rouCard == null ? BigDecimal.ZERO : rouCard.getResidualValue());
            card.setDepreciationMethod("STRAIGHT_LINE");
            card.setEffectiveDate(LocalDate.now());
            card.setStatus("IN_USE");
            card.setDeprStatus("NOT_DEPRECIATED");
            card.setAccumDepr(BigDecimal.ZERO);
            card.setLockFlag("1");
            card.setSourceCapNo(c.getLseNo());
            card.setCreateBy(SecurityUtils.getCurrentUserId());
            cardDao.insert(card);
            String summary = "租赁购买转自有：" + c.getLseNo() + " / 买价 " + c.getBuyPrice();
            var buyVoucher = voucherService.create("LSE", LocalDate.now(), summary,
                    "LEASE_BUY", c.getLseNo(), null, List.of(
                    FinVoucherLineSpec.of(ACCT_FIXED, "DR", c.getBuyPrice(), summary),
                    FinVoucherLineSpec.of(ACCT_CLEARING, "CR", c.getBuyPrice(), summary)));
            out.put("newCard", card);
            out.put("buyVoucherId", buyVoucher.getId());
        }

        // ROU 处置核销（借 1606 NBV + 借 1602 累计折旧 / 贷 1621 原值）
        if (c.getRouCardId() != null) {
            EamAssetCard rouCard = cardDao.selectById(c.getRouCardId());
            BigDecimal cost = nz(rouCard.getAssetCost());
            BigDecimal accum = nz(rouCard.getAccumDepr());
            BigDecimal nbv = cost.subtract(accum).setScale(2, RoundingMode.HALF_UP);
            String summary = "租赁退租核销：" + c.getLseNo() + " / NBV " + nbv;
            List<FinVoucherLineSpec> lines = new ArrayList<>();
            if (nbv.signum() != 0) {
                lines.add(FinVoucherLineSpec.of(ACCT_CLEARING, "DR", nbv.abs(), summary));
            }
            if (accum.signum() != 0) {
                lines.add(FinVoucherLineSpec.of(ACCT_ACCUM, "DR", accum, summary));
            }
            lines.add(FinVoucherLineSpec.of(ACCT_ROU, "CR", cost, summary));
            var dispVoucher = voucherService.create("LSE", LocalDate.now(), summary,
                    "LEASE_RETURN", c.getLseNo(), null, lines);
            // 负债核销（借 2701 / 贷 1606）
            if (liabilityBal.signum() != 0) {
                String lsum = "租赁负债核销：" + c.getLseNo() + " / 余额 " + liabilityBal;
                voucherService.create("LSE", LocalDate.now(), lsum,
                        "LEASE_RETURN", c.getLseNo(), null, List.of(
                        FinVoucherLineSpec.of(ACCT_LIAB, "DR", liabilityBal.abs(), lsum),
                        FinVoucherLineSpec.of(ACCT_CLEARING, "CR", liabilityBal.abs(), lsum)));
            }
            // ROU 卡片置 DISPOSED
            EamAssetCard cupd = new EamAssetCard();
            cupd.setId(rouCard.getId());
            cupd.setVerNo(rouCard.getVerNo());
            cupd.setStatus("DISPOSED");
            cupd.setUpdateBy(SecurityUtils.getCurrentUserId());
            cardDao.updateById(cupd);
            out.put("disposeVoucherId", dispVoucher.getId());
            out.put("liabilityBal", liabilityBal);
        }

        // 非正常损坏赔偿挂应收（design D6 简化）
        if (damageAmount != null && damageAmount.signum() > 0) {
            String dsum = "租赁非正常损坏赔偿：" + c.getLseNo() + " / " + damageAmount;
            var dv = voucherService.create("LSE", LocalDate.now(), dsum,
                    "LEASE_DAMAGE", c.getLseNo(), null, List.of(
                    FinVoucherLineSpec.of(ACCT_AR, "DR", damageAmount, dsum),
                    FinVoucherLineSpec.of(ACCT_CLEARING, "CR", damageAmount, dsum)));
            out.put("damageVoucherId", dv.getId());
        }

        EamLeaseContract upd = new EamLeaseContract();
        upd.setId(c.getId());
        upd.setVerNo(c.getVerNo());
        upd.setStatus("TERMINATED");
        upd.setRemark(hasText(reason) ? reason : ("终止类型 " + t));
        upd.setUpdateBy(SecurityUtils.getCurrentUserId());
        contractDao.updateById(upd);
        out.put("contract", contractDao.selectById(c.getId()));
        out.put("type", t);
        return out;
    }

    // ================= 内部 =================

    private record Pending(BigDecimal revisedRate, int extraMonths,
                           BigDecimal revisedPayment, String reason) {
    }

    /** 定值 JSON 手拆（避免引第三方 JSON 依赖）：仅解析本服务写入的 pending 结构 */
    private Pending parsePending(String json) {
        if (!hasText(json) || !json.contains("\"pending\"")) {
            return null;
        }
        try {
            BigDecimal rate = extractDecimal(json, "revisedRate");
            int extra = Integer.parseInt(extractRaw(json, "extraMonths"));
            BigDecimal payment = extractDecimal(json, "revisedPayment");
            String reason = extractRaw(json, "reason");
            return new Pending(rate, extra, payment, reason == null ? "" : reason);
        } catch (Exception ex) {
            throw new ServiceException(422, "CHANGE_JSON 解析失败：" + ex.getMessage());
        }
    }

    private String extractRaw(String json, String key) {
        String marker = "\"" + key + "\":";
        int i = json.indexOf(marker);
        if (i < 0) {
            return null;
        }
        int s = i + marker.length();
        int e = s;
        while (e < json.length() && json.charAt(e) != ',' && json.charAt(e) != '}') {
            e++;
        }
        String v = json.substring(s, e).trim();
        return "null".equals(v) ? null : v.replace("\"", "");
    }

    private BigDecimal extractDecimal(String json, String key) {
        String v = extractRaw(json, key);
        return v == null ? null : new BigDecimal(v);
    }

    // ---- CHANGE_JSON 双槽位工具：pending（重计量参数）+ exemptFlat（豁免已处理月锚 tag=vid,tag=vid）----

    /** 取豁免已处理锚串（无则空串）；值为引号串，需整体提取（内部含逗号） */
    private String extractExempt(String json) {
        if (!hasText(json)) {
            return "";
        }
        String marker = "\"exemptFlat\":\"";
        int i = json.indexOf(marker);
        if (i < 0) {
            return "";
        }
        int s = i + marker.length();
        int e = json.indexOf('"', s);
        return e < 0 ? "" : json.substring(s, e);
    }

    /** 取 pending 原始片段（"pending":{...}，无则空串），供重建时保留 */
    private String extractPendingRaw(String json) {
        if (!hasText(json)) {
            return "";
        }
        int i = json.indexOf("\"pending\":");
        if (i < 0) {
            return "";
        }
        int start = json.indexOf('{', i);
        if (start < 0) {
            return "";
        }
        int depth = 0;
        for (int j = start; j < json.length(); j++) {
            char ch = json.charAt(j);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return json.substring(i, j + 1);
                }
            }
        }
        return "";
    }

    /** 重建 CHANGE_JSON：{"pending":{...},"exemptFlat":"..."}（空槽省略） */
    private String buildJson(String pendingRaw, String exemptFlat) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        if (hasText(pendingRaw)) {
            sb.append(pendingRaw);
            first = false;
        }
        if (hasText(exemptFlat)) {
            if (!first) {
                sb.append(',');
            }
            sb.append("\"exemptFlat\":\"").append(exemptFlat).append('"');
        }
        return sb.append('}').toString();
    }

    private String appendExempt(String flat, String periodTag, String vid) {
        String entry = periodTag + "=" + vid;
        return hasText(flat) ? flat + "," + entry : entry;
    }

    private String removeExempt(String flat, String periodTag) {
        if (!hasText(flat)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String entry : flat.split(",")) {
            if (!entry.startsWith(periodTag + "=")) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(entry);
            }
        }
        return sb.toString();
    }

    private String lookupExempt(String flat, String periodTag) {
        if (!hasText(flat)) {
            return null;
        }
        for (String entry : flat.split(",")) {
            if (entry.startsWith(periodTag + "=")) {
                return entry.substring(periodTag.length() + 1);
            }
        }
        return null;
    }

    private EamLeaseContract require(String id) {
        EamLeaseContract c = id == null ? null : contractDao.selectById(id);
        if (c == null) {
            throw new ServiceException(404, "租赁合同不存在：" + id);
        }
        return c;
    }

    private String nextLseNo() {
        String prefix = "LSE" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        Integer max = contractDao.selectMaxSeq(prefix);
        return prefix + String.format("%04d", (max == null ? 0 : max) + 1);
    }

    private String nextCardNo() {
        String prefix = "ZC" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        Integer max = cardDao.selectMaxSeq(prefix);
        return prefix + String.format("%04d", (max == null ? 0 : max) + 1);
    }

    private void requireRole(String role, String action) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        for (var a : auth.getAuthorities()) {
            String r = a.getAuthority();
            if ("ROLE_ADMIN".equalsIgnoreCase(r) || role.equalsIgnoreCase(r)) {
                return;
            }
        }
        throw new ServiceException(403, "当前角色无权" + action + "（须 " + role + "）");
    }

    private void requirePeriod(String periodTag) {
        if (!hasText(periodTag) || periodTag.length() != 6) {
            throw new ServiceException(422, "会计月格式须为 yyyyMM");
        }
    }

    private String prevPeriod(String periodTag) {
        YearMonth ym = YearMonth.parse(periodTag.substring(0, 4) + "-" + periodTag.substring(4));
        return ym.minusMonths(1).format(DateTimeFormatter.ofPattern("yyyyMM"));
    }

    private LocalDate periodEndDate(String periodTag) {
        return YearMonth.parse(periodTag.substring(0, 4) + "-" + periodTag.substring(4))
                .atEndOfMonth();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
