package com.erp.service.impl.eam;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.eam.EamAssetCardDao;
import com.erp.dao.eam.EamTransferOrderDao;
import com.erp.dao.eam.EamWorkOrderDao;
import com.erp.dao.mdm.MdmLegalEntityDao;
import com.erp.entity.eam.EamAssetCard;
import com.erp.entity.eam.EamTransferOrder;
import com.erp.entity.eam.EamWorkOrder;
import com.erp.entity.mdm.MdmLegalEntity;
import com.erp.service.eam.EamSafetyLockService;
import com.erp.service.eam.EamTransferService;
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
 * 资产调拨实现（add-asset-transfer-lease，spec asset-transfer-lease，design D2/D3/D4）。
 * BR-21 四前置聚合（未关闭工单/安全挂锁/质押/保险桩位）→ 四节点审批 → 交接双确认 →
 * 完成即改卡片；CROSS_LE 追加 USCC 判定 + 双凭证 + 内部交易单据 + 开票待办（同事务回滚）。
 */
@Slf4j
@Service
public class EamTransferServiceImpl implements EamTransferService {

    private final EamTransferOrderDao trfDao;
    private final EamAssetCardDao cardDao;
    private final EamWorkOrderDao workOrderDao;
    private final MdmLegalEntityDao legalEntityDao;
    private final EamSafetyLockService safetyLockService;
    private final GlVoucherService voucherService;
    private final NoticeService noticeService;
    private final com.erp.service.approval.ApprovalEngine approvalEngine;
    private final com.erp.service.SysParamService sysParamService;

    public EamTransferServiceImpl(EamTransferOrderDao trfDao,
                                  EamAssetCardDao cardDao,
                                  EamWorkOrderDao workOrderDao,
                                  MdmLegalEntityDao legalEntityDao,
                                  EamSafetyLockService safetyLockService,
                                  GlVoucherService voucherService,
                                  NoticeService noticeService,
                                  com.erp.service.approval.ApprovalEngine approvalEngine,
                                  com.erp.service.SysParamService sysParamService) {
        this.trfDao = trfDao;
        this.cardDao = cardDao;
        this.workOrderDao = workOrderDao;
        this.legalEntityDao = legalEntityDao;
        this.safetyLockService = safetyLockService;
        this.voucherService = voucherService;
        this.noticeService = noticeService;
        this.approvalEngine = approvalEngine;
        this.sysParamService = sysParamService;
    }

    // ================= 分页 / 详情 =================

    @Override
    public Page<EamTransferOrder> page(long current, long size, String transferType,
                                       String status, String cardNo) {
        LambdaQueryWrapper<EamTransferOrder> qw = new LambdaQueryWrapper<EamTransferOrder>()
                .eq(hasText(transferType), EamTransferOrder::getTransferType, transferType)
                .eq(hasText(status), EamTransferOrder::getStatus, status)
                .like(hasText(cardNo), EamTransferOrder::getCardNo, cardNo)
                .orderByDesc(EamTransferOrder::getCreateDate);
        return trfDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    public Map<String, Object> detail(String id) {
        EamTransferOrder order = require(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("order", order);
        out.put("card", cardDao.selectById(order.getCardId()));
        return out;
    }

    // ================= 创建（BR-21 四前置聚合）=================

    @Override
    @Transactional
    public Map<String, Object> create(EamTransferOrder req) {
        if (!hasText(req.getCardId())) {
            throw new ServiceException(422, "资产卡片必填");
        }
        EamAssetCard card = cardDao.selectById(req.getCardId());
        if (card == null) {
            throw new ServiceException(422, "资产卡片不存在：" + req.getCardId());
        }
        String type = req.getTransferType() == null ? "" : req.getTransferType().trim().toUpperCase();
        if (!"DEPT".equals(type) && !"CROSS_LE".equals(type)) {
            throw new ServiceException(422, "调拨类型取值域：DEPT 部门间 / CROSS_LE 跨法人");
        }
        if (!hasText(req.getReason())) {
            throw new ServiceException(422, "调拨原因必填");
        }
        if (!"IN_USE".equals(card.getStatus())) {
            throw new ServiceException(422, "仅在用资产可发起调拨（当前状态 " + card.getStatus() + "）");
        }

        // BR-21 四前置 + 重复发起 + CROSS_LE 法人校验（create/submit 共用；新建单无自身豁免）
        assertTransferable(card, req.getCardId(), type, req.getToLe(), null);

        EamTransferOrder order = new EamTransferOrder();
        order.setTrfNo(nextTrfNo());
        order.setTransferType(type);
        order.setCardId(card.getId());
        order.setCardNo(card.getCardNo());
        // FROM 快照 = 卡片现行
        order.setFromDept(card.getDeptName());
        order.setFromCc(card.getCostCenterCode());
        order.setFromLe(card.getLegalEntity());
        order.setFromLocation(card.getStorageLocation());
        // TO 快照 = 入参
        order.setToDept(req.getToDept());
        order.setToCc(req.getToCc());
        order.setToLe("CROSS_LE".equals(type) ? req.getToLe() : card.getLegalEntity());
        order.setToLocation(req.getToLocation());
        order.setReason(req.getReason());
        order.setPlanDate(req.getPlanDate());
        order.setStatus("DRAFT");
        order.setCreateBy(SecurityUtils.getCurrentUserId());
        trfDao.insert(order);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("order", order);
        return out;
    }

    // ================= 提交审批（四节点链）=================

    @Override
    @Transactional
    public Map<String, Object> submit(String id) {
        EamTransferOrder order = require(id);
        if (!"DRAFT".equals(order.getStatus()) && !"REJECTED".equals(order.getStatus())) {
            throw new ServiceException(422, "仅草稿/已驳回可提交审批（当前 " + order.getStatus() + "）");
        }
        // 提交时重跑 BR-21（草稿期间状态可能变化）；不校验重复发起（本单自身豁免）
        EamAssetCard card = cardDao.selectById(order.getCardId());
        if (card == null) {
            throw new ServiceException(422, "资产卡片不存在：" + order.getCardId());
        }
        assertTransferable(card, order.getCardId(), order.getTransferType(), order.getToLe(),
                order.getId());
        return doSubmit(order);
    }

    /** BR-21 四前置聚合 + 重复发起 + CROSS_LE USCC 判定（create/submit 共用；anyHit → 422 逐项列示） */
    private void assertTransferable(EamAssetCard card, String cardId, String type,
                                    String toLeCode, String excludeOrderId) {
        // 重复发起阻断：同资产进行中调拨单（submit 时排除本单）
        EamTransferOrder inFlight = trfDao.selectInFlightByCard(cardId,
                excludeOrderId == null ? "" : excludeOrderId);
        if (inFlight != null) {
            throw new ServiceException(422, "该资产已有进行中的调拨单 " + inFlight.getTrfNo()
                    + "，不可重复发起");
        }
        // BR-21 四前置聚合：逐项校验、任一命中 L1 阻断并逐项列示
        List<String> blockers = new ArrayList<>();
        // ① 未关闭工单
        List<EamWorkOrder> openWos = workOrderDao.selectList(new LambdaQueryWrapper<EamWorkOrder>()
                .eq(EamWorkOrder::getCardId, cardId)
                .notIn(EamWorkOrder::getStatus, "CLOSED", "CANCELLED"));
        for (EamWorkOrder wo : openWos) {
            blockers.add("存在未关闭的" + ("PREVENTIVE".equals(wo.getWoType()) ? "维护" : "维修")
                    + "工单：" + wo.getWoNo());
        }
        // ② 安全停机挂锁未解除（复用运维断言）
        try {
            safetyLockService.assertNoActiveLock(cardId);
        } catch (ServiceException ex) {
            blockers.add(ex.getMessage());
        }
        // ③ 质押抵押
        if ("1".equals(card.getPledgedFlag())) {
            blockers.add("卡片已标记质押/抵押（PLEDGED_FLAG=1），不可调拨");
        }
        // ③b 盘点冻结（BR-4.13-30：标签异常/账实冲突冻结调拨与处置）
        if ("1".equals(card.getCountFreezeFlag())) {
            blockers.add("卡片处于盘点冻结状态（标签异常/账实冲突差异单未闭环，BR-4.13-30），不可调拨");
        }
        // ④ 保险理赔（保险域未建，桩位恒通过；偏差记录 design Non-Goals）
        //桩位：insuranceClaimOpen(cardId) → 恒 false

        if (!blockers.isEmpty()) {
            throw new ServiceException(422, "调拨前置校验未通过（BR-4.13-21，逐项列示）："
                    + String.join("；", blockers));
        }

        // CROSS_LE 前置：调入法人必填且 USCC 不同
        if ("CROSS_LE".equals(type)) {
            if (!hasText(toLeCode)) {
                throw new ServiceException(422, "跨法人调拨须指定调入法人主体");
            }
            MdmLegalEntity fromLe = findLe(card.getLegalEntity());
            MdmLegalEntity toLe = findLe(toLeCode);
            if (fromLe == null || toLe == null) {
                throw new ServiceException(422, "调出/调入法人主体不存在（LE_CODE 无效）");
            }
            if (eq(fromLe.getUscc(), toLe.getUscc())) {
                throw new ServiceException(422, "调出与调入法人 USCC 税号相同，不构成跨法人调拨（BR-4.13-23）");
            }
        }
    }

    private Map<String, Object> doSubmit(EamTransferOrder order) {
        order.setStatus("PENDING_APPROVE");
        order.setUpdateBy(SecurityUtils.getCurrentUserId());
        trfDao.updateById(order);

        // 四节点链：调出主管 → 调入主管 → 资产管理员（CROSS_LE 追加财务经理）
        List<List<com.erp.service.approval.ApprovalNodeSpec>> chain = new ArrayList<>();
        chain.add(List.of(com.erp.service.approval.ApprovalNodeSpec
                .sign("ROLE_DEPT_HEAD", "调出部门主管审核")));
        chain.add(List.of(com.erp.service.approval.ApprovalNodeSpec
                .sign("ROLE_DEPT_HEAD", "调入部门主管审核")));
        chain.add(List.of(com.erp.service.approval.ApprovalNodeSpec
                .sign("ROLE_ASSET_ADMIN", "资产管理员审核")));
        if ("CROSS_LE".equals(order.getTransferType())) {
            chain.add(List.of(com.erp.service.approval.ApprovalNodeSpec
                    .sign("ROLE_FIN_MGR", "财务经理审核（跨法人）")));
        }
        var inst = approvalEngine.submit("AssetTransfer", order.getId(),
                "资产调拨审核：" + order.getTrfNo() + " " + order.getCardNo()
                        + "（" + order.getTransferType() + "）",
                "ROLE_ASSET_ADMIN", chain);
        EamTransferOrder upd = new EamTransferOrder();
        upd.setId(order.getId());
        upd.setVerNo(order.getVerNo());
        upd.setApprId(inst.getId());
        trfDao.updateById(upd);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("order", trfDao.selectById(order.getId()));
        out.put("apprId", inst.getId());
        return out;
    }

    // ================= 回调入口 =================

    @Override
    @Transactional
    public Map<String, Object> onApprovalPassed(String id) {
        EamTransferOrder order = require(id);
        if ("COMPLETED".equals(order.getStatus())) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("order", order);
            out.put("idempotent", true);
            return out;
        }
        EamTransferOrder upd = new EamTransferOrder();
        upd.setId(order.getId());
        upd.setVerNo(order.getVerNo());
        upd.setStatus("APPROVED");
        upd.setUpdateBy(SecurityUtils.getCurrentUserId());
        trfDao.updateById(upd);
        log.info("调拨单 {} 审批通过，待实物交接双确认", order.getTrfNo());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("order", trfDao.selectById(order.getId()));
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> onApprovalRejected(String id, String reason) {
        EamTransferOrder order = require(id);
        EamTransferOrder upd = new EamTransferOrder();
        upd.setId(order.getId());
        upd.setVerNo(order.getVerNo());
        upd.setStatus("REJECTED");
        upd.setRejectReason(hasText(reason) ? reason : "审核驳回");
        upd.setUpdateBy(SecurityUtils.getCurrentUserId());
        trfDao.updateById(upd);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("order", trfDao.selectById(order.getId()));
        return out;
    }

    // ================= 交接双确认 =================

    @Override
    @Transactional
    public Map<String, Object> handoverConfirm(String id, String direction) {
        EamTransferOrder order = require(id);
        if (!"APPROVED".equals(order.getStatus()) && !"HANDOVERED".equals(order.getStatus())) {
            throw new ServiceException(422, "仅审批通过后可交接确认（当前 " + order.getStatus() + "）");
        }
        String dir = direction == null ? "" : direction.trim().toUpperCase();
        boolean out = "OUT".equals(dir);
        boolean in = "IN".equals(dir);
        if (!out && !in) {
            throw new ServiceException(422, "交接方向取值域：OUT 调出方 / IN 调入方");
        }
        String user = SecurityUtils.getCurrentUserId();
        EamTransferOrder upd = new EamTransferOrder();
        upd.setId(order.getId());
        upd.setVerNo(order.getVerNo());
        if (out) {
            if (hasText(order.getOutConfirmBy())) {
                throw new ServiceException(409, "调出方已确认交接：" + order.getOutConfirmBy());
            }
            upd.setOutConfirmBy(user);
            upd.setOutConfirmAt(LocalDateTime.now());
        } else {
            if (hasText(order.getInConfirmBy())) {
                throw new ServiceException(409, "调入方已确认交接：" + order.getInConfirmBy());
            }
            upd.setInConfirmBy(user);
            upd.setInConfirmAt(LocalDateTime.now());
        }
        trfDao.updateById(upd);
        EamTransferOrder fresh = trfDao.selectById(order.getId());
        // 双齐 → HANDOVERED（可完成）
        if (hasText(fresh.getOutConfirmBy()) && hasText(fresh.getInConfirmBy())) {
            EamTransferOrder s = new EamTransferOrder();
            s.setId(fresh.getId());
            s.setVerNo(fresh.getVerNo());
            s.setStatus("HANDOVERED");
            trfDao.updateById(s);
            fresh = trfDao.selectById(order.getId());
        }
        Map<String, Object> out2 = new LinkedHashMap<>();
        out2.put("order", fresh);
        out2.put("pending", hasText(fresh.getOutConfirmBy()) && hasText(fresh.getInConfirmBy())
                ? null : "待" + (hasText(fresh.getOutConfirmBy()) ? "调入方" : "调出方") + "确认");
        return out2;
    }

    // ================= 完成（即改卡片 + CROSS_LE 内部交易）=================

    @Override
    @Transactional
    public Map<String, Object> completeFromApproval(String id) {
        EamTransferOrder order = require(id);
        if ("COMPLETED".equals(order.getStatus())) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("order", order);
            out.put("idempotent", true);
            return out;
        }
        if (!"HANDOVERED".equals(order.getStatus())) {
            throw new ServiceException(422, "交接双确认未完成，不可置完成（当前 " + order.getStatus()
                    + "；" + (hasText(order.getOutConfirmBy()) && hasText(order.getInConfirmBy())
                    ? "状态异常" : "待" + (hasText(order.getOutConfirmBy()) ? "调入方" : "调出方") + "确认") + "）");
        }
        EamAssetCard card = cardDao.selectById(order.getCardId());
        if (card == null) {
            throw new ServiceException(422, "资产卡片不存在：" + order.getCardId());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("order", order);

        // DEPT/CROSS_LE 通用：同事务即改卡片部门/成本中心/地点（CROSS_LE 加法人）——先改后走内部交易，
        // 避免 doInterco 内处置更新与本更新 verNo 失配
        EamAssetCard cupd = new EamAssetCard();
        cupd.setId(card.getId());
        cupd.setVerNo(card.getVerNo());
        cupd.setDeptName(order.getToDept());
        cupd.setCostCenterCode(order.getToCc());
        cupd.setStorageLocation(order.getToLocation());
        if ("CROSS_LE".equals(order.getTransferType())) {
            cupd.setLegalEntity(order.getToLe());
        }
        cupd.setUpdateBy(SecurityUtils.getCurrentUserId());
        if (cardDao.updateById(cupd) == 0) {
            throw new ServiceException(409, "卡片更新冲突，请重试：" + card.getCardNo());
        }

        if ("CROSS_LE".equals(order.getTransferType())) {
            // 跨法人：内部交易（BR-4.13-23/C-4.13-10），任一失败整体回滚
            out.putAll(doInterco(order, cardDao.selectById(card.getId())));
        }

        // 置 COMPLETED + 完成时间（BR-22 折旧回溯锚）
        EamTransferOrder upd = new EamTransferOrder();
        upd.setId(order.getId());
        upd.setVerNo(trfDao.selectById(order.getId()).getVerNo());
        upd.setStatus("COMPLETED");
        upd.setCompletedAt(LocalDateTime.now());
        upd.setUpdateBy(SecurityUtils.getCurrentUserId());
        trfDao.updateById(upd);
        out.put("order", trfDao.selectById(order.getId()));
        return out;
    }

    /** 跨法人内部交易：调出处置凭证 + 调入建卡 + 建卡凭证 + 内部交易单据 + 开票待办 */
    private Map<String, Object> doInterco(EamTransferOrder order, EamAssetCard card) {
        MdmLegalEntity fromLe = findLe(order.getFromLe());
        MdmLegalEntity toLe = findLe(order.getToLe());
        if (fromLe == null || toLe == null) {
            throw new ServiceException(422, "调出/调入法人主体缺失，不可跨法人完成");
        }
        if (eq(fromLe.getUscc(), toLe.getUscc())) {
            throw new ServiceException(422, "调出与调入法人 USCC 相同，不可走内部交易（BR-4.13-23）");
        }
        BigDecimal cost = nz(card.getAssetCost());
        BigDecimal accum = nz(card.getAccumDepr());
        BigDecimal impair = nz(card.getImpairment());
        BigDecimal nbv = cost.subtract(accum).subtract(impair).setScale(2, RoundingMode.HALF_UP);
        String intercoNo = nextIntercoNo();
        String summaryOut = "跨法人调出：" + order.getTrfNo() + " " + card.getCardNo() + " / NBV " + nbv;
        String summaryIn = "跨法人调入建卡：" + order.getTrfNo() + " / 原值 " + nbv;

        // ① 调出处置凭证：借 1606 清理 NBV + 借 1602 累计折旧 / 贷 1601 固定资产原值
        List<FinVoucherLineSpec> outLines = new ArrayList<>();
        if (nbv.signum() != 0) {
            outLines.add(FinVoucherLineSpec.of("1606", "DR", nbv, summaryOut));
        }
        if (accum.signum() != 0) {
            outLines.add(FinVoucherLineSpec.of("1602", "DR", accum.setScale(2, RoundingMode.HALF_UP), summaryOut));
        }
        if (impair.signum() != 0) {
            outLines.add(FinVoucherLineSpec.of("1603", "DR", impair.setScale(2, RoundingMode.HALF_UP), summaryOut));
        }
        outLines.add(FinVoucherLineSpec.of("1601", "CR", cost.setScale(2, RoundingMode.HALF_UP), summaryOut));
        var outVoucher = voucherService.create("TRF", LocalDate.now(), summaryOut,
                "TRANSFER_OUT", order.getTrfNo(), null, outLines);

        // ② 调入方建卡（原值=NBV，次月起提；折旧政策沿用分类默认+卡片年限）
        EamAssetCard newCard = new EamAssetCard();
        newCard.setCardNo(nextCardNo());
        newCard.setAssetName(card.getAssetName());
        newCard.setSpecModel(card.getSpecModel());
        newCard.setCategoryCode(card.getCategoryCode());
        newCard.setLegalEntity(order.getToLe());
        newCard.setDeptName(order.getToDept());
        newCard.setCostCenterCode(order.getToCc());
        newCard.setStorageLocation(order.getToLocation());
        newCard.setAssetCost(nbv.signum() > 0 ? nbv : cost);
        newCard.setLifeYears(card.getLifeYears());
        newCard.setResidualRate(card.getResidualRate());
        newCard.setResidualValue(card.getResidualValue());
        newCard.setDepreciationMethod(card.getDepreciationMethod());
        newCard.setEffectiveDate(LocalDate.now());
        newCard.setStatus("IN_USE");
        newCard.setDeprStatus("NOT_DEPRECIATED");
        newCard.setAccumDepr(BigDecimal.ZERO);
        newCard.setLockFlag("1");
        newCard.setPledgedFlag("0");
        newCard.setSourceCapNo(order.getTrfNo());
        newCard.setCreateBy(SecurityUtils.getCurrentUserId());
        cardDao.insert(newCard);

        // ③ 建卡凭证：借 1601 固定资产 NBV / 贷 1221 内部往来
        String intercoAcct = requireParam("EAM_INTERCO_ACCOUNT");
        var inVoucher = voucherService.create("TRF", LocalDate.now(), summaryIn,
                "TRANSFER_IN", order.getTrfNo(), null, List.of(
                FinVoucherLineSpec.of("1601", "DR", newCard.getAssetCost(), summaryIn),
                FinVoucherLineSpec.of(intercoAcct, "CR", newCard.getAssetCost(), summaryIn)));

        // ④ 调出方卡片置 DISPOSED（已转移至对方法人，调入方以新卡承接）
        EamAssetCard dispose = new EamAssetCard();
        dispose.setId(card.getId());
        dispose.setVerNo(card.getVerNo());
        dispose.setStatus("DISPOSED");
        dispose.setUpdateBy(SecurityUtils.getCurrentUserId());
        cardDao.updateById(dispose);

        // ⑤ 单据锚回写 + 销项开票申请待办（发票域未建，仅待办；偏差记录）
        EamTransferOrder upd = new EamTransferOrder();
        upd.setId(order.getId());
        upd.setVerNo(order.getVerNo());
        upd.setIntercoNo(intercoNo);
        upd.setOutVoucherId(outVoucher.getId());
        upd.setInVoucherId(inVoucher.getId());
        upd.setNewCardId(newCard.getId());
        upd.setNbvAmount(newCard.getAssetCost());
        trfDao.updateById(upd);
        try {
            noticeService.push("ROLE_FIN_MGR", null, "销项开票申请待办（跨法人调拨）",
                    "调拨单 " + order.getTrfNo() + "（内部交易 " + intercoNo + "）已转至法人 "
                            + order.getToLe() + "，金额 " + newCard.getAssetCost()
                            + "，请开具增值税销项发票（发票域未建，仅待办留痕）",
                    "EAM_INTERCO_INVOICE", intercoNo);
        } catch (Exception ex) {
            log.warn("开票待办推送失败（不阻断完成事务）：{}", ex.getMessage());
        }
        log.info("跨法人调拨 {} 内部交易 {} 双凭证生成，新卡 {}", order.getTrfNo(), intercoNo,
                newCard.getCardNo());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("intercoNo", intercoNo);
        out.put("outVoucherId", outVoucher.getId());
        out.put("inVoucherId", inVoucher.getId());
        out.put("newCard", newCard);
        out.put("nbv", newCard.getAssetCost());
        return out;
    }

    // ================= C-4.13-10 月结卡控 =================

    @Override
    public Map<String, Object> assertIntercoComplete() {
        List<EamTransferOrder> gaps = trfDao.selectUnsettledInterco();
        Map<String, Object> out = new LinkedHashMap<>();
        if (!gaps.isEmpty()) {
            List<String> nos = new ArrayList<>();
            for (EamTransferOrder g : gaps) {
                nos.add(g.getTrfNo() + (hasText(g.getIntercoNo()) ? "" : "（缺内部交易单据）")
                        + (hasText(g.getOutVoucherId()) ? "" : "（缺调出凭证）")
                        + (hasText(g.getInVoucherId()) ? "" : "（缺调入凭证）"));
            }
            throw new ServiceException(422, "C-4.13-10 月结卡控阻断：以下跨法人调拨内部交易未闭环 → "
                    + String.join("；", nos));
        }
        out.put("passed", true);
        out.put("unsettled", 0);
        return out;
    }

    // ================= 闲置共享池（BR-4.13-05）=================

    @Override
    public List<Map<String, Object>> idlePool() {
        List<Map<String, Object>> out = new ArrayList<>();
        List<EamAssetCard> cards = cardDao.selectList(new LambdaQueryWrapper<EamAssetCard>()
                .eq(EamAssetCard::getStatus, "IN_USE"));
        LocalDate today = LocalDate.now();
        for (EamAssetCard card : cards) {
            LocalDate anchor = card.getLastUsedAt() != null ? card.getLastUsedAt()
                    : card.getEffectiveDate();
            if (anchor == null) {
                continue;
            }
            long months = ChronoUnit.MONTHS.between(anchor, today);
            if (months < 12) {
                continue;   // 未达 12 月，不入池
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("cardId", card.getId());
            row.put("cardNo", card.getCardNo());
            row.put("assetName", card.getAssetName());
            row.put("deptName", card.getDeptName());
            row.put("costCenterCode", card.getCostCenterCode());
            row.put("lastUsedAt", anchor);
            row.put("idleMonths", months);
            row.put("idleLevel", months >= 24 ? "DISPOSAL_EVAL" : "IDLE");
            row.put("transferable", true);   // 入池即可发起调拨（S-4.13-13）
            out.add(row);
            // ≥24 月：处置评估待办推资产管理员（处置走 7.6，本期仅待办）
            if (months >= 24) {
                try {
                    noticeService.push("ROLE_ASSET_ADMIN", null, "闲置资产处置评估待办",
                            "资产 " + card.getCardNo() + "（" + card.getAssetName()
                                    + "）闲置已 " + months + " 个月，请评估处置（BR-4.13-05，处置走 7.6）",
                            "EAM_IDLE_DISPOSAL", card.getId());
                } catch (Exception ex) {
                    log.warn("闲置处置待办推送失败（不阻断查询）：{}", ex.getMessage());
                }
            }
        }
        return out;
    }

    // ================= 内部 =================

    private EamTransferOrder require(String id) {
        EamTransferOrder order = id == null ? null : trfDao.selectById(id);
        if (order == null) {
            throw new ServiceException(404, "调拨单不存在：" + id);
        }
        return order;
    }

    private MdmLegalEntity findLe(String leCode) {
        if (!hasText(leCode)) {
            return null;
        }
        return legalEntityDao.selectOne(new LambdaQueryWrapper<MdmLegalEntity>()
                .eq(MdmLegalEntity::getLeCode, leCode)
                .eq(MdmLegalEntity::getDelFlag, "0")
                .last("LIMIT 1"));
    }

    private String nextTrfNo() {
        String prefix = "TRF" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        Integer max = trfDao.selectMaxSeq(prefix);
        return prefix + String.format("%04d", (max == null ? 0 : max) + 1);
    }

    private String nextIntercoNo() {
        String prefix = "ICR" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        Integer max = trfDao.selectMaxIntercoSeq(prefix);
        return prefix + String.format("%04d", (max == null ? 0 : max) + 1);
    }

    private String nextCardNo() {
        String prefix = "ZC" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        Integer max = cardDao.selectMaxSeq(prefix);
        return prefix + String.format("%04d", (max == null ? 0 : max) + 1);
    }

    private String requireParam(String key) {
        String v = sysParamService.getValue(key);
        if (!hasText(v)) {
            throw new ServiceException(422, "系统参数 " + key + " 未配置，跨法人凭证取数缺失");
        }
        return v;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
