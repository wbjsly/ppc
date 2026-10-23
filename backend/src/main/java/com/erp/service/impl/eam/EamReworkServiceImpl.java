package com.erp.service.impl.eam;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.dao.eam.EamAssetCardDao;
import com.erp.dao.eam.EamAssetCategoryDao;
import com.erp.dao.eam.EamCipCostDao;
import com.erp.dao.eam.EamCipProjectDao;
import com.erp.dao.eam.EamReworkItemDao;
import com.erp.dao.eam.EamReworkOrderDao;
import com.erp.dao.eam.EamTransferOrderDao;
import com.erp.dao.eam.EamWorkOrderDao;
import com.erp.dao.fin.FinAccountDao;
import com.erp.entity.eam.EamAssetCard;
import com.erp.entity.eam.EamAssetCategory;
import com.erp.entity.eam.EamCipProject;
import com.erp.entity.eam.EamReworkItem;
import com.erp.entity.eam.EamReworkOrder;
import com.erp.entity.eam.EamTransferOrder;
import com.erp.entity.eam.EamWorkOrder;
import com.erp.entity.fin.FinAccount;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.eam.EamCipService;
import com.erp.service.eam.EamReworkService;
import com.erp.service.eam.EamSafetyLockService;
import com.erp.service.fin.GlVoucherService;
import com.erp.service.system.NoticeService;
import com.erp.service.SysParamService;
import com.erp.util.SecurityUtils;
import com.erp.common.ServiceException;
import lombok.RequiredArgsConstructor;
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
 * 资产技改大修实现（add-asset-rework，design D2~D6、D8）。
 * 三凭证锚：REWORK_TRANSFER（转在建）/ REWORK_REBUILD（完工转资）/ REWORK_EXPENSE（费用化）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EamReworkServiceImpl implements EamReworkService {

    private final EamReworkOrderDao orderDao;
    private final EamReworkItemDao itemDao;
    private final EamAssetCardDao cardDao;
    private final EamAssetCategoryDao categoryDao;
    private final EamWorkOrderDao workOrderDao;
    private final EamTransferOrderDao transferOrderDao;
    private final EamCipProjectDao cipProjectDao;
    private final EamCipCostDao cipCostDao;
    private final FinAccountDao accountDao;
    private final EamCipService cipService;
    private final GlVoucherService voucherService;
    private final ApprovalEngine approvalEngine;
    private final EamSafetyLockService safetyLockService;
    private final NoticeService noticeService;
    private final SysParamService sysParamService;

    private static final String ACCT_CIP = "1604";
    private static final String ACCT_ACCUM = "1602";
    private static final String ACCT_FA = "1601";
    private static final String ACCT_REPAIR_EXP = "660201";
    private static final String ACCT_AP = "2202";

    // ================= 创建 / 编辑 =================

    @Override
    @Transactional
    public Map<String, Object> create(EamReworkOrder order, List<EamReworkItem> items) {
        EamAssetCard card = requireCard(order.getCardId());
        EamReworkOrder inFlight = orderDao.selectInFlightByCard(card.getId());
        if (inFlight != null) {
            throw new ServiceException(422, "卡片 " + card.getCardNo() + " 已有进行中的技改单：" + inFlight.getRwfNo());
        }
        assertPreConditions(card);
        if (order.getBudgetAmount() == null) {
            throw new ServiceException(422, "技改预算必填");
        }
        List<EamReworkItem> builtItems = buildItems(null, items);

        EamReworkOrder o = new EamReworkOrder();
        o.setRwfNo(nextRwfNo());
        o.setCardId(card.getId());
        o.setCardNo(card.getCardNo());
        o.setAssetName(card.getAssetName());
        o.setCategoryCode(card.getCategoryCode());
        o.setDeptName(card.getDeptName());
        o.setContent(order.getContent());
        o.setBudgetAmount(order.getBudgetAmount());
        o.setExpectedEffect(order.getExpectedEffect());
        o.setRemark(order.getRemark());
        o.setFloorReviewFlag(isFullyDepreciated(card) ? "1" : "0");
        o.setFloorMergeFlag("0");
        o.setContinueDeprFlag("0");
        o.setReworkStatus("DRAFT");
        o.setCreateBy(SecurityUtils.getCurrentUserId());
        orderDao.insert(o);
        for (EamReworkItem it : builtItems) {
            it.setOrderId(o.getId());
            itemDao.insert(it);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("order", orderDao.selectById(o.getId()));
        out.put("itemCount", builtItems.size());
        return out;
    }

    @Override
    @Transactional
    public void update(EamReworkOrder req, List<EamReworkItem> items) {
        EamReworkOrder old = require(req.getId());
        if (!"DRAFT".equals(old.getReworkStatus()) && !"REJECTED".equals(old.getReworkStatus())) {
            throw new ServiceException(422, "仅草稿/已驳回可编辑（当前 " + old.getReworkStatus() + "）");
        }
        List<EamReworkItem> builtItems = buildItems(old.getId(), items);
        itemDao.delete(new LambdaQueryWrapper<EamReworkItem>().eq(EamReworkItem::getOrderId, old.getId()));
        for (EamReworkItem it : builtItems) {
            it.setOrderId(old.getId());
            itemDao.insert(it);
        }
        EamReworkOrder patch = new EamReworkOrder();
        patch.setId(old.getId());
        patch.setVerNo(old.getVerNo());
        patch.setContent(req.getContent());
        patch.setBudgetAmount(req.getBudgetAmount());
        patch.setExpectedEffect(req.getExpectedEffect());
        patch.setRemark(req.getRemark());
        patch.setUpdateBy(SecurityUtils.getCurrentUserId());
        orderDao.updateById(patch);
    }

    // ================= 提交判定 =================

    @Override
    @Transactional
    public Map<String, Object> submit(String id) {
        EamReworkOrder order = require(id);
        if (!"DRAFT".equals(order.getReworkStatus()) && !"REJECTED".equals(order.getReworkStatus())) {
            throw new ServiceException(422, "仅草稿/已驳回可提交判定（当前 " + order.getReworkStatus() + "）");
        }
        if (itemDao.selectCount(new LambdaQueryWrapper<EamReworkItem>()
                .eq(EamReworkItem::getOrderId, id).eq(EamReworkItem::getDelFlag, "0")) == 0) {
            throw new ServiceException(422, "至少录入一条 BR-37 判定依据（条件类型+量化值+证据）");
        }
        // BR-38 防拆分：12 月同卡笔数与合计
        LocalDateTime since = LocalDateTime.now().minusMonths(12);
        List<EamReworkOrder> peers = orderDao.selectList(new LambdaQueryWrapper<EamReworkOrder>()
                .eq(EamReworkOrder::getCardId, order.getCardId())
                .eq(EamReworkOrder::getDelFlag, "0")
                .ne(EamReworkOrder::getId, id)
                .in(EamReworkOrder::getReworkStatus, "PENDING_JUDGE", "JUDGED", "IN_REWORK", "COMPLETED", "REBUILT", "EXPENSED", "CLOSED")
                .ge(EamReworkOrder::getCreateDate, since));
        BigDecimal floor = capitalizationFloor(order.getCategoryCode());
        BigDecimal sum12 = BigDecimal.ZERO;
        for (EamReworkOrder p : peers) {
            sum12 = sum12.add(nz(p.getBudgetAmount()));
        }
        BigDecimal budget = nz(order.getBudgetAmount());
        boolean countHit = peers.size() >= 3;
        boolean floorHit = budget.compareTo(floor) < 0 && sum12.add(budget).compareTo(floor) >= 0;
        String merge = (countHit || floorHit) ? "1" : "0";
        orderDao.update(null, new LambdaUpdateWrapper<EamReworkOrder>()
                .eq(EamReworkOrder::getId, id)
                .set(EamReworkOrder::getReworkStatus, "PENDING_JUDGE")
                .set(EamReworkOrder::getFloorMergeFlag, merge)
                .set(EamReworkOrder::getVerNo, order.getVerNo() + 1)
                .set(EamReworkOrder::getUpdateBy, SecurityUtils.getCurrentUserId()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", id);
        out.put("status", "PENDING_JUDGE");
        out.put("peerCount12m", peers.size());
        out.put("mergeHit", countHit || floorHit);
        return out;
    }

    // ================= 判定 =================

    @Override
    @Transactional
    public Map<String, Object> judge(String id, String capJudge, String judgeNote,
                                     String judgeEvidence, List<EamReworkItem> items) {
        EamReworkOrder order = require(id);
        if (!"PENDING_JUDGE".equals(order.getReworkStatus())) {
            throw new ServiceException(422, "仅待判定状态可判定（当前 " + order.getReworkStatus() + "）");
        }
        if (!"CAP".equals(capJudge) && !"EXPENSE".equals(capJudge)) {
            throw new ServiceException(422, "判定结果仅 CAP（资本化）/ EXPENSE（费用化）");
        }
        if (items != null && !items.isEmpty()) {
            List<EamReworkItem> builtItems = buildItems(id, items);
            itemDao.delete(new LambdaQueryWrapper<EamReworkItem>().eq(EamReworkItem::getOrderId, id));
            for (EamReworkItem it : builtItems) {
                it.setOrderId(id);
                itemDao.insert(it);
            }
        }
        // BR-37 三条件量化校验
        List<EamReworkItem> cur = itemDao.selectList(new LambdaQueryWrapper<EamReworkItem>()
                .eq(EamReworkItem::getOrderId, id).eq(EamReworkItem::getDelFlag, "0"));
        boolean qualified = cur.stream().anyMatch(this::qualifies);
        if (!qualified) {
            throw new ServiceException(422, "BR-37 判定依据不足：须满足寿命延长≥1年 / 产能提升≥10% / 精度性能显著提升其一并附证据");
        }
        boolean hasEvidence = cur.stream().anyMatch(i -> hasText(i.getEvidenceNo()));
        if (!hasEvidence && !hasText(judgeEvidence)) {
            throw new ServiceException(422, "判定须附证据附件（技术评估报告/寿命测算）");
        }
        if ("CAP".equals(capJudge)) {
            BigDecimal floor = capitalizationFloor(order.getCategoryCode());
            BigDecimal budget = nz(order.getBudgetAmount());
            if (budget.compareTo(floor) < 0 && !"1".equals(order.getFloorMergeFlag())) {
                throw new ServiceException(422, "本单预算 " + budget + " 未达分类资本化下限 " + floor
                        + " 且不满足 12 月合并计算条件，请判定为费用化（EXPENSE）");
            }
        }
        EamReworkOrder patch = new EamReworkOrder();
        patch.setId(id);
        patch.setVerNo(order.getVerNo());
        patch.setCapJudge(capJudge);
        patch.setJudgeNote(judgeNote);
        patch.setJudgeEvidence(judgeEvidence);
        patch.setJudgeBy(SecurityUtils.getCurrentUserId());
        patch.setJudgeDate(LocalDateTime.now());
        patch.setUpdateBy(SecurityUtils.getCurrentUserId());

        boolean needReview = "1".equals(order.getFloorReviewFlag()) || "1".equals(order.getFloorMergeFlag());
        if (needReview) {
            // 已提足复核 / BR-38 防拆分 → 财务经理复核审批
            List<List<ApprovalNodeSpec>> chain = new ArrayList<>();
            chain.add(List.of(ApprovalNodeSpec.sign("ROLE_FIN_MGR",
                    "1".equals(order.getFloorMergeFlag()) ? "财务经理复核（防拆分合并计算）" : "财务经理复核（已提足技改）")));
            var instance = approvalEngine.submit("AssetRework", id,
                    "技改判定复核：" + order.getRwfNo() + " " + order.getAssetName(), null, chain);
            patch.setReworkStatus("PENDING_JUDGE");
            patch.setApprId(instance.getId());
            orderDao.updateById(patch);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("orderId", id);
            out.put("reviewRequired", true);
            out.put("apprId", instance.getId());
            out.put("nodes", 1);
            return out;
        }
        patch.setReworkStatus("JUDGED");
        orderDao.updateById(patch);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", id);
        out.put("reviewRequired", false);
        out.put("status", "JUDGED");
        return out;
    }

    @Override
    public void arbitrate(String id, String note) {
        EamReworkOrder order = require(id);
        if (!"PENDING_JUDGE".equals(order.getReworkStatus()) && !"REJECTED".equals(order.getReworkStatus())) {
            throw new ServiceException(422, "仅待判定/已驳回可争议裁定");
        }
        if (!hasText(note)) {
            throw new ServiceException(422, "裁定意见必填（留痕）");
        }
        orderDao.update(null, new LambdaUpdateWrapper<EamReworkOrder>()
                .eq(EamReworkOrder::getId, id)
                .set(EamReworkOrder::getJudgeNote, "【财务经理裁定】" + note)
                .set(EamReworkOrder::getVerNo, order.getVerNo() + 1)
                .set(EamReworkOrder::getUpdateBy, SecurityUtils.getCurrentUserId()));
    }

    @Override
    @Transactional
    public Map<String, Object> onApprovalPassed(String id) {
        EamReworkOrder order = require(id);
        orderDao.update(null, new LambdaUpdateWrapper<EamReworkOrder>()
                .eq(EamReworkOrder::getId, id)
                .set(EamReworkOrder::getReworkStatus, "JUDGED")
                .set(EamReworkOrder::getVerNo, order.getVerNo() + 1)
                .set(EamReworkOrder::getUpdateBy, SecurityUtils.getCurrentUserId()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", id);
        out.put("status", "JUDGED");
        return out;
    }

    @Override
    @Transactional
    public void onApprovalRejected(String id, String reason) {
        EamReworkOrder order = require(id);
        orderDao.update(null, new LambdaUpdateWrapper<EamReworkOrder>()
                .eq(EamReworkOrder::getId, id)
                .set(EamReworkOrder::getReworkStatus, "DRAFT")
                .set(EamReworkOrder::getJudgeNote, "【复核驳回】" + reason)
                .set(EamReworkOrder::getVerNo, order.getVerNo() + 1)
                .set(EamReworkOrder::getUpdateBy, SecurityUtils.getCurrentUserId()));
    }

    // ================= 特批续提（BR-39） =================

    @Override
    @Transactional
    public Map<String, Object> requestContinueDepr(String id, String reason) {
        EamReworkOrder order = require(id);
        if (!"IN_REWORK".equals(order.getReworkStatus())) {
            throw new ServiceException(422, "仅在建-技改中可申请特批续提（当前 " + order.getReworkStatus() + "）");
        }
        if ("1".equals(order.getContinueDeprFlag())) {
            throw new ServiceException(422, "已特批续提，无需重复申请");
        }
        List<List<ApprovalNodeSpec>> chain = new ArrayList<>();
        chain.add(List.of(ApprovalNodeSpec.sign("ROLE_FIN_MGR", "特批续提审批")));
        var instance = approvalEngine.submit("AssetReworkContinue", id,
                "技改特批续提：" + order.getRwfNo() + " " + (reason == null ? "" : reason), null, chain);
        EamReworkOrder patch = new EamReworkOrder();
        patch.setId(id);
        patch.setVerNo(order.getVerNo());
        patch.setContinueApprId(instance.getId());
        patch.setUpdateBy(SecurityUtils.getCurrentUserId());
        orderDao.updateById(patch);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", id);
        out.put("apprId", instance.getId());
        out.put("nodes", 1);
        return out;
    }

    @Override
    @Transactional
    public void onContinueApproved(String id) {
        EamReworkOrder order = require(id);
        orderDao.update(null, new LambdaUpdateWrapper<EamReworkOrder>()
                .eq(EamReworkOrder::getId, id)
                .set(EamReworkOrder::getContinueDeprFlag, "1")
                .set(EamReworkOrder::getVerNo, order.getVerNo() + 1)
                .set(EamReworkOrder::getUpdateBy, SecurityUtils.getCurrentUserId()));
    }

    @Override
    public void onContinueRejected(String id, String reason) {
        log.info("技改单 {} 特批续提驳回（{}）", id, reason);
    }

    // ================= 转在建 =================

    @Override
    @Transactional
    public Map<String, Object> toInRework(String id) {
        EamReworkOrder order = require(id);
        if (!"JUDGED".equals(order.getReworkStatus()) || !"CAP".equals(order.getCapJudge())) {
            throw new ServiceException(422, "仅资本化判定通过（JUDGED/CAP）可转在建");
        }
        EamAssetCard card = cardDao.selectById(order.getCardId());
        if (card == null || !"0".equals(card.getDelFlag())) {
            throw new ServiceException(422, "原卡不存在：" + order.getCardNo());
        }
        BigDecimal cost = nz(card.getAssetCost());
        BigDecimal accum = nz(card.getAccumDepr()).add(nz(card.getImpairment()));
        BigDecimal nbv = cost.subtract(accum).max(BigDecimal.ZERO);
        requireAccounts(ACCT_CIP, ACCT_ACCUM, ACCT_FA);

        String summary = "技改转在建：" + order.getRwfNo() + " " + card.getCardNo();
        List<GlVoucherService.FinVoucherLineSpec> lines = new ArrayList<>();
        if (nbv.signum() > 0) {
            lines.add(GlVoucherService.FinVoucherLineSpec.of(ACCT_CIP, "DR", nbv, summary));
        }
        if (accum.signum() > 0) {
            lines.add(GlVoucherService.FinVoucherLineSpec.of(ACCT_ACCUM, "DR", accum, summary));
        }
        if (cost.signum() > 0) {
            lines.add(GlVoucherService.FinVoucherLineSpec.of(ACCT_FA, "CR", cost, summary));
        }
        var voucher = voucherService.create("RWF", LocalDate.now(), summary,
                "REWORK_TRANSFER", order.getRwfNo(), null, lines);

        EamCipProject project = new EamCipProject();
        project.setName("技改-" + card.getCardNo() + " " + order.getRwfNo());
        project.setBudgetAmount(order.getBudgetAmount());
        project.setOwnerName(order.getCreateBy());
        project.setReworkOrderId(id);
        project.setRemark("技改大修 1:1 项目：" + (order.getContent() == null ? "" : order.getContent()));
        project = cipService.createProject(project);

        EamAssetCard cupd = new EamAssetCard();
        cupd.setId(card.getId());
        cupd.setVerNo(card.getVerNo());
        cupd.setStatus("IN_REWORK");
        cupd.setUpdateBy(SecurityUtils.getCurrentUserId());
        if (cardDao.updateById(cupd) == 0) {
            throw new ServiceException(409, "卡片更新冲突：" + card.getCardNo());
        }
        EamReworkOrder patch = new EamReworkOrder();
        patch.setId(id);
        patch.setVerNo(orderDao.selectById(id).getVerNo());
        patch.setReworkStatus("IN_REWORK");
        patch.setOrigNbv(nbv);
        patch.setCipProjectId(project.getId());
        patch.setTransferVoucherId(voucher.getId());
        patch.setUpdateBy(SecurityUtils.getCurrentUserId());
        orderDao.updateById(patch);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", id);
        out.put("status", "IN_REWORK");
        out.put("origNbv", nbv);
        out.put("cipProjectId", project.getId());
        out.put("voucherId", voucher.getId());
        return out;
    }

    // ================= 验收 / 转资重建 =================

    @Override
    @Transactional
    public Map<String, Object> accept(String id, LocalDate acceptDate) {
        EamReworkOrder order = require(id);
        if (!"IN_REWORK".equals(order.getReworkStatus())) {
            throw new ServiceException(422, "仅在建-技改中可完工验收（当前 " + order.getReworkStatus() + "）");
        }
        LocalDate date = acceptDate == null ? LocalDate.now() : acceptDate;
        // CIP 完工（要求已发生归集，否则 422 提示先归集）
        cipService.complete(order.getCipProjectId(), date);
        orderDao.update(null, new LambdaUpdateWrapper<EamReworkOrder>()
                .eq(EamReworkOrder::getId, id)
                .set(EamReworkOrder::getReworkStatus, "COMPLETED")
                .set(EamReworkOrder::getAcceptDate, date)
                .set(EamReworkOrder::getVerNo, order.getVerNo() + 1)
                .set(EamReworkOrder::getUpdateBy, SecurityUtils.getCurrentUserId()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", id);
        out.put("status", "COMPLETED");
        out.put("acceptDate", date);
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> rebuild(String id, Integer newLifeYears) {
        EamReworkOrder order = require(id);
        if (!"COMPLETED".equals(order.getReworkStatus())) {
            throw new ServiceException(422, "仅已验收（COMPLETED）可转资重建（当前 " + order.getReworkStatus() + "）");
        }
        if (newLifeYears == null || newLifeYears <= 0) {
            throw new ServiceException(422, "剩余年限（技术评估值）必填且为正整数");
        }
        EamAssetCard old = cardDao.selectById(order.getCardId());
        BigDecimal settled = nz(cipCostDao.sumTotal(order.getCipProjectId()));
        BigDecimal newCost = nz(order.getOrigNbv()).add(settled).setScale(2, RoundingMode.HALF_UP);
        if (newCost.signum() <= 0) {
            throw new ServiceException(422, "新原值 " + newCost + " 非正，无法重建卡（请核对转入时 NBV 与归集）");
        }
        requireAccounts(ACCT_FA, ACCT_CIP);

        String summary = "技改完工转资：" + order.getRwfNo() + " → " + old.getCardNo();
        var voucher = voucherService.create("RWF", LocalDate.now(), summary, "REWORK_REBUILD",
                order.getRwfNo(), null, List.of(
                        GlVoucherService.FinVoucherLineSpec.of(ACCT_FA, "DR", newCost, summary),
                        GlVoucherService.FinVoucherLineSpec.of(ACCT_CIP, "CR", newCost, summary)));

        // 新卡：复制非财务字段 + 新原值/年限 + 锚原卡（BR-40）
        EamAssetCategory category = categoryByCode(old.getCategoryCode());
        BigDecimal residualRate = old.getResidualRate() != null
                ? old.getResidualRate()
                : (category != null && category.getDefaultResidualRate() != null
                        ? category.getDefaultResidualRate() : BigDecimal.ZERO);
        EamAssetCard neu = new EamAssetCard();
        neu.setCardNo(nextCardNo());
        neu.setAssetName(old.getAssetName());
        neu.setSpecModel(old.getSpecModel());
        neu.setCategoryCode(old.getCategoryCode());
        neu.setLegalEntity(old.getLegalEntity());
        neu.setDeptName(old.getDeptName());
        neu.setCostCenterCode(old.getCostCenterCode());
        neu.setStorageLocation(old.getStorageLocation());
        neu.setAssetCost(newCost);
        neu.setLifeYears(newLifeYears);
        neu.setResidualRate(residualRate);
        neu.setResidualValue(newCost.multiply(residualRate).setScale(2, RoundingMode.HALF_UP));
        neu.setDepreciationMethod(old.getDepreciationMethod());
        // 次月起提：生效日=验收日，引擎「起提月≤本期」天然从次月首个计提期纳入（BR-40 不补提）
        neu.setEffectiveDate(order.getAcceptDate());
        neu.setStatus("IN_USE");
        neu.setDeprStatus("NOT_DEPRECIATED");
        neu.setAccumDepr(BigDecimal.ZERO);
        neu.setImpairment(BigDecimal.ZERO);
        neu.setLockFlag("0");
        neu.setPledgedFlag("0");
        neu.setCountFreezeFlag("0");
        neu.setSourceCapId(old.getId());
        neu.setSourceCapNo(old.getCardNo());
        neu.setCreateBy(SecurityUtils.getCurrentUserId());
        cardDao.insert(neu);

        // 原卡归档（保留全部历史）
        cardDao.update(null, new LambdaUpdateWrapper<EamAssetCard>()
                .eq(EamAssetCard::getId, old.getId())
                .set(EamAssetCard::getStatus, "REPLACED")
                .set(EamAssetCard::getUpdateBy, SecurityUtils.getCurrentUserId())
                .set(EamAssetCard::getVerNo, old.getVerNo() + 1));

        EamReworkOrder patch = new EamReworkOrder();
        patch.setId(id);
        patch.setVerNo(order.getVerNo());
        patch.setReworkStatus("REBUILT");
        patch.setNewCardId(neu.getId());
        patch.setNewCost(newCost);
        patch.setNewLifeYears(newLifeYears);
        patch.setRebuildVoucherId(voucher.getId());
        patch.setUpdateBy(SecurityUtils.getCurrentUserId());
        orderDao.updateById(patch);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", id);
        out.put("status", "REBUILT");
        out.put("newCardNo", neu.getCardNo());
        out.put("newCost", newCost);
        out.put("newLifeYears", newLifeYears);
        out.put("settled", settled);
        out.put("voucherId", voucher.getId());
        return out;
    }

    // ================= 费用化 =================

    @Override
    @Transactional
    public Map<String, Object> expense(String id) {
        EamReworkOrder order = require(id);
        if (!"JUDGED".equals(order.getReworkStatus()) || !"EXPENSE".equals(order.getCapJudge())) {
            throw new ServiceException(422, "仅费用化判定通过（JUDGED/EXPENSE）可费用化确认");
        }
        BigDecimal amount = nz(order.getBudgetAmount());
        if (amount.signum() <= 0) {
            throw new ServiceException(422, "费用化金额（技改预算）须大于 0");
        }
        requireAccounts(ACCT_REPAIR_EXP, ACCT_AP);
        String summary = "技改费用化：" + order.getRwfNo() + " " + order.getCardNo();
        var voucher = voucherService.create("RWF", LocalDate.now(), summary, "REWORK_EXPENSE",
                order.getRwfNo(), null, List.of(
                        GlVoucherService.FinVoucherLineSpec.of(ACCT_REPAIR_EXP, "DR", amount, summary),
                        GlVoucherService.FinVoucherLineSpec.of(ACCT_AP, "CR", amount, summary)));
        EamReworkOrder patch = new EamReworkOrder();
        patch.setId(id);
        patch.setVerNo(order.getVerNo());
        patch.setReworkStatus("EXPENSED");
        patch.setExpenseVoucherId(voucher.getId());
        patch.setUpdateBy(SecurityUtils.getCurrentUserId());
        orderDao.updateById(patch);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", id);
        out.put("status", "EXPENSED");
        out.put("voucherId", voucher.getId());
        return out;
    }

    // ================= 结案 / 冲回 =================

    @Override
    public void close(String id) {
        EamReworkOrder order = require(id);
        if (!"REBUILT".equals(order.getReworkStatus()) && !"EXPENSED".equals(order.getReworkStatus())) {
            throw new ServiceException(422, "仅转资重建/费用化后可结案（当前 " + order.getReworkStatus() + "）");
        }
        orderDao.update(null, new LambdaUpdateWrapper<EamReworkOrder>()
                .eq(EamReworkOrder::getId, id)
                .set(EamReworkOrder::getReworkStatus, "CLOSED")
                .set(EamReworkOrder::getVerNo, order.getVerNo() + 1)
                .set(EamReworkOrder::getUpdateBy, SecurityUtils.getCurrentUserId()));
    }

    @Override
    @Transactional
    public Map<String, Object> reverse(String id) {
        EamReworkOrder order = require(id);
        if (!"IN_REWORK".equals(order.getReworkStatus())) {
            throw new ServiceException(422, "仅在建-技改中可整单冲回（REBUILT 后须走资产处置，当前 "
                    + order.getReworkStatus() + "）");
        }
        BigDecimal settled = nz(cipCostDao.sumTotal(order.getCipProjectId()));
        if (settled.signum() > 0) {
            throw new ServiceException(422, "项目已发生归集 ¥" + settled + "，不可整单冲回");
        }
        int reversed = 0;
        if (hasText(order.getTransferVoucherId())) {
            voucherService.reverse(order.getTransferVoucherId(), "技改整单冲回：" + order.getRwfNo(), order.getRwfNo());
            reversed++;
        }
        EamAssetCard card = cardDao.selectById(order.getCardId());
        if (card != null && "IN_REWORK".equals(card.getStatus())) {
            cardDao.update(null, new LambdaUpdateWrapper<EamAssetCard>()
                    .eq(EamAssetCard::getId, card.getId())
                    .set(EamAssetCard::getStatus, "IN_USE")
                    .set(EamAssetCard::getUpdateBy, SecurityUtils.getCurrentUserId())
                    .set(EamAssetCard::getVerNo, card.getVerNo() + 1));
        }
        if (hasText(order.getCipProjectId())) {
            EamCipProject project = cipProjectDao.selectById(order.getCipProjectId());
            if (project != null && "OPEN".equals(project.getStatus())) {
                cipProjectDao.update(null, new LambdaUpdateWrapper<EamCipProject>()
                        .eq(EamCipProject::getId, project.getId())
                        .set(EamCipProject::getStatus, "VOID")
                        .set(EamCipProject::getUpdateBy, SecurityUtils.getCurrentUserId())
                        .set(EamCipProject::getVerNo, project.getVerNo() + 1));
            }
        }
        orderDao.update(null, new LambdaUpdateWrapper<EamReworkOrder>()
                .eq(EamReworkOrder::getId, id)
                .set(EamReworkOrder::getReworkStatus, "JUDGED")
                .set(EamReworkOrder::getVerNo, order.getVerNo() + 1)
                .set(EamReworkOrder::getUpdateBy, SecurityUtils.getCurrentUserId()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", id);
        out.put("status", "JUDGED");
        out.put("reversedVouchers", reversed);
        return out;
    }

    // ================= 超期预警（C-4.13-06） =================

    @Override
    @Transactional
    public Map<String, Object> scanOverdue() {
        int grace = 30;
        try {
            grace = sysParamService.getAmount("CAPITALIZATION_GRACE_DAYS", new BigDecimal("30")).intValue();
        } catch (Exception ignore) {
            // 参数缺失用默认 30
        }
        LocalDate today = LocalDate.now();
        List<EamReworkOrder> completed = orderDao.selectList(new LambdaQueryWrapper<EamReworkOrder>()
                .eq(EamReworkOrder::getReworkStatus, "COMPLETED")
                .eq(EamReworkOrder::getDelFlag, "0")
                .isNotNull(EamReworkOrder::getAcceptDate));
        List<Map<String, Object>> hit = new ArrayList<>();
        for (EamReworkOrder o : completed) {
            long days = ChronoUnit.DAYS.between(o.getAcceptDate(), today);
            if (days <= grace) {
                continue;
            }
            String level = days > grace * 2 ? "ESCALATED" : "WARN";
            noticeService.push("ROLE_ASSET_ADMIN", null,
                    "技改转资超期预警（C-4.13-06）",
                    "技改单 " + o.getRwfNo() + "（" + o.getCardNo() + "）验收后 " + days
                            + " 天未转资" + ("ESCALATED".equals(level) ? "，已升级财务经理" : ""),
                    "EAM_REWORK_OVERDUE", o.getId());
            if ("ESCALATED".equals(level)) {
                noticeService.push("ROLE_FIN_MGR", null,
                        "技改转资超期升级（C-4.13-06）",
                        "技改单 " + o.getRwfNo() + "（" + o.getCardNo() + "）验收后 " + days + " 天未转资，请督办",
                        "EAM_REWORK_OVERDUE", o.getId());
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("orderId", o.getId());
            row.put("rwfNo", o.getRwfNo());
            row.put("cardNo", o.getCardNo());
            row.put("days", days);
            row.put("level", level);
            hit.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("graceDays", grace);
        out.put("overdueCount", hit.size());
        out.put("overdue", hit);
        return out;
    }

    // ================= 查询 =================

    @Override
    public Page<EamReworkOrder> page(long current, long size, String keyword, String status) {
        LambdaQueryWrapper<EamReworkOrder> qw = new LambdaQueryWrapper<EamReworkOrder>()
                .eq(EamReworkOrder::getDelFlag, "0")
                .orderByDesc(EamReworkOrder::getCreateDate);
        if (hasText(keyword)) {
            qw.and(w -> w.like(EamReworkOrder::getRwfNo, keyword).or().like(EamReworkOrder::getCardNo, keyword));
        }
        if (hasText(status)) {
            qw.eq(EamReworkOrder::getReworkStatus, status);
        }
        return orderDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    public Map<String, Object> detail(String id) {
        EamReworkOrder order = require(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("order", order);
        out.put("items", itemDao.selectList(new LambdaQueryWrapper<EamReworkItem>()
                .eq(EamReworkItem::getOrderId, id).eq(EamReworkItem::getDelFlag, "0")));
        if (hasText(order.getCipProjectId())) {
            EamCipProject p = cipProjectDao.selectById(order.getCipProjectId());
            Map<String, Object> pm = new LinkedHashMap<>();
            if (p != null) {
                pm.put("projectId", p.getId());
                pm.put("projectNo", p.getProjectNo());
                pm.put("name", p.getName());
                pm.put("budgetAmount", p.getBudgetAmount());
                pm.put("settledAmount", p.getSettledAmount());
                pm.put("status", p.getStatus());
                pm.put("costTotal", cipCostDao.sumTotal(p.getId()));
            }
            out.put("cipProject", pm);
        }
        if ("COMPLETED".equals(order.getReworkStatus()) && order.getAcceptDate() != null) {
            out.put("overdueDays", ChronoUnit.DAYS.between(order.getAcceptDate(), LocalDate.now()));
        }
        return out;
    }

    // ================= 私有助手 =================

    private EamReworkOrder require(String id) {
        EamReworkOrder order = orderDao.selectById(id);
        if (order == null || !"0".equals(order.getDelFlag())) {
            throw new ServiceException(404, "技改单不存在：" + id);
        }
        return order;
    }

    private EamAssetCard requireCard(String cardId) {
        EamAssetCard card = cardDao.selectById(cardId);
        if (card == null || !"0".equals(card.getDelFlag())) {
            throw new ServiceException(422, "原卡不存在：" + cardId);
        }
        return card;
    }

    /** D6 前置断言：状态/冻结/安全锁/未结工单/在途调拨，逐项列示 */
    private void assertPreConditions(EamAssetCard card) {
        List<String> blockers = new ArrayList<>();
        if (!"IN_USE".equals(card.getStatus())) {
            blockers.add("卡片 " + card.getCardNo() + " 状态非在用（" + card.getStatus() + "），不可发起技改");
        }
        if ("1".equals(card.getCountFreezeFlag())) {
            blockers.add("卡片 " + card.getCardNo() + " 处于盘点冻结状态，不可技改");
        }
        try {
            safetyLockService.assertNoActiveLock(card.getId());
        } catch (ServiceException ex) {
            blockers.add("卡片 " + card.getCardNo() + "：" + ex.getMessage());
        }
        List<EamWorkOrder> openWos = workOrderDao.selectList(new LambdaQueryWrapper<EamWorkOrder>()
                .eq(EamWorkOrder::getCardId, card.getId())
                .notIn(EamWorkOrder::getStatus, "CLOSED", "CANCELLED"));
        for (EamWorkOrder wo : openWos) {
            blockers.add("卡片 " + card.getCardNo() + " 存在未关闭维修工单：" + wo.getWoNo());
        }
        EamTransferOrder openTrf = transferOrderDao.selectInFlightByCard(card.getId(), null);
        if (openTrf != null) {
            blockers.add("卡片 " + card.getCardNo() + " 存在未关闭调拨单：" + openTrf.getTrfNo());
        }
        if (!blockers.isEmpty()) {
            throw new ServiceException(422, "技改前置校验未通过（逐项列示）：" + String.join("；", blockers));
        }
    }

    private List<EamReworkItem> buildItems(String orderId, List<EamReworkItem> items) {
        List<EamReworkItem> out = new ArrayList<>();
        if (items == null) {
            return out;
        }
        for (EamReworkItem it : items) {
            if (it == null || !hasText(it.getCondType())) {
                throw new ServiceException(422, "判定依据条件类型必填");
            }
            if (!"LIFT_LIFE".equals(it.getCondType()) && !"LIFT_CAPACITY".equals(it.getCondType())
                    && !"LIFT_QUALITY".equals(it.getCondType())) {
                throw new ServiceException(422, "条件类型仅 LIFT_LIFE / LIFT_CAPACITY / LIFT_QUALITY");
            }
            EamReworkItem rb = new EamReworkItem();
            rb.setOrderId(orderId);
            rb.setCondType(it.getCondType());
            rb.setQuantValue(it.getQuantValue());
            rb.setEvidenceNo(it.getEvidenceNo());
            rb.setRemark(it.getRemark());
            rb.setCreateBy(SecurityUtils.getCurrentUserId());
            out.add(rb);
        }
        return out;
    }

    /** BR-37 量化：寿命≥1 年 / 产能≥10% / 精度性能（定性） */
    private boolean qualifies(EamReworkItem it) {
        if ("LIFT_QUALITY".equals(it.getCondType())) {
            return true;
        }
        if (it.getQuantValue() == null) {
            return false;
        }
        if ("LIFT_LIFE".equals(it.getCondType())) {
            return it.getQuantValue().compareTo(BigDecimal.ONE) >= 0;
        }
        return it.getQuantValue().compareTo(new BigDecimal("10")) >= 0;
    }

    private boolean isFullyDepreciated(EamAssetCard card) {
        if ("FULLY".equals(card.getDeprStatus()) || "FULLY_DEPRECIATED".equals(card.getDeprStatus())) {
            return true;
        }
        BigDecimal cost = nz(card.getAssetCost());
        BigDecimal accum = nz(card.getAccumDepr()).add(nz(card.getImpairment()));
        BigDecimal residual = card.getResidualValue() != null ? card.getResidualValue()
                : cost.multiply(nz(card.getResidualRate())).setScale(2, RoundingMode.HALF_UP);
        return cost.signum() > 0 && accum.compareTo(cost.subtract(residual)) >= 0;
    }

    private BigDecimal capitalizationFloor(String categoryCode) {
        EamAssetCategory category = categoryByCode(categoryCode);
        if (category == null || category.getCapitalizationFloor() == null) {
            throw new ServiceException(422, "卡片分类 " + categoryCode + " 未配置资本化下限（CAPITALIZATION_FLOOR）");
        }
        return category.getCapitalizationFloor();
    }

    private void requireAccounts(String... codes) {
        List<String> missing = new ArrayList<>();
        for (String code : codes) {
            FinAccount acc = accountDao.selectOne(new LambdaQueryWrapper<FinAccount>()
                    .eq(FinAccount::getAccountCode, code)
                    .eq(FinAccount::getDelFlag, "0").last("LIMIT 1"));
            if (acc == null) {
                missing.add(code);
            }
        }
        if (!missing.isEmpty()) {
            throw new ServiceException(422, "凭证科目缺失（L1 阻断）：" + String.join(",", missing));
        }
    }

    private String nextRwfNo() {
        String prefix = "RWF" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMM"));
        Long seq = orderDao.selectCount(new LambdaQueryWrapper<EamReworkOrder>()
                .likeRight(EamReworkOrder::getRwfNo, prefix));
        return prefix + String.format("%04d", (seq == null ? 0 : seq) + 1);
    }

    private String nextCardNo() {
        String prefix = "ZC" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        Integer max = cardDao.selectMaxSeq(prefix);
        return prefix + String.format("%04d", (max == null ? 0 : max) + 1);
    }

    private EamAssetCategory categoryByCode(String categoryCode) {
        return categoryDao.selectOne(new LambdaQueryWrapper<EamAssetCategory>()
                .eq(EamAssetCategory::getCategoryCode, categoryCode).last("LIMIT 1"));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static boolean hasText(String v) {
        return v != null && !v.isBlank();
    }
}
