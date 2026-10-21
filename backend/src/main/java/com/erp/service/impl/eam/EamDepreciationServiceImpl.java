package com.erp.service.impl.eam;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.eam.EamAssetCardDao;
import com.erp.dao.eam.EamDepreciationDetailDao;
import com.erp.dao.eam.EamDepreciationRunDao;
import com.erp.dao.fin.FinVoucherDao;
import com.erp.dao.fin.FinVoucherLineDao;
import com.erp.dao.mdm.MdmCostCenterDao;
import com.erp.eam.DepreciationEngine;
import com.erp.eam.DepreciationEngine.DepreciationResult;
import com.erp.entity.eam.EamAssetCard;
import com.erp.entity.eam.EamDepreciationDetail;
import com.erp.entity.eam.EamDepreciationRun;
import com.erp.entity.mdm.MdmCostCenter;
import com.erp.service.eam.EamDepreciationService;
import com.erp.service.fin.GlVoucherService;
import com.erp.service.fin.GlVoucherService.FinVoucherLineSpec;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 折旧计提实现（add-asset-depreciation，spec asset-depreciation，design D3~D5）。
 * 月度计提：PERIOD_TAG 幂等 → 应提清单 → {@link DepreciationEngine} 三方法计算 →
 * 成本分摊（成本中心 COST_TYPE → SysParam 科目）→ 自动过账凭证 → 台账回写。
 */
@Slf4j
@Service
public class EamDepreciationServiceImpl implements EamDepreciationService {

    private static final String ACCT_PROD = "EAM_DEPR_ACCT_PROD";
    private static final String ACCT_SALES = "EAM_DEPR_ACCT_SALES";
    private static final String ACCT_DEFAULT = "EAM_DEPR_ACCT_DEFAULT";
    private static final String ACCT_CREDIT = "EAM_DEPR_CREDIT";   // 累计折旧（贷方，1602）
    private static final String PARAM_RESIDUAL_ALERT = "DEPRECIATION_RESIDUAL_ALERT";
    private static final String PARAM_TOLERANCE = "TOLERANCE_DEFAULT";

    private final EamDepreciationRunDao runDao;
    private final EamDepreciationDetailDao detailDao;
    private final EamAssetCardDao cardDao;
    private final com.erp.dao.eam.EamAssetChangeDao changeDao;
    private final MdmCostCenterDao costCenterDao;
    private final GlVoucherService voucherService;
    private final FinVoucherLineDao voucherLineDao;
    private final com.erp.service.SysParamService sysParamService;
    private final com.erp.service.approval.ApprovalEngine approvalEngine;

    public EamDepreciationServiceImpl(EamDepreciationRunDao runDao,
                                      EamDepreciationDetailDao detailDao,
                                      EamAssetCardDao cardDao,
                                      com.erp.dao.eam.EamAssetChangeDao changeDao,
                                      MdmCostCenterDao costCenterDao,
                                      GlVoucherService voucherService,
                                      FinVoucherLineDao voucherLineDao,
                                      com.erp.service.SysParamService sysParamService,
                                      com.erp.service.approval.ApprovalEngine approvalEngine) {
        this.runDao = runDao;
        this.detailDao = detailDao;
        this.cardDao = cardDao;
        this.changeDao = changeDao;
        this.costCenterDao = costCenterDao;
        this.voucherService = voucherService;
        this.voucherLineDao = voucherLineDao;
        this.sysParamService = sysParamService;
        this.approvalEngine = approvalEngine;
    }

    // ================= 变动处理（政策变更 POLICY / 减值 IMPAIRMENT，4.1/4.2）=================

    @Override
    public Page<com.erp.entity.eam.EamAssetChange> pageChange(long current, long size,
                                                              String changeType, String cardNo) {
        LambdaQueryWrapper<com.erp.entity.eam.EamAssetChange> qw =
                new LambdaQueryWrapper<com.erp.entity.eam.EamAssetChange>()
                        .eq(hasText(changeType), com.erp.entity.eam.EamAssetChange::getChangeType, changeType)
                        .eq(hasText(cardNo), com.erp.entity.eam.EamAssetChange::getCardNo, cardNo)
                        .orderByDesc(com.erp.entity.eam.EamAssetChange::getCreateDate);
        return changeDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    public Map<String, Object> changePolicy(String cardId, Integer afterLifeYears,
                                            BigDecimal afterResidualRate,
                                            String afterMethod, String reason) {
        return changePolicy(cardId, afterLifeYears, afterResidualRate, afterMethod, reason, null);
    }

    @Override
    @Transactional
    public Map<String, Object> changePolicy(String cardId, Integer afterLifeYears,
                                            BigDecimal afterResidualRate,
                                            String afterMethod, String reason,
                                            String effectivePeriod) {
        EamAssetCard card = requireCard(cardId);
        if (!hasText(reason)) {
            throw new ServiceException(422, "政策变更须填写原因");
        }
        if (afterLifeYears == null || afterLifeYears <= 0) {
            throw new ServiceException(422, "变更后使用年限必须大于 0");
        }
        com.erp.entity.eam.EamAssetChange ch = new com.erp.entity.eam.EamAssetChange();
        ch.setChangeNo(nextChangeNo());
        ch.setChangeType("POLICY");
        ch.setCardId(card.getId());
        ch.setCardNo(card.getCardNo());
        // 快照 before（BR-4.13-14 留痕）
        ch.setBeforeLifeYears(card.getLifeYears());
        ch.setBeforeResidualRate(card.getResidualRate());
        ch.setBeforeMethod(card.getDepreciationMethod());
        // after
        ch.setAfterLifeYears(afterLifeYears);
        ch.setAfterResidualRate(afterResidualRate == null ? card.getResidualRate() : afterResidualRate);
        ch.setAfterMethod(hasText(afterMethod) ? afterMethod : card.getDepreciationMethod());
        // 生效期间 = 指定或系统下期；不得早于当前计提基准期（不追溯已提期间，BR-4.13-14）
        String eff = hasText(effectivePeriod) ? effectivePeriod : nextPeriod(currentPeriod());
        if (eff.compareTo(currentPeriod()) < 0) {
            throw new ServiceException(422, "政策变更生效期间不得早于当期：" + eff);
        }
        ch.setEffectivePeriod(eff);
        ch.setReason(reason);
        ch.setStatus("PENDING_APPROVE");
        ch.setCreateBy(SecurityUtils.getCurrentUserId());
        changeDao.insert(ch);

        // 审批底座单签（同转资口径：ROLE_FIN_ASSET）
        var inst = approvalEngine.submit("AssetDepreciation", ch.getId(),
                "折旧政策变更审核：" + ch.getCardNo() + " / 年限 " + card.getLifeYears()
                        + "→" + afterLifeYears, null,
                List.of(List.of(com.erp.service.approval.ApprovalNodeSpec
                        .sign("ROLE_FIN_ASSET", "财务资产会计审核"))));
        com.erp.entity.eam.EamAssetChange upd = new com.erp.entity.eam.EamAssetChange();
        upd.setId(ch.getId());
        upd.setVerNo(ch.getVerNo());
        upd.setApprId(inst.getId());
        changeDao.updateById(upd);
        return Map.of("changeId", ch.getId(), "apprId", inst.getId(),
                "effectivePeriod", ch.getEffectivePeriod());
    }

    @Override
    @Transactional
    public Map<String, Object> approveChange(String changeId) {
        com.erp.entity.eam.EamAssetChange ch = requireChange(changeId);
        if (!"PENDING_APPROVE".equals(ch.getStatus())) {
            throw new ServiceException(409, "变动单非待审批状态：" + ch.getStatus());
        }
        if ("POLICY".equals(ch.getChangeType())) {
            // 政策变更：置 APPROVED 待生效；下期计提读新政策（快照生效），不改本期卡片
            ch.setStatus("APPROVED");
        } else if ("IMPAIRMENT".equals(ch.getChangeType())) {
            // 减值（4.2）：出减值凭证（借 EAM_DEPR_IMPAIR_CR / 贷 EAM_IMPAIRMENT_RESERVE）
            String drAcct = requireParam("EAM_DEPR_IMPAIR_CR");
            String crAcct = requireParam("EAM_IMPAIRMENT_RESERVE");
            BigDecimal amt = ch.getImpairAmount();
            if (amt == null || amt.signum() <= 0) {
                throw new ServiceException(422, "减值金额必须大于 0");
            }
            String summary = "资产减值：" + ch.getCardNo() + " / 减值准备 " + amt;
            List<FinVoucherLineSpec> lines = List.of(
                    FinVoucherLineSpec.of(drAcct, "DR", amt.setScale(2, RoundingMode.HALF_UP), summary),
                    FinVoucherLineSpec.of(crAcct, "CR", amt.setScale(2, RoundingMode.HALF_UP), summary));
            var voucher = voucherService.create("IMP", LocalDate.now(), summary,
                    "ASSET_IMPAIRMENT", ch.getChangeNo(), null, lines);
            // 卡片 IMPAIRMENT 落值（次月引擎以减值后 NBV 为基数重算）
            EamAssetCard card = requireCard(ch.getCardId());
            BigDecimal newImpair = nz(card.getImpairment()).add(amt);
            EamAssetCard upd = new EamAssetCard();
            upd.setId(card.getId());
            upd.setVerNo(card.getVerNo());
            upd.setImpairment(newImpair.setScale(2, RoundingMode.HALF_UP));
            upd.setUpdateBy(SecurityUtils.getCurrentUserId());
            cardDao.updateById(upd);
            // 减值后 NBV 快照（重算基数留痕）
            BigDecimal afterNbv = nz(card.getAssetCost()).subtract(nz(card.getAccumDepr()))
                    .subtract(newImpair).setScale(2, RoundingMode.HALF_UP);
            ch.setImpairAfterNbv(afterNbv);
            ch.setImpairVoucherId(voucher.getId());
            ch.setStatus("APPROVED");
        } else {
            throw new ServiceException(422, "不支持的变动类型：" + ch.getChangeType());
        }
        ch.setUpdateBy(SecurityUtils.getCurrentUserId());
        changeDao.updateById(ch);
        return Map.of("changeId", ch.getId(), "status", ch.getStatus());
    }

    @Override
    @Transactional
    public Map<String, Object> impairment(String cardId, BigDecimal amount, String reason) {
        EamAssetCard card = requireCard(cardId);
        if (amount == null || amount.signum() <= 0) {
            throw new ServiceException(422, "减值金额必须大于 0");
        }
        if (!hasText(reason)) {
            throw new ServiceException(422, "减值须填写原因");
        }
        com.erp.entity.eam.EamAssetChange ch = new com.erp.entity.eam.EamAssetChange();
        ch.setChangeNo(nextChangeNo());
        ch.setChangeType("IMPAIRMENT");
        ch.setCardId(card.getId());
        ch.setCardNo(card.getCardNo());
        ch.setImpairAmount(amount);
        ch.setReason(reason);
        ch.setStatus("PENDING_APPROVE");
        ch.setCreateBy(SecurityUtils.getCurrentUserId());
        changeDao.insert(ch);

        var inst = approvalEngine.submit("AssetDepreciation", ch.getId(),
                "资产减值审核：" + ch.getCardNo() + " / 减值 " + amount, null,
                List.of(List.of(com.erp.service.approval.ApprovalNodeSpec
                        .sign("ROLE_FIN_ASSET", "财务资产会计审核"))));
        com.erp.entity.eam.EamAssetChange upd = new com.erp.entity.eam.EamAssetChange();
        upd.setId(ch.getId());
        upd.setVerNo(ch.getVerNo());
        upd.setApprId(inst.getId());
        changeDao.updateById(upd);
        return Map.of("changeId", ch.getId(), "apprId", inst.getId());
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<com.erp.entity.fin.FinVoucherLine> detailsByVoucher(String voucherId) {
        // 7.2.3 凭证生成：凭证明细=会计分录行（行号/科目/方向/金额/摘要），
        // 借贷平由凭证底座校验（FR-4.6-5-5）。折旧明细（一卡一行）另见 runDetail。
        Map<String, Object> voucher = voucherService.detail(voucherId);
        Object lines = voucher == null ? null : voucher.get("lines");
        return lines == null ? List.of()
                : (List<com.erp.entity.fin.FinVoucherLine>) lines;
    }

    @Override
    @Transactional
    public Map<String, Object> rejectChange(String changeId, String reason) {
        com.erp.entity.eam.EamAssetChange ch = requireChange(changeId);
        ch.setStatus("REJECTED");
        ch.setReason(hasText(reason) ? reason : ch.getReason());
        ch.setUpdateBy(SecurityUtils.getCurrentUserId());
        changeDao.updateById(ch);
        return Map.of("changeId", ch.getId(), "status", "REJECTED");
    }

    private com.erp.entity.eam.EamAssetChange requireChange(String id) {
        com.erp.entity.eam.EamAssetChange ch = changeDao.selectById(id);
        if (ch == null) {
            throw new ServiceException(404, "变动单不存在：" + id);
        }
        return ch;
    }

    private EamAssetCard requireCard(String id) {
        EamAssetCard card = cardDao.selectById(id);
        if (card == null) {
            throw new ServiceException(404, "资产卡片不存在：" + id);
        }
        return card;
    }

    private String nextChangeNo() {
        String prefix = "BD" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        Integer max = changeDao.selectMaxSeq(prefix);
        return prefix + String.format("%04d", (max == null ? 0 : max) + 1);
    }

    private String currentPeriod() {
        return YearMonth.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
    }

    private String nextPeriod(String periodTag) {
        YearMonth ym = YearMonth.parse(periodTag.substring(0, 4) + "-" + periodTag.substring(4));
        return ym.plusMonths(1).format(DateTimeFormatter.ofPattern("yyyyMM"));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    // ================= 分页 / 详情 =================

    @Override
    public Page<EamDepreciationRun> page(long current, long size, String periodTag) {
        LambdaQueryWrapper<EamDepreciationRun> qw = new LambdaQueryWrapper<EamDepreciationRun>()
                .eq(hasText(periodTag), EamDepreciationRun::getPeriodTag, periodTag)
                .orderByDesc(EamDepreciationRun::getPeriodTag);
        return runDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    public Map<String, Object> detail(String runId) {
        EamDepreciationRun run = runDao.selectById(runId);
        if (run == null) {
            throw new ServiceException(404, "计提单不存在：" + runId);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run", run);
        out.put("details", detailDao.selectList(new LambdaQueryWrapper<EamDepreciationDetail>()
                .eq(EamDepreciationDetail::getRunId, runId)
                .orderByAsc(EamDepreciationDetail::getCardNo)));
        return out;
    }

    // ================= 应提预览（未落库）=================

    @Override
    public Map<String, Object> preview(String periodTag) {
        requirePeriod(periodTag);
        List<String> missing = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (EamAssetCard card : depreciableCards(periodTag, missing)) {
            DepreciationResult r = computeFor(card, periodTag);
            total = total.add(r.periodDepreciation());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("cardNo", card.getCardNo());
            row.put("assetName", card.getAssetName());
            row.put("method", card.getDepreciationMethod());
            row.put("beginNbv", r.beginNbv());
            row.put("periodDepreciation", r.periodDepreciation());
            row.put("endAccumDepreciation", r.endAccumDepreciation());
            row.put("endNbv", r.endNbv());
            row.put("residualAlert", r.residualAlert());
            row.put("capped", r.capped());
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("periodTag", periodTag);
        out.put("cards", rows);
        out.put("totalDepreciation", total.setScale(2, RoundingMode.HALF_UP));
        out.put("missingParams", missing);
        out.put("alreadyRun", runDao.selectByPeriod(periodTag) != null);
        return out;
    }

    // ================= 执行计提（FR-4.6-5-1~7）=================

    @Override
    @Transactional
    public Map<String, Object> runDepreciation(String periodTag) {
        requirePeriod(periodTag);
        // 幂等卡控：PERIOD_TAG 唯一（冲回后软删放行）
        if (runDao.selectByPeriod(periodTag) != null) {
            throw new ServiceException(409, "该会计月已计提，请先冲回后重提（" + periodTag + "）");
        }
        // 政策变更生效（BR-4.13-14）：下期生效，先落卡片再计提
        int applied = applyApprovedPolicy(periodTag);
        // 应提清单：参数缺失 → 阻断并提示补录
        List<String> missing = new ArrayList<>();
        List<EamAssetCard> cards = depreciableCards(periodTag, missing);
        if (!missing.isEmpty()) {
            throw new ServiceException(422, "以下卡片折旧参数缺失，请补录后重提：" + String.join("；", missing));
        }
        if (cards.isEmpty()) {
            throw new ServiceException(422, "该会计月无可计提卡片（均未到起提月或已提足）");
        }

        // 科目前置校验（凭证取数硬前置）
        String acctDefault = requireParam(ACCT_DEFAULT);

        BigDecimal alertThreshold = sysParamService.getRate(PARAM_RESIDUAL_ALERT,
                DepreciationEngine.DEFAULT_RESIDUAL_ALERT_THRESHOLD);

        // ---- 逐卡计算 + 明细 ----
        EamDepreciationRun run = new EamDepreciationRun();
        run.setRunNo(nextRunNo(periodTag));
        run.setPeriodTag(periodTag);
        run.setStatus("POSTED");
        run.setReversedFlag("0");
        run.setTotalDepr(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        run.setReconDiff(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        run.setWarningCount(0);
        run.setCreateBy(SecurityUtils.getCurrentUserId());
        runDao.insert(run);

        List<EamDepreciationDetail> details = new ArrayList<>(cards.size());
        BigDecimal total = BigDecimal.ZERO;
        int alertCount = 0;
        for (EamAssetCard card : cards) {
            DepreciationResult r = computeFor(card, periodTag, alertThreshold);
            EamDepreciationDetail d = new EamDepreciationDetail();
            d.setRunId(run.getId());
            d.setPeriodTag(periodTag);
            d.setCardId(card.getId());
            d.setCardNo(card.getCardNo());
            d.setAssetName(card.getAssetName());
            d.setAssetCost(card.getAssetCost());
            d.setDepreciationMethod(card.getDepreciationMethod());
            d.setBeginAccumDepr(accumOf(card));
            d.setPeriodDepr(r.periodDepreciation());
            d.setEndAccumDepr(r.endAccumDepreciation());
            d.setEndNbv(r.endNbv());
            d.setCostCenterCode(card.getCostCenterCode());
            d.setDebitAccount(debitAccountFor(card.getCostCenterCode(), acctDefault));
            d.setCapped(r.capped() ? "1" : "0");
            d.setResidualAlert(r.residualAlert() ? "1" : "0");
            d.setReversed("0");
            d.setCreateBy(SecurityUtils.getCurrentUserId());
            detailDao.insert(d);
            details.add(d);
            total = total.add(r.periodDepreciation());
            if (r.residualAlert()) {
                alertCount++;
            }
        }
        run.setTotalDepr(total.setScale(2, RoundingMode.HALF_UP));
        run.setWarningCount(alertCount);

        // ---- BR-4.13-13 勾稽（凭证生成前）：本期其他已过账折旧凭证 vs 本期应计折旧 ----
        // 口径：本期尚无本次凭证，glAmount 为其他来源折旧凭证的借方合计；diff 记入 run 供审计。
        BigDecimal glAmount = sumGlDepreciation(periodTag);
        BigDecimal reconDiff = total.subtract(glAmount).abs();
        run.setReconDiff(reconDiff.setScale(2, RoundingMode.HALF_UP));

        // ---- 折旧凭证（design D5：借方按 成本中心×费用科目 汇总多行 / 贷 累计折旧 一行，自动过账）----
        String creditAccount = requireParam(ACCT_CREDIT);
        String summary = "月度折旧计提 " + periodTag + "（共 " + cards.size() + " 张卡）";
        List<FinVoucherLineSpec> lines = new ArrayList<>();
        for (Map<String, Object> g : detailDao.sumByCostCenterAndAccount(run.getId())) {
            String acct = g.get("debitAccount") == null ? acctDefault : g.get("debitAccount").toString();
            BigDecimal amt = (BigDecimal) g.get("amount");
            lines.add(FinVoucherLineSpec.of(acct, "DR", amt.setScale(2, RoundingMode.HALF_UP), summary));
        }
        lines.add(FinVoucherLineSpec.of(creditAccount, "CR",
                total.setScale(2, RoundingMode.HALF_UP), summary));
        var voucher = voucherService.create("DEP", periodEndDate(periodTag), summary,
                "DEPRECIATION", run.getRunNo(), null, lines);
        run.setVoucherId(voucher.getId());
        run.setUpdateBy(SecurityUtils.getCurrentUserId());
        runDao.updateById(run);

        // ---- 台账回写（FR-4.6-5-7）----
        for (int i = 0; i < cards.size(); i++) {
            EamAssetCard card = cards.get(i);
            DepreciationResult r = computeFor(card, periodTag, alertThreshold);
            EamDepreciationDetail d = details.get(i);
            d.setVoucherId(voucher.getId());
            detailDao.updateById(d);
            EamAssetCard upd = new EamAssetCard();
            upd.setId(card.getId());
            upd.setVerNo(card.getVerNo());
            upd.setAccumDepr(r.endAccumDepreciation());
            upd.setDeprStatus(r.fullyDepreciated() ? "FULLY_DEPRECIATED" : "DEPRECIATING");
            upd.setUpdateBy(SecurityUtils.getCurrentUserId());
            if (cardDao.updateById(upd) == 0) {
                throw new ServiceException(409, "卡片台账更新冲突：" + card.getCardNo() + "，请手动更新后重试（FR-4.6-5-7）");
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run", run);
        out.put("voucherId", voucher.getId());
        out.put("cardCount", cards.size());
        out.put("totalDepreciation", total.setScale(2, RoundingMode.HALF_UP));
        out.put("residualAlertCount", alertCount);
        out.put("policyApplied", applied);
        return out;
    }

    // ================= 冲回（整单全冲）=================

    @Override
    @Transactional
    public Map<String, Object> reverse(String runId, String reason) {
        EamDepreciationRun run = runDao.selectById(runId);
        if (run == null) {
            throw new ServiceException(404, "计提单不存在：" + runId);
        }
        if ("1".equals(run.getReversedFlag())) {
            throw new ServiceException(409, "该计提单已冲回");
        }
        if (!hasText(run.getVoucherId())) {
            throw new ServiceException(422, "计提单无关联凭证，不可冲回");
        }

        // 1) 红冲折旧凭证（BR-4.6-09）
        var reversed = voucherService.reverse(run.getVoucherId(),
                hasText(reason) ? reason : "折旧冲回", run.getRunNo());

        // 2) 台账回滚（逐卡减去本期折旧）
        List<EamDepreciationDetail> details = detailDao.selectList(
                new LambdaQueryWrapper<EamDepreciationDetail>()
                        .eq(EamDepreciationDetail::getRunId, runId));
        for (EamDepreciationDetail d : details) {
            EamAssetCard card = cardDao.selectById(d.getCardId());
            if (card == null) {
                continue;
            }
            BigDecimal newAccum = (card.getAccumDepr() == null ? BigDecimal.ZERO : card.getAccumDepr())
                    .subtract(d.getPeriodDepr());
            if (newAccum.signum() < 0) {
                newAccum = BigDecimal.ZERO;
            }
            EamAssetCard upd = new EamAssetCard();
            upd.setId(card.getId());
            upd.setVerNo(card.getVerNo());
            upd.setAccumDepr(newAccum);
            upd.setDeprStatus(newAccum.signum() > 0 ? "DEPRECIATING" : "NOT_DEPRECIATED");
            upd.setUpdateBy(SecurityUtils.getCurrentUserId());
            cardDao.updateById(upd);
            // 明细标冲回
            EamDepreciationDetail dupd = new EamDepreciationDetail();
            dupd.setId(d.getId());
            dupd.setVerNo(d.getVerNo());
            dupd.setReversed("1");
            dupd.setUpdateBy(SecurityUtils.getCurrentUserId());
            detailDao.updateById(dupd);
        }

        // 3) 计提单标冲回 + 软删（放行 PERIOD_TAG 重提）
        EamDepreciationRun rupd = new EamDepreciationRun();
        rupd.setId(run.getId());
        rupd.setVerNo(run.getVerNo());
        rupd.setStatus("REVERSED");
        rupd.setReversedFlag("1");
        rupd.setReverseChangeId(reversed.getId());
        rupd.setUpdateBy(SecurityUtils.getCurrentUserId());
        runDao.updateById(rupd);
        runDao.deleteById(run.getId());   // @TableLogic 软删 → PERIOD_TAG 唯一索引放行

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", runId);
        out.put("reversedVoucherId", reversed.getId());
        out.put("cardCount", details.size());
        return out;
    }

    // ================= 内部 =================

    /** 应提清单：IN_USE ∧ 起提月 ≤ 本期 ∧ 非 FULLY_DEPRECIATED；参数缺失入 missing */
    private List<EamAssetCard> depreciableCards(String periodTag, List<String> missing) {
        List<EamAssetCard> out = new ArrayList<>();
        List<EamAssetCard> cards = cardDao.selectList(new LambdaQueryWrapper<EamAssetCard>()
                .eq(EamAssetCard::getStatus, "IN_USE")
                .eq(EamAssetCard::getDelFlag, "0"));
        for (EamAssetCard card : cards) {
            if ("FULLY_DEPRECIATED".equals(card.getDeprStatus())) {
                continue;
            }
            // 参数缺失（FR-4.6-5-1）
            if (card.getAssetCost() == null || card.getAssetCost().signum() <= 0
                    || card.getLifeYears() == null || card.getLifeYears() <= 0
                    || card.getResidualRate() == null
                    || card.getEffectiveDate() == null) {
                missing.add(card.getCardNo() + "（原值/年限/残值率/启用日缺失）");
                continue;
            }
            String startMonth = card.getDeprStartMonth();
            if (!hasText(startMonth)) {
                startMonth = DepreciationEngine.startMonth(card.getEffectiveDate());
            }
            if (!DepreciationEngine.isDepreciable(periodTag, startMonth)) {
                continue;   // 未到起提月（BR-4.13-11）
            }
            out.add(card);
        }
        return out;
    }

    /**
     * 政策变更生效（BR-4.13-14）：把 EFFECTIVE_PERIOD ≤ 本期 且状态 APPROVED 的 POLICY
     * 变更回写卡片三政策字段，下期计提自动读新政策，不追溯已计提期间。返回应用条数。
     */
    private int applyApprovedPolicy(String periodTag) {
        List<com.erp.entity.eam.EamAssetChange> list = changeDao.selectList(
                new LambdaQueryWrapper<com.erp.entity.eam.EamAssetChange>()
                        .eq(com.erp.entity.eam.EamAssetChange::getChangeType, "POLICY")
                        .eq(com.erp.entity.eam.EamAssetChange::getStatus, "APPROVED")
                        .isNotNull(com.erp.entity.eam.EamAssetChange::getEffectivePeriod));
        int n = 0;
        for (com.erp.entity.eam.EamAssetChange ch : list) {
            if (ch.getEffectivePeriod().compareTo(periodTag) > 0) {
                continue; // 未到生效期间
            }
            EamAssetCard card = cardDao.selectById(ch.getCardId());
            if (card == null) {
                continue;
            }
            EamAssetCard upd = new EamAssetCard();
            upd.setId(card.getId());
            upd.setVerNo(card.getVerNo());
            upd.setLifeYears(ch.getAfterLifeYears());
            upd.setResidualRate(ch.getAfterResidualRate());
            upd.setDepreciationMethod(ch.getAfterMethod());
            upd.setUpdateBy(SecurityUtils.getCurrentUserId());
            cardDao.updateById(upd);
            // 生效后置 DONE，避免下期重复应用
            com.erp.entity.eam.EamAssetChange done = new com.erp.entity.eam.EamAssetChange();
            done.setId(ch.getId());
            done.setVerNo(ch.getVerNo());
            done.setStatus("DONE");
            changeDao.updateById(done);
            n++;
        }
        return n;
    }

    /** 计算单卡本期折旧（已提月数 = 起提月~上一月） */
    private DepreciationResult computeFor(EamAssetCard card, String periodTag) {
        return computeFor(card, periodTag, sysParamService.getRate(PARAM_RESIDUAL_ALERT,
                DepreciationEngine.DEFAULT_RESIDUAL_ALERT_THRESHOLD));
    }

    private DepreciationResult computeFor(EamAssetCard card, String periodTag, BigDecimal alertThreshold) {
        String startMonth = hasText(card.getDeprStartMonth())
                ? card.getDeprStartMonth()
                : DepreciationEngine.startMonth(card.getEffectiveDate());
        int already = Math.max(0, DepreciationEngine.monthsDepreciated(prevPeriod(periodTag), startMonth));
        BigDecimal residualValue = card.getResidualValue() != null ? card.getResidualValue()
                : card.getAssetCost().multiply(card.getResidualRate() == null ? BigDecimal.ZERO : card.getResidualRate())
                        .setScale(2, RoundingMode.HALF_UP);
        BigDecimal accum = card.getAccumDepr() == null ? BigDecimal.ZERO : card.getAccumDepr();
        BigDecimal impairment = card.getImpairment() == null ? BigDecimal.ZERO : card.getImpairment();
        return DepreciationEngine.compute(card.getAssetCost(), residualValue, card.getLifeYears(),
                card.getDepreciationMethod(), already, accum, impairment, alertThreshold);
    }

    /** 成本中心 COST_TYPE → 借方费用科目（FR-4.6-5-4） */
    private String debitAccountFor(String costCenterCode, String defaultAccount) {
        if (!hasText(costCenterCode)) {
            return defaultAccount;
        }
        MdmCostCenter cc = costCenterDao.selectOne(new LambdaQueryWrapper<MdmCostCenter>()
                .eq(MdmCostCenter::getCcCode, costCenterCode)
                .eq(MdmCostCenter::getDelFlag, "0")
                .last("LIMIT 1"));
        if (cc == null || cc.getCostType() == null) {
            return defaultAccount;
        }
        String key = "PROD".equals(cc.getCostType()) ? ACCT_PROD
                : "SALES".equals(cc.getCostType()) ? ACCT_SALES : ACCT_DEFAULT;
        String acct = sysParamService.getValue(key);
        return hasText(acct) ? acct : defaultAccount;
    }

    /**
     * 本期已过账折旧凭证（sourceType=DEPRECIATION）借方发生额合计（BR-4.13-13 勾稽取数）。
     * 用于在生成本次凭证前，比对"本期其他折旧凭证"与"本期应计折旧"的差异（L4 差异预警）。
     */
    private BigDecimal sumGlDepreciation(String periodTag) {
        BigDecimal v = voucherLineDao.sumDrByPeriodAndSourceType(periodTag, "DEPRECIATION");
        return v == null ? BigDecimal.ZERO : v;
    }

    private LocalDate periodEndDate(String periodTag) {
        return YearMonth.parse(periodTag.substring(0, 4) + "-" + periodTag.substring(4)).atEndOfMonth();
    }

    private String prevPeriod(String periodTag) {
        YearMonth ym = YearMonth.parse(periodTag.substring(0, 4) + "-" + periodTag.substring(4));
        return ym.minusMonths(1).format(DateTimeFormatter.ofPattern("yyyyMM"));
    }

    /** 卡片期初累计折旧（空值按 0） */
    private static BigDecimal accumOf(EamAssetCard card) {
        return card.getAccumDepr() == null ? BigDecimal.ZERO : card.getAccumDepr();
    }

    private String nextRunNo(String periodTag) {
        String prefix = "JZ" + periodTag + "-";
        Integer max = runDao.selectMaxSeq(prefix);
        return prefix + String.format("%04d", (max == null ? 0 : max) + 1);
    }

    private void requirePeriod(String periodTag) {
        if (!hasText(periodTag) || periodTag.length() != 6) {
            throw new ServiceException(422, "会计月格式须为 yyyyMM");
        }
    }

    private String requireParam(String key) {
        String v = sysParamService.getValue(key);
        if (!hasText(v)) {
            throw new ServiceException(422, "系统参数 " + key + " 未配置，折旧凭证取数缺失");
        }
        return v;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
