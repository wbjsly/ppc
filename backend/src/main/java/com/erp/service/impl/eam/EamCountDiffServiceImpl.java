package com.erp.service.impl.eam;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.eam.EamAssetCardDao;
import com.erp.dao.eam.EamCountDiffDao;
import com.erp.dao.eam.EamCountLineDao;
import com.erp.dao.eam.EamCountPlanDao;
import com.erp.entity.eam.EamAssetCard;
import com.erp.entity.eam.EamCountDiff;
import com.erp.entity.eam.EamCountLine;
import com.erp.entity.eam.EamCountPlan;
import com.erp.service.eam.EamCountDiffService;
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
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 盘点差异实现（add-asset-count，spec asset-count，design D2/D5/D6）。
 * 四类差异自动比对生成；逐单分级审批（阈值参数）；盘盈自动带价建卡（BR-27）；
 * 盘亏两段式：挂账 1901 → 责任认定 → 核销（BR-28）；标签异常冻结调拨处置（BR-30）。
 */
@Slf4j
@Service
public class EamCountDiffServiceImpl implements EamCountDiffService {

    private final EamCountDiffDao diffDao;
    private final EamCountLineDao lineDao;
    private final EamCountPlanDao planDao;
    private final EamAssetCardDao cardDao;
    private final GlVoucherService voucherService;
    private final NoticeService noticeService;
    private final com.erp.service.SysParamService sysParamService;
    private final com.erp.service.approval.ApprovalEngine approvalEngine;

    public EamCountDiffServiceImpl(EamCountDiffDao diffDao,
                                   EamCountLineDao lineDao,
                                   EamCountPlanDao planDao,
                                   EamAssetCardDao cardDao,
                                   GlVoucherService voucherService,
                                   NoticeService noticeService,
                                   com.erp.service.SysParamService sysParamService,
                                   com.erp.service.approval.ApprovalEngine approvalEngine) {
        this.diffDao = diffDao;
        this.lineDao = lineDao;
        this.planDao = planDao;
        this.cardDao = cardDao;
        this.voucherService = voucherService;
        this.noticeService = noticeService;
        this.sysParamService = sysParamService;
        this.approvalEngine = approvalEngine;
    }

    // ================= 分页 / 详情 =================

    @Override
    public Page<EamCountDiff> page(long current, long size, String planId,
                                   String diffType, String status) {
        LambdaQueryWrapper<EamCountDiff> qw = new LambdaQueryWrapper<EamCountDiff>()
                .eq(hasText(planId), EamCountDiff::getPlanId, planId)
                .eq(hasText(diffType), EamCountDiff::getDiffType, diffType)
                .eq(hasText(status), EamCountDiff::getStatus, status)
                .orderByDesc(EamCountDiff::getCreateDate);
        return diffDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    public Map<String, Object> detail(String id) {
        EamCountDiff diff = require(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("diff", diff);
        EamCountLine line = lineDao.selectById(diff.getLineId());
        out.put("line", line);
        if (line != null && hasText(line.getCardId())) {
            out.put("card", cardDao.selectById(line.getCardId()));
        }
        return out;
    }

    // ================= 自动比对（FR-4.13-5-4）=================

    @Override
    @Transactional
    public Map<String, Object> autoCompare(String planId) {
        EamCountPlan plan = planDao.selectById(planId);
        if (plan == null) {
            throw new ServiceException(404, "盘点单不存在：" + planId);
        }
        List<EamCountLine> lines = lineDao.selectList(new LambdaQueryWrapper<EamCountLine>()
                .eq(EamCountLine::getPlanId, planId));
        int created = 0;
        int skipped = 0;
        for (EamCountLine line : lines) {
            String type = classifyDiff(line);
            if (type == null) {
                continue;   // 四实态无差异
            }
            // 幂等：该行已有同类型未关闭差异 → 跳过
            Long exist = diffDao.selectCount(new LambdaQueryWrapper<EamCountDiff>()
                    .eq(EamCountDiff::getLineId, line.getId())
                    .eq(EamCountDiff::getDiffType, type)
                    .ne(EamCountDiff::getStatus, "CLOSED"));
            if (exist != null && exist > 0) {
                skipped++;
                continue;
            }
            EamCountDiff diff = newDiff(plan, line, type);
            // BR-30 / spec：账实状态冲突 → 差异单即冻结该资产（禁止再次处置与调拨）
            if ("STATUS_CONFLICT".equals(type)) {
                diff.setFrozenFlag("1");
                EamAssetCard card = line.getCardId() == null ? null
                        : cardDao.selectById(line.getCardId());
                if (card != null && !"1".equals(card.getCountFreezeFlag())) {
                    EamAssetCard cupd = new EamAssetCard();
                    cupd.setId(card.getId());
                    cupd.setVerNo(card.getVerNo());
                    cupd.setCountFreezeFlag("1");
                    cupd.setUpdateBy(SecurityUtils.getCurrentUserId());
                    cardDao.updateById(cupd);
                }
            }
            // 盘盈自动带价（BR-27）
            if ("OVER".equals(type)) {
                BigDecimal price = suggestedPrice(line.getSnapCategory());
                diff.setSuggestedPrice(price);
                diff.setDiffAmount(price);
                diff.setPriceBasis(price == null
                        ? "无计价依据（同分类近 12 月无转资）→ 升级财务经理裁定"
                        : "同分类近 12 月转资原值均价（BR-4.13-27）");
            } else if ("SHORT".equals(type)) {
                diff.setDiffAmount(line.getSnapNbv() == null ? BigDecimal.ZERO : line.getSnapNbv());
            }
            diffDao.insert(diff);
            // 行差异标记回写
            EamCountLine lup = new EamCountLine();
            lup.setId(line.getId());
            lup.setVerNo(line.getVerNo());
            lup.setDiffFlag("1");
            lup.setDiffType(type);
            lup.setUpdateBy(SecurityUtils.getCurrentUserId());
            lineDao.updateById(lup);
            created++;
        }
        // 状态推进：COUNTING/DIFF_REVIEW
        if (!"CLOSED".equals(plan.getStatus()) && !"ADJUSTING".equals(plan.getStatus())) {
            EamCountPlan upd = new EamCountPlan();
            upd.setId(plan.getId());
            upd.setVerNo(plan.getVerNo());
            upd.setStatus("DIFF_REVIEW");
            upd.setUpdateBy(SecurityUtils.getCurrentUserId());
            planDao.updateById(upd);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", created);
        out.put("skipped", skipped);
        return out;
    }

    /** 实盘状态 → 差异类型（四实态 null；SURPLUS→OVER；MISSING→SHORT；STATUS_CONFLICT 透传；UNKNOWN→PENDING_CONFIRM） */
    private String classifyDiff(EamCountLine line) {
        String st = line.getActualStatus();
        if (!hasText(st)) {
            return null;
        }
        switch (st) {
            case "SURPLUS":
                return "OVER";
            case "MISSING":
                return "SHORT";
            case "UNKNOWN":
                return "PENDING_CONFIRM";
            default:
                // 快照内卡已处置但实物在场 → 账实状态冲突（录入时已标）
                return hasText(line.getDiffType()) && "STATUS_CONFLICT".equals(line.getDiffType())
                        ? "STATUS_CONFLICT" : null;
        }
    }

    private EamCountDiff newDiff(EamCountPlan plan, EamCountLine line, String type) {
        EamCountDiff diff = new EamCountDiff();
        diff.setPlanId(plan.getId());
        diff.setCntNo(plan.getCntNo());
        diff.setLineId(line.getId());
        diff.setDiffType(type);
        diff.setCardId(line.getCardId());
        diff.setCardNo(line.getCardNo());
        diff.setSnapName(line.getSnapName());
        diff.setSnapDept(line.getSnapDept());
        diff.setSnapCc(line.getSnapCc());
        diff.setSnapCategory(line.getSnapCategory());
        diff.setSnapCost(line.getSnapCost());
        diff.setSnapAccum(line.getSnapAccum());
        diff.setSnapNbv(line.getSnapNbv());
        diff.setStatus("PENDING");
        diff.setAdjustStatus("NONE");
        diff.setFrozenFlag("0");
        diff.setCreateBy(SecurityUtils.getCurrentUserId());
        return diff;
    }

    /**
     * 盘盈建议价（BR-27）：同分类卡片近 12 月内启用（≈转资）的原值均价；
     * 无参考 → null（提交审批时 422 阻断升级财务经理）。
     */
    private BigDecimal suggestedPrice(String categoryCode) {
        if (!hasText(categoryCode)) {
            return null;
        }
        LocalDate since = LocalDate.now().minusMonths(12);
        List<EamAssetCard> refs = cardDao.selectList(new LambdaQueryWrapper<EamAssetCard>()
                .eq(EamAssetCard::getCategoryCode, categoryCode)
                .ge(EamAssetCard::getEffectiveDate, since)
                .isNotNull(EamAssetCard::getAssetCost));
        if (refs.isEmpty()) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        int n = 0;
        for (EamAssetCard c : refs) {
            if (c.getAssetCost() != null && c.getAssetCost().signum() > 0) {
                sum = sum.add(c.getAssetCost());
                n++;
            }
        }
        if (n == 0) {
            return null;
        }
        return sum.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);
    }

    // ================= 调查留痕 =================

    @Override
    @Transactional
    public Map<String, Object> investigate(String id, String reason, String evidence) {
        EamCountDiff diff = require(id);
        if (!List.of("PENDING", "LIABILITY_PENDING").contains(diff.getStatus())) {
            throw new ServiceException(422, "当前状态不可调查留痕（" + diff.getStatus() + "）");
        }
        if (!hasText(reason)) {
            throw new ServiceException(422, "差异原因必填（C-4.13-04 留痕）");
        }
        EamCountDiff upd = newUpd(diff);
        upd.setReason(reason);
        upd.setEvidence(evidence);
        diffDao.updateById(upd);
        // 查无原因 → 升级财务经理（FR-4.13-5-4）
        if (reason.contains("查无原因") || reason.contains("无法查明")) {
            try {
                noticeService.push("ROLE_FIN_MGR", null, "盘点差异查无原因升级",
                        diff.getCntNo() + " 差异单（" + diff.getDiffType() + "，卡片 "
                                + diff.getCardNo() + "）调查无法查明原因，升级财务经理裁定",
                        "EAM_COUNT_DIFF", diff.getId());
            } catch (Exception ex) {
                log.warn("升级通知推送失败（不阻断）：{}", ex.getMessage());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("diffId", id);
        return out;
    }

    // ================= 分级审批（D2）=================

    @Override
    @Transactional
    public Map<String, Object> submitApproval(String id) {
        EamCountDiff diff = require(id);
        if (!"PENDING".equals(diff.getStatus()) && !"REJECTED".equals(diff.getStatus())
                && !"LIABILITY_PENDING".equals(diff.getStatus())) {
            throw new ServiceException(422, "当前状态不可提交审批（" + diff.getStatus() + "）");
        }
        // 盘盈无计价依据 → 阻断升级财务经理（BR-27）
        if ("OVER".equals(diff.getDiffType())
                && (diff.getDiffAmount() == null || diff.getDiffAmount().signum() <= 0)) {
            throw new ServiceException(422, "盘盈无计价依据（重置成本无法确定），不可入账，已升级财务经理裁定（BR-4.13-27）");
        }
        if (!hasText(diff.getReason())) {
            throw new ServiceException(422, "提交审批前须完成差异原因调查留痕");
        }
        BigDecimal amount = diff.getDiffAmount() == null ? BigDecimal.ZERO : diff.getDiffAmount();
        BigDecimal threshold = sysParamService.getAmount("ASSET_COUNT_DIFF_THRESHOLD",
                new BigDecimal("50000"));
        // 分级节点链
        List<List<com.erp.service.approval.ApprovalNodeSpec>> chain = new ArrayList<>();
        chain.add(List.of(com.erp.service.approval.ApprovalNodeSpec
                .sign("ROLE_DEPT_HEAD", "部门主管审批")));
        chain.add(List.of(com.erp.service.approval.ApprovalNodeSpec
                .sign("ROLE_FIN_ASSET", "财务资产会计审核")));
        if (amount.compareTo(threshold) >= 0) {
            chain.add(List.of(com.erp.service.approval.ApprovalNodeSpec
                    .sign("ROLE_FIN_MGR", "财务经理审批（超阈值）")));
            chain.add(List.of(com.erp.service.approval.ApprovalNodeSpec
                    .sign("ROLE_GM", "总经理审批（超阈值）")));
        }
        diff.setStatus("PENDING_APPROVE");
        diff.setUpdateBy(SecurityUtils.getCurrentUserId());
        diffDao.updateById(diff);
        String title = "盘点差异审批：" + diff.getCntNo() + " "
                + diffTypeLabel(diff.getDiffType()) + " " + diff.getCardNo()
                + " / 金额 " + amount + (amount.compareTo(threshold) >= 0 ? "（超阈值）" : "");
        var inst = approvalEngine.submit("AssetCountDiff", diff.getId(), title,
                "ROLE_FIN_ASSET", chain);
        EamCountDiff upd = newUpd(diff);
        upd.setApprId(inst.getId());
        diffDao.updateById(upd);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("diffId", id);
        out.put("apprId", inst.getId());
        out.put("threshold", threshold);
        out.put("nodes", chain.size());
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> onApprovalPassed(String id) {
        EamCountDiff diff = require(id);
        EamCountDiff upd = newUpd(diff);
        switch (diff.getDiffType()) {
            case "OVER":
                upd.setStatus("READY_TO_POST");
                break;
            case "SHORT":
                upd.setStatus("LIABILITY_PENDING");
                break;
            case "TAG_ISSUE":
            case "STATUS_CONFLICT":
                upd.setStatus("CLOSED");
                // 差异闭环 → 解除该卡盘点冻结（BR-30 解冻语义）
                if ("1".equals(diff.getFrozenFlag()) && diff.getCardId() != null) {
                    EamAssetCard card = cardDao.selectById(diff.getCardId());
                    if (card != null && "1".equals(card.getCountFreezeFlag())) {
                        EamAssetCard cupd = new EamAssetCard();
                        cupd.setId(card.getId());
                        cupd.setVerNo(card.getVerNo());
                        cupd.setCountFreezeFlag("0");
                        cupd.setUpdateBy(SecurityUtils.getCurrentUserId());
                        cardDao.updateById(cupd);
                    }
                }
                break;
            default:
                upd.setStatus("APPROVED");
        }
        diffDao.updateById(upd);
        // 盘亏：审批通过即挂账（BR-28 两段式第一段）
        if ("SHORT".equals(diff.getDiffType())) {
            suspendShortage(id);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("diffId", id);
        out.put("status", diffDao.selectById(id).getStatus());
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> onApprovalRejected(String id, String reason) {
        EamCountDiff diff = require(id);
        EamCountDiff upd = newUpd(diff);
        upd.setStatus("REJECTED");
        upd.setRejectReason(hasText(reason) ? reason : "差异审批驳回");
        diffDao.updateById(upd);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("diffId", id);
        out.put("status", "REJECTED");
        return out;
    }

    // ================= 责任认定（BR-28）=================

    @Override
    @Transactional
    public Map<String, Object> liability(String id, String liabilityType, String liabilityPerson,
                                         BigDecimal compensateAmount, String basis) {
        EamCountDiff diff = require(id);
        if (!"LIABILITY_PENDING".equals(diff.getStatus())) {
            throw new ServiceException(422, "当前状态不可责任认定（" + diff.getStatus()
                    + "，须盘亏差异审批通过后）");
        }
        String type = liabilityType == null ? "" : liabilityType.trim().toUpperCase();
        if (!List.of("NONE", "COMPENSATE", "INSURANCE").contains(type)) {
            throw new ServiceException(422, "责任认定结论取值域：NONE 无人为 / COMPENSATE 责任人赔偿 / INSURANCE 保险理赔");
        }
        if ("COMPENSATE".equals(type) && (compensateAmount == null || compensateAmount.signum() <= 0)) {
            throw new ServiceException(422, "责任人赔偿须填写赔偿金额");
        }
        if ("INSURANCE".equals(type)) {
            // 保险域未建，桩位恒通过（偏差记录 design Non-Goals）；理赔额暂用赔偿额字段
            log.info("保险理赔分支桩位通过（保险域未建，偏差记录）：差异单 {}", id);
        }
        if (!hasText(basis)) {
            throw new ServiceException(422, "责任认定须填写依据留痕（C-4.13-04 L5）");
        }
        EamCountDiff upd = newUpd(diff);
        upd.setLiabilityType(type);
        upd.setLiabilityPerson(liabilityPerson);
        upd.setCompensateAmount(compensateAmount == null ? BigDecimal.ZERO : compensateAmount);
        upd.setLiabilityBasis(basis);
        upd.setStatus("READY_TO_POST");
        diffDao.updateById(upd);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("diffId", id);
        out.put("status", "READY_TO_POST");
        return out;
    }

    // ================= 盘亏挂账（BR-28 第一段）=================

    @Override
    @Transactional
    public Map<String, Object> suspendShortage(String id) {
        EamCountDiff diff = require(id);
        if (!"SHORT".equals(diff.getDiffType())) {
            throw new ServiceException(422, "仅盘亏差异可挂账");
        }
        if (!"SUSPENDED".equals(diff.getAdjustStatus()) && !"POSTED".equals(diff.getAdjustStatus())) {
            BigDecimal nbv = nz(diff.getSnapNbv());
            String summary = "盘点盘亏挂账：" + diff.getCntNo() + " " + diff.getCardNo()
                    + " / NBV " + nbv + "（责任认定未完成，暂挂待处理财产损溢）";
            var v = voucherService.create("CNT", LocalDate.now(), summary,
                    "COUNT_SUSPEND", diff.getCntNo(), null, List.of(
                    FinVoucherLineSpec.of("1901", "DR", nbv, summary),
                    FinVoucherLineSpec.of("1601", "CR", nz(diff.getSnapCost()), summary),
                    FinVoucherLineSpec.of("1602", "DR", nz(diff.getSnapAccum()), summary)));
            EamCountDiff upd = newUpd(diff);
            upd.setAdjustStatus("SUSPENDED");
            upd.setVoucherId(v.getId());
            diffDao.updateById(upd);
            log.info("盘亏差异 {} 挂账凭证 {}（NBV {}）", id, v.getId(), nbv);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("diffId", id);
        out.put("adjustStatus", diffDao.selectById(id).getAdjustStatus());
        return out;
    }

    // ================= 账务执行（D5）=================

    @Override
    @Transactional
    public Map<String, Object> postAdjust(String id) {
        EamCountDiff diff = require(id);
        if (!"READY_TO_POST".equals(diff.getStatus())) {
            throw new ServiceException(422, "当前状态不可账务执行（" + diff.getStatus() + "）");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if ("OVER".equals(diff.getDiffType())) {
            out.putAll(postGain(diff));
        } else if ("SHORT".equals(diff.getDiffType())) {
            out.putAll(postLoss(diff));
        } else {
            throw new ServiceException(422, "该差异类型无需账务执行（" + diff.getDiffType() + "）");
        }
        EamCountDiff upd = newUpd(diff);
        upd.setStatus("POSTED");
        diffDao.updateById(upd);
        // 立即闭环
        upd = newUpd(diffDao.selectById(id));
        upd.setStatus("CLOSED");
        diffDao.updateById(upd);
        out.put("diffId", id);
        out.put("status", "CLOSED");
        return out;
    }

    /** 盘盈入账（BR-27）：重置成本建卡 + 盘盈凭证 借固定资产/贷 6301 */
    private Map<String, Object> postGain(EamCountDiff diff) {
        BigDecimal price = diff.getSuggestedPrice();
        if (price == null || price.signum() <= 0) {
            throw new ServiceException(422, "盘盈无计价依据，不可入账（BR-4.13-27）");
        }
        // 建卡（复用转资口径：次月起提、直线默认）
        EamAssetCard card = new EamAssetCard();
        card.setCardNo(nextCardNo());
        card.setAssetName(hasText(diff.getSnapName()) ? diff.getSnapName() : "盘盈-" + diff.getCardNo());
        card.setCategoryCode(hasText(diff.getSnapCategory()) ? diff.getSnapCategory() : "MACH");
        card.setDeptName(diff.getSnapDept());
        card.setCostCenterCode(diff.getSnapCc());
        card.setAssetCost(price);
        card.setLifeYears(5);
        card.setResidualRate(new BigDecimal("0.05"));
        card.setResidualValue(price.multiply(new BigDecimal("0.05")).setScale(2, RoundingMode.HALF_UP));
        card.setDepreciationMethod("STRAIGHT_LINE");
        card.setEffectiveDate(LocalDate.now());
        card.setStatus("IN_USE");
        card.setDeprStatus("NOT_DEPRECIATED");
        card.setAccumDepr(BigDecimal.ZERO);
        card.setLockFlag("1");
        card.setSourceCapNo(diff.getCntNo());
        card.setCreateBy(SecurityUtils.getCurrentUserId());
        cardDao.insert(card);
        String summary = "盘点盘盈入账：" + diff.getCntNo() + " " + card.getCardNo()
                + " / 重置成本 " + price;
        var v = voucherService.create("CNT", LocalDate.now(), summary,
                "COUNT_GAIN", diff.getCntNo(), null, List.of(
                FinVoucherLineSpec.of("1601", "DR", price, summary),
                FinVoucherLineSpec.of("6301", "CR", price, summary)));
        EamCountDiff upd = newUpd(diff);
        upd.setAdjustStatus("POSTED");
        upd.setVoucherId(v.getId());
        diffDao.updateById(upd);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("newCardNo", card.getCardNo());
        out.put("voucherId", v.getId());
        return out;
    }

    /** 盘亏核销（BR-28 第二段）：责任认定后 借 6711 净损失 + 借 1122 赔偿 / 贷 1901；卡片 DISPOSED */
    private Map<String, Object> postLoss(EamCountDiff diff) {
        if (!hasText(diff.getLiabilityType())) {
            throw new ServiceException(422, "盘亏须先完成责任认定（BR-4.13-28），不得核销");
        }
        BigDecimal nbv = nz(diff.getSnapNbv());
        BigDecimal comp = nz(diff.getCompensateAmount());
        if (comp.compareTo(nbv) > 0) {
            comp = nbv;   // 赔偿不超过 NBV
        }
        BigDecimal loss = nbv.subtract(comp).setScale(2, RoundingMode.HALF_UP);
        String summary = "盘点盘亏核销：" + diff.getCntNo() + " " + diff.getCardNo()
                + " / NBV " + nbv + "，赔偿 " + comp + "，损失 " + loss
                + "（认定 " + diff.getLiabilityType() + "）";
        List<FinVoucherLineSpec> lines = new ArrayList<>();
        if (loss.signum() != 0) {
            lines.add(FinVoucherLineSpec.of("6711", "DR", loss, summary));
        }
        if (comp.signum() != 0) {
            lines.add(FinVoucherLineSpec.of("1122", "DR", comp, summary));
        }
        lines.add(FinVoucherLineSpec.of("1901", "CR", nbv, summary));
        var v = voucherService.create("CNT", LocalDate.now(), summary,
                "COUNT_LOSS", diff.getCntNo(), null, lines);
        // 卡片核销（已挂账期间卡片仍 IN_USE，核销时置 DISPOSED）
        if (hasText(diff.getCardId())) {
            EamAssetCard card = cardDao.selectById(diff.getCardId());
            if (card != null) {
                EamAssetCard cupd = new EamAssetCard();
                cupd.setId(card.getId());
                cupd.setVerNo(card.getVerNo());
                cupd.setStatus("DISPOSED");
                cupd.setUpdateBy(SecurityUtils.getCurrentUserId());
                cardDao.updateById(cupd);
            }
        }
        EamCountDiff upd = newUpd(diff);
        upd.setAdjustStatus("POSTED");
        upd.setVoucherId(v.getId());
        diffDao.updateById(upd);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("voucherId", v.getId());
        out.put("loss", loss);
        out.put("compensate", comp);
        return out;
    }

    // ================= 冻结（BR-30）=================

    @Override
    @Transactional
    public Map<String, Object> freezeTag(String cardId, String diffId, boolean freeze) {
        EamAssetCard card = cardDao.selectById(cardId);
        if (card == null) {
            throw new ServiceException(404, "资产卡片不存在：" + cardId);
        }
        EamAssetCard upd = new EamAssetCard();
        upd.setId(card.getId());
        upd.setVerNo(card.getVerNo());
        upd.setCountFreezeFlag(freeze ? "1" : "0");
        upd.setUpdateBy(SecurityUtils.getCurrentUserId());
        cardDao.updateById(upd);
        if (hasText(diffId)) {
            EamCountDiff diff = diffDao.selectById(diffId);
            if (diff != null) {
                EamCountDiff dupd = newUpd(diff);
                dupd.setFrozenFlag(freeze ? "1" : "0");
                diffDao.updateById(dupd);
            }
        } else if (freeze) {
            // 现场发现双标签/污损 → 新建 TAG_ISSUE 差异单并冻结（BR-30 入口，无盘点单上下文时 planId 取卡片所属最近单可空）
            EamCountDiff diff = new EamCountDiff();
            diff.setPlanId("");
            diff.setCntNo("");
            diff.setLineId("");
            diff.setDiffType("TAG_ISSUE");
            diff.setCardId(card.getId());
            diff.setCardNo(card.getCardNo());
            diff.setSnapName(card.getAssetName());
            diff.setSnapDept(card.getDeptName());
            diff.setSnapCc(card.getCostCenterCode());
            diff.setStatus("PENDING");
            diff.setAdjustStatus("NONE");
            diff.setFrozenFlag("1");
            diff.setReason("标签异常：同一实物多标签或标签污损不可识别（BR-4.13-30），已冻结调拨与处置");
            diff.setCreateBy(SecurityUtils.getCurrentUserId());
            diffDao.insert(diff);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cardNo", card.getCardNo());
        out.put("frozen", freeze);
        return out;
    }

    @Override
    public List<Map<String, Object>> frozenCards() {
        List<Map<String, Object>> out = new ArrayList<>();
        List<EamAssetCard> cards = cardDao.selectList(new LambdaQueryWrapper<EamAssetCard>()
                .eq(EamAssetCard::getCountFreezeFlag, "1"));
        for (EamAssetCard card : cards) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("cardId", card.getId());
            row.put("cardNo", card.getCardNo());
            row.put("assetName", card.getAssetName());
            row.put("deptName", card.getDeptName());
            // 关联差异单（未关闭的 TAG_ISSUE/STATUS_CONFLICT）
            List<EamCountDiff> diffs = diffDao.selectList(new LambdaQueryWrapper<EamCountDiff>()
                    .eq(EamCountDiff::getCardId, card.getId())
                    .in(EamCountDiff::getDiffType, "TAG_ISSUE", "STATUS_CONFLICT")
                    .ne(EamCountDiff::getStatus, "CLOSED"));
            row.put("diffId", diffs.isEmpty() ? null : diffs.get(0).getId());
            row.put("diffType", diffs.isEmpty() ? null : diffs.get(0).getDiffType());
            out.add(row);
        }
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> closeTagIssue(String diffId) {
        EamCountDiff diff = require(diffId);
        if (!List.of("TAG_ISSUE", "STATUS_CONFLICT").contains(diff.getDiffType())) {
            throw new ServiceException(422, "仅标签异常/账实冲突差异可按补标解冻关闭");
        }
        // 解冻卡片
        if (hasText(diff.getCardId())) {
            freezeTag(diff.getCardId(), diffId, false);
        }
        EamCountDiff upd = newUpd(diffDao.selectById(diffId));
        upd.setStatus("CLOSED");
        diffDao.updateById(upd);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("diffId", diffId);
        out.put("status", "CLOSED");
        out.put("unfrozen", true);
        return out;
    }

    // ================= 内部 =================

    private EamCountDiff require(String id) {
        EamCountDiff diff = id == null ? null : diffDao.selectById(id);
        if (diff == null) {
            throw new ServiceException(404, "差异单不存在：" + id);
        }
        return diff;
    }

    private EamCountDiff newUpd(EamCountDiff base) {
        EamCountDiff upd = new EamCountDiff();
        upd.setId(base.getId());
        upd.setVerNo(base.getVerNo());
        return upd;
    }

    private String nextCardNo() {
        String prefix = "ZC" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        Integer max = cardDao.selectMaxSeq(prefix);
        return prefix + String.format("%04d", (max == null ? 0 : max) + 1);
    }

    private static String diffTypeLabel(String type) {
        switch (type) {
            case "OVER": return "盘盈";
            case "SHORT": return "盘亏";
            case "TAG_ISSUE": return "标签异常";
            case "STATUS_CONFLICT": return "账实冲突";
            default: return type;
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
