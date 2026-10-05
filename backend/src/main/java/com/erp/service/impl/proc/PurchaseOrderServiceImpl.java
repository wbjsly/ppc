package com.erp.service.impl.proc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmSupplierDao;
import com.erp.dao.proc.FrameworkAgreementDao;
import com.erp.dao.proc.FrameworkAgreementLineDao;
import com.erp.dao.proc.ProcPrLineDao;
import com.erp.dao.proc.ProcRequisitionDao;
import com.erp.dao.proc.PurchaseOrderDao;
import com.erp.dao.proc.PurchaseOrderLineDao;
import com.erp.dao.proc.RfqDao;
import com.erp.entity.mdm.MdmSupplier;
import com.erp.entity.proc.FrameworkAgreement;
import com.erp.entity.proc.FrameworkAgreementLine;
import com.erp.entity.proc.ProcPrLine;
import com.erp.entity.proc.ProcRequisition;
import com.erp.entity.proc.PurchaseOrder;
import com.erp.entity.proc.PurchaseOrderLine;
import com.erp.entity.proc.PurchaseOrderVersion;
import com.erp.entity.proc.Rfq;
import com.erp.service.proc.ProcRequisitionService;
import com.erp.service.proc.PurchaseOrderService;
import com.erp.service.proc.RfqService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 采购订单（change add-framework-agreement-order，spec purchase-order，design D8）。
 * 三入口共用卡控：供应商 QUALIFIED（BR-4.2-18）、PR 超量回写（BR-4.2-51 复用既有桩）、
 * 协议状态与余量（S-4.2-03 / L1059）。价控引擎（组 7）在提交审批时接通。
 */
@Slf4j
@Service
public class PurchaseOrderServiceImpl implements PurchaseOrderService {

    public static final String DRAFT = "DRAFT";
    public static final String APPROVING = "APPROVING";
    public static final String APPROVED = "APPROVED";
    public static final String CLOSED = "CLOSED";

    private final PurchaseOrderDao poDao;
    private final PurchaseOrderLineDao lineDao;
    private final FrameworkAgreementDao agreementDao;
    private final FrameworkAgreementLineDao agreementLineDao;
    private final MdmSupplierDao mdmSupplierDao;
    private final RfqDao rfqDao;
    private final ProcRequisitionDao requisitionDao;
    private final ProcPrLineDao prLineDao;
    private final RfqService rfqService;
    private final ProcRequisitionService requisitionService;
    private final com.erp.service.proc.PoApprovalService poApprovalService;
    private final com.erp.service.proc.PriceControlService priceControlService;
    private final com.erp.dao.proc.PurchaseOrderVersionDao versionDao;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /** 合同/协议价上浮容差（PRICE_TOLERANCE，C-4.2-07 变更升级判据） */
    @org.springframework.beans.factory.annotation.Value("${app.proc.price-tolerance:0.05}")
    private BigDecimal priceTolerance;

    public PurchaseOrderServiceImpl(PurchaseOrderDao poDao,
                                    PurchaseOrderLineDao lineDao,
                                    FrameworkAgreementDao agreementDao,
                                    FrameworkAgreementLineDao agreementLineDao,
                                    MdmSupplierDao mdmSupplierDao,
                                    RfqDao rfqDao,
                                    ProcRequisitionDao requisitionDao,
                                    ProcPrLineDao prLineDao,
                                    RfqService rfqService,
                                    ProcRequisitionService requisitionService,
                                    com.erp.service.proc.PoApprovalService poApprovalService,
                                    com.erp.service.proc.PriceControlService priceControlService,
                                    com.erp.dao.proc.PurchaseOrderVersionDao versionDao,
                                    com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.poDao = poDao;
        this.lineDao = lineDao;
        this.agreementDao = agreementDao;
        this.agreementLineDao = agreementLineDao;
        this.mdmSupplierDao = mdmSupplierDao;
        this.rfqDao = rfqDao;
        this.requisitionDao = requisitionDao;
        this.prLineDao = prLineDao;
        this.rfqService = rfqService;
        this.requisitionService = requisitionService;
        this.poApprovalService = poApprovalService;
        this.priceControlService = priceControlService;
        this.versionDao = versionDao;
        this.objectMapper = objectMapper;
    }

    @Override
    public Page<PurchaseOrder> page(long current, long size, String status, String source, String keyword) {
        LambdaQueryWrapper<PurchaseOrder> qw = new LambdaQueryWrapper<PurchaseOrder>()
                .eq(isNotBlank(status), PurchaseOrder::getStatus, status)
                .eq(isNotBlank(source), PurchaseOrder::getSource, source)
                .and(isNotBlank(keyword), w -> w
                        .like(PurchaseOrder::getPoNo, keyword.trim())
                        .or().like(PurchaseOrder::getSupplierName, keyword.trim())
                        .or().like(PurchaseOrder::getPrNo, keyword.trim()))
                .orderByDesc(PurchaseOrder::getCreateDate);
        return poDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    public Map<String, Object> detail(String id) {
        PurchaseOrder po = requirePo(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("po", po);
        result.put("lines", linesOf(id));
        return result;
    }

    // ---------- 入口一：从协议下单（spec「订单三入口创建」/ 3.2 到期阻断 / 3.5 余量校验） ----------

    @Override
    @Transactional
    public Map<String, Object> createFromAgreement(Map<String, Object> payload) {
        String agreementId = str(payload.get("agreementId"));
        if (!isNotBlank(agreementId)) {
            throw new ServiceException(422, "agreementId 必填");
        }
        FrameworkAgreement fa = agreementDao.selectById(agreementId.trim());
        if (fa == null) {
            throw new ServiceException(404, "框架协议不存在");
        }
        // 3.2 到期/终止阻断引用（L1059：到期后新 PO 不可引用，已有 PO 正常执行）
        if (!"1".equals(fa.getStatus()) && !"2".equals(fa.getStatus())) {
            throw new ServiceException(422, "协议已到期或已终止，不可引用，请先续签（当前状态 "
                    + fa.getStatus() + "）");
        }
        Object rawLines = payload.get("lines");
        if (!(rawLines instanceof List<?> list) || list.isEmpty()) {
            throw new ServiceException(422, "下单明细行必填（agreementLineId + qty）");
        }

        // 逐行校验：归属、锁价、余量；并收集行
        record Pending(String agLineId, BigDecimal qty, LocalDate reqDate,
                       FrameworkAgreementLine agLine) {}
        List<Pending> pendings = new ArrayList<>();
        String supplierId = null;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                throw new ServiceException(422, "明细行格式非法");
            }
            String agLineId = str(m.get("agreementLineId"));
            if (!isNotBlank(agLineId)) {
                throw new ServiceException(422, "agreementLineId 必填");
            }
            FrameworkAgreementLine agl = agreementLineDao.selectById(agLineId.trim());
            if (agl == null || !fa.getId().equals(agl.getAgreementId())) {
                throw new ServiceException(422, "协议明细行不存在或不属于该协议：" + agLineId);
            }
            BigDecimal qty = toDecimal(m.get("qty"));
            if (qty == null || qty.signum() <= 0) {
                throw new ServiceException(422, agl.getItemCode() + " 下单数量须大于 0");
            }
            // 单价锁定（L908）：载荷若带 unitPrice 且与协议价不一致 → 422 篡改
            BigDecimal clientPrice = toDecimal(m.get("unitPrice"));
            if (clientPrice != null && clientPrice.compareTo(agl.getUnitPrice()) != 0) {
                throw new ServiceException(422, "协议物料单价锁定不可修改（FR-4.2-3-1）："
                        + agl.getItemCode() + " 协议价 " + agl.getUnitPrice());
            }
            // 3.5 余量校验
            if (agl.getCommitQty() != null) {
                BigDecimal ordered = agl.getOrderedQty() == null ? BigDecimal.ZERO : agl.getOrderedQty();
                if (ordered.add(qty).compareTo(agl.getCommitQty()) > 0) {
                    BigDecimal remain = agl.getCommitQty().subtract(ordered);
                    throw new ServiceException(422, "超出协议承诺量（S-4.2-03）：" + agl.getItemCode()
                            + " 承诺 " + agl.getCommitQty() + "，已下单 " + ordered
                            + "，剩余可下单 " + remain.max(BigDecimal.ZERO));
                }
            }
            // 同一 PO 仅支持同一供应商的协议行（头供应商唯一）
            if (supplierId == null) {
                supplierId = agl.getAwardSupplierId();
            } else if (!supplierId.equals(agl.getAwardSupplierId())) {
                throw new ServiceException(422, "所选协议行分属不同中标供应商，请分单下单");
            }
            pendings.add(new Pending(agl.getId(), qty, parseDate(m.get("reqDate")), agl));
        }
        requireQualified(supplierId);

        // 落单（编号 + 头 + 行）
        PurchaseOrder po = new PurchaseOrder();
        po.setPoNo(nextPoNo());
        po.setPoType("NORMAL");
        po.setSource("AGREEMENT");
        po.setSourceId(fa.getId());
        po.setSupplierId(supplierId);
        po.setSupplierName(supplierName(supplierId));
        po.setStatus(DRAFT);
        po.setApprovalBatch(0);
        po.setCurrVersion(1);
        po.setTaxRate(toDecimal(payload.get("taxRate")));
        po.setTaxCode(str(payload.get("taxCode")));
        po.setRemark(str(payload.get("remark")));
        BigDecimal total = BigDecimal.ZERO;
        List<PurchaseOrderLine> rows = new ArrayList<>();
        int no = 1;
        for (Pending p : pendings) {
            PurchaseOrderLine l = new PurchaseOrderLine();
            l.setLineNo(no++);
            l.setItemCode(p.agLine().getItemCode());
            l.setItemName(p.agLine().getItemName());
            l.setQty(p.qty());
            l.setUnitPrice(p.agLine().getUnitPrice());   // 带出锁定价
            l.setAmount(p.qty().multiply(p.agLine().getUnitPrice()).setScale(2, RoundingMode.HALF_UP));
            l.setReqDate(p.reqDate());
            l.setAgreementLineId(p.agLineId());
            l.setReceivedQty(BigDecimal.ZERO);
            l.setPriceCtrlResult("PASS");   // 协议价带出，价控第 1 级天然通过（组 7 落日志）
            total = total.add(l.getAmount());
            rows.add(l);
        }
        po.setTotalAmt(total);
        poDao.insert(po);
        insertLines(po.getId(), rows);

        // 余量回写（同事务 + 乐观校验：WHERE ORDERED_QTY = 旧值，冲突 → 409 整体回滚）
        for (Pending p : pendings) {
            BigDecimal old = p.agLine().getOrderedQty() == null ? BigDecimal.ZERO : p.agLine().getOrderedQty();
            int updated = agreementLineDao.update(null, new LambdaUpdateWrapper<FrameworkAgreementLine>()
                    .eq(FrameworkAgreementLine::getId, p.agLineId())
                    .eq(FrameworkAgreementLine::getOrderedQty, old)
                    .set(FrameworkAgreementLine::getOrderedQty, old.add(p.qty())));
            if (updated == 0) {
                throw new ServiceException(409, "协议余量并发冲突，请刷新后重试（"
                        + p.agLine().getItemCode() + "）");
            }
        }
        log.info("PO {} created from agreement {} supplier={} lines={} total={}",
                po.getPoNo(), fa.getAgreementNo(), po.getSupplierName(), rows.size(), total);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("po", po);
        result.put("lineCount", rows.size());
        return result;
    }

    // ---------- 入口二：RFQ 中选转 PO（消费 awarded 桩，供 2.3.1） ----------

    @Override
    @Transactional
    public Map<String, Object> createFromRfq(String prNo) {
        if (!isNotBlank(prNo)) {
            throw new ServiceException(422, "prNo 必填");
        }
        Map<String, Object> awarded = rfqService.awarded(prNo.trim());
        if (!Boolean.TRUE.equals(awarded.get("awarded"))) {
            throw new ServiceException(422, str(awarded.get("hint")) == null
                    ? "该 PR 无中选结果，不可转 PO" : str(awarded.get("hint")));
        }
        ProcRequisition pr = requisitionDao.selectOne(new LambdaQueryWrapper<ProcRequisition>()
                .eq(ProcRequisition::getPrNo, prNo.trim())
                .last("LIMIT 1"));
        if (pr == null) {
            throw new ServiceException(404, "PR 不存在：" + prNo);
        }
        String rfqNo = str(awarded.get("rfqNo"));
        Rfq rfq = rfqDao.selectOne(new LambdaQueryWrapper<Rfq>()
                .eq(Rfq::getRfqNo, rfqNo).last("LIMIT 1"));
        BigDecimal awardPrice = toDecimal(awarded.get("awardPrice"));
        if (awardPrice == null || awardPrice.signum() <= 0) {
            throw new ServiceException(422, "中选价缺失，不可转 PO");
        }
        String supplierId = str(awarded.get("supplierId"));
        requireQualified(supplierId);

        List<ProcPrLine> prLines = prLineDao.selectList(new LambdaQueryWrapper<ProcPrLine>()
                .eq(ProcPrLine::getPrId, pr.getId())
                .orderByAsc(ProcPrLine::getLineNo));
        if (prLines.isEmpty()) {
            throw new ServiceException(422, "PR 无请购行，不可转 PO");
        }

        PurchaseOrder po = new PurchaseOrder();
        po.setPoNo(nextPoNo());
        po.setPoType("1".equals(awarded.get("emergency")) ? "SPECIAL" : "NORMAL");
        po.setSource("RFQ");
        po.setSourceId(rfq == null ? null : rfq.getId());
        po.setSupplierId(supplierId);
        po.setSupplierName(str(awarded.get("supplierName")));
        po.setPrId(pr.getId());
        po.setPrNo(pr.getPrNo());
        po.setStatus(DRAFT);
        po.setApprovalBatch(0);
        po.setCurrVersion(1);

        List<PurchaseOrderLine> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        int no = 1;
        for (ProcPrLine pl : prLines) {
            PurchaseOrderLine l = new PurchaseOrderLine();
            l.setLineNo(no++);
            l.setItemCode(pl.getItemCode());
            l.setItemName(pl.getItemCode());   // PR 行无品名字段，按编码展示（主数据名可后续联查）
            l.setQty(pl.getQty());
            l.setUnitPrice(awardPrice);   // RFQ 模型：中选价适用于全部行
            l.setAmount(pl.getQty().multiply(awardPrice).setScale(2, RoundingMode.HALF_UP));
            l.setReqDate(pl.getReqDate());
            l.setPrLineId(pl.getId());
            l.setReceivedQty(BigDecimal.ZERO);
            total = total.add(l.getAmount());
            rows.add(l);
        }
        po.setTotalAmt(total);
        poDao.insert(po);
        insertLines(po.getId(), rows);

        // PR 回写（BR-4.2-51 复用既有桩：超量 422、达量自动关闭 PR 行）
        for (int i = 0; i < prLines.size(); i++) {
            requisitionService.receivePoAllocation(prLines.get(i).getId(), rows.get(i).getQty());
        }
        log.info("PO {} created from RFQ {} (pr {}) supplier={} total={}",
                po.getPoNo(), rfqNo, pr.getPrNo(), po.getSupplierName(), total);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("po", po);
        result.put("lineCount", rows.size());
        result.put("rfqNo", rfqNo);
        return result;
    }

    // ---------- 入口三：手工创建 ----------

    @Override
    @Transactional
    public Map<String, Object> createManual(Map<String, Object> payload) {
        String supplierId = str(payload.get("supplierId"));
        if (!isNotBlank(supplierId)) {
            throw new ServiceException(422, "supplierId 必填");
        }
        requireQualified(supplierId);   // BR-4.2-18
        Object rawLines = payload.get("lines");
        if (!(rawLines instanceof List<?> list) || list.isEmpty()) {
            throw new ServiceException(422, "订单明细行必填");
        }
        String poType = str(payload.get("poType"));
        if (poType == null || poType.trim().isEmpty()) {
            poType = "NORMAL";
        }
        if (!"NORMAL".equals(poType) && !"SPECIAL".equals(poType)) {
            throw new ServiceException(422, "poType 须为 NORMAL 或 SPECIAL");
        }

        PurchaseOrder po = new PurchaseOrder();
        po.setPoNo(nextPoNo());
        po.setPoType(poType);
        po.setSource("MANUAL");
        po.setSupplierId(supplierId.trim());
        po.setSupplierName(supplierName(supplierId.trim()));
        String prNo = str(payload.get("prNo"));
        if (isNotBlank(prNo)) {
            ProcRequisition pr = requisitionDao.selectOne(new LambdaQueryWrapper<ProcRequisition>()
                    .eq(ProcRequisition::getPrNo, prNo.trim()).last("LIMIT 1"));
            if (pr == null) {
                throw new ServiceException(404, "PR 不存在：" + prNo);
            }
            po.setPrId(pr.getId());
            po.setPrNo(pr.getPrNo());
        }
        po.setStatus(DRAFT);
        po.setApprovalBatch(0);
        po.setCurrVersion(1);
        po.setTaxRate(toDecimal(payload.get("taxRate")));
        po.setTaxCode(str(payload.get("taxCode")));
        po.setRemark(str(payload.get("remark")));

        List<PurchaseOrderLine> rows = new ArrayList<>();
        List<String> prLineIds = new ArrayList<>();
        List<BigDecimal> prQtys = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        int no = 1;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                throw new ServiceException(422, "明细行格式非法");
            }
            String itemCode = str(m.get("itemCode"));
            BigDecimal qty = toDecimal(m.get("qty"));
            BigDecimal price = toDecimal(m.get("unitPrice"));
            if (!isNotBlank(itemCode)) {
                throw new ServiceException(422, "明细行物料必填");
            }
            if (qty == null || qty.signum() <= 0) {
                throw new ServiceException(422, itemCode + " 数量须大于 0");
            }
            if (price == null || price.signum() < 0) {
                throw new ServiceException(422, itemCode + " 单价须为非负数");
            }
            PurchaseOrderLine l = new PurchaseOrderLine();
            l.setLineNo(no++);
            l.setItemCode(itemCode.trim());
            l.setItemName(str(m.get("itemName")) == null ? itemCode : str(m.get("itemName")));
            l.setQty(qty);
            l.setUnitPrice(price);
            l.setAmount(qty.multiply(price).setScale(2, RoundingMode.HALF_UP));
            l.setReqDate(parseDate(m.get("reqDate")));
            String prLineId = str(m.get("prLineId"));
            if (isNotBlank(prLineId)) {
                l.setPrLineId(prLineId.trim());
                prLineIds.add(prLineId.trim());
                prQtys.add(qty);
            }
            l.setReceivedQty(BigDecimal.ZERO);
            // 价控三重由组 7 在提交审批时执行并回填（创建时占位为 PENDING）
            l.setPriceCtrlResult("PENDING");
            total = total.add(l.getAmount());
            rows.add(l);
        }
        po.setTotalAmt(total);
        poDao.insert(po);
        insertLines(po.getId(), rows);

        // PR 行溯源回写（BR-4.2-51）
        for (int i = 0; i < prLineIds.size(); i++) {
            requisitionService.receivePoAllocation(prLineIds.get(i), prQtys.get(i));
        }
        log.info("PO {} manually created supplier={} lines={} total={}",
                po.getPoNo(), po.getSupplierName(), rows.size(), total);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("po", po);
        result.put("lineCount", rows.size());
        return result;
    }

    // ---------- 状态流转 ----------

    /**
     * 提交审批（DRAFT → APPROVING，批次 +1）。
     * 顺序（spec purchase-price-control）：先价控三重（BLOCK → 422 提示特批；
     * specialReason 非空 → BLOCK 降级 ESCALATE 转升级链），再三档判级生成节点
     * （行 ESCALATE → 链尾加签采购总监）。
     */
    @Override
    @Transactional
    public Map<String, Object> submit(String id, String specialReason) {
        PurchaseOrder po = requirePo(id);
        if (!DRAFT.equals(po.getStatus())) {
            throw new ServiceException(422, "仅草稿状态可提交审批，当前 " + po.getStatus());
        }
        if (po.getTotalAmt() == null || po.getTotalAmt().signum() <= 0) {
            throw new ServiceException(422, "订单金额无效，不可提交");
        }
        List<PurchaseOrderLine> lines = lineDao.selectList(new LambdaQueryWrapper<PurchaseOrderLine>()
                .eq(PurchaseOrderLine::getPoId, po.getId())
                .orderByAsc(PurchaseOrderLine::getLineNo));
        // 1) 价控三重（写日志 + 回填行结果；异常 422 直接中断本次提交）
        var pcLogs = priceControlService.execute(po, lines, specialReason);
        // 2) 状态推进
        po.setStatus(APPROVING);
        po.setApprovalBatch((po.getApprovalBatch() == null ? 0 : po.getApprovalBatch()) + 1);
        po.setUpdateBy(SecurityUtils.getCurrentUserId());
        poDao.updateById(po);
        // 3) 三档判级生成审批节点（design D1；ESCALATE 行加签采购总监）
        var chain = poApprovalService.createTasks(po);
        log.info("PO {} submitted batch={} priceLogs={} chain={}", po.getPoNo(),
                po.getApprovalBatch(), pcLogs.size(), chain.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", po.getStatus());
        result.put("approvalBatch", po.getApprovalBatch());
        result.put("priceControl", pcLogs);
        result.put("chain", chain);
        return result;
    }

    /** 手工关闭（偏差 D5：本变更 PO 生命周期到此为止，收货闭环属 2.4） */
    @Override
    @Transactional
    public Map<String, Object> close(String id, String reason) {
        PurchaseOrder po = requirePo(id);
        if (CLOSED.equals(po.getStatus())) {
            throw new ServiceException(422, "订单已关闭");
        }
        if (APPROVING.equals(po.getStatus())) {
            throw new ServiceException(422, "审批中订单不可关闭，请先驳回或等待审批完成");
        }
        if (!isNotBlank(reason) || reason.trim().length() < 2) {
            throw new ServiceException(422, "关闭原因必填（不少于 2 字）");
        }
        po.setStatus(CLOSED);
        po.setCloseReason(reason.trim());
        po.setClosedBy(SecurityUtils.getCurrentUserId());
        po.setClosedDate(LocalDateTime.now());
        po.setUpdateBy(SecurityUtils.getCurrentUserId());
        poDao.updateById(po);
        log.info("PO {} closed by {}: {}", po.getPoNo(), po.getClosedBy(), reason.trim());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", po.getStatus());
        result.put("closeReason", po.getCloseReason());
        return result;
    }

    // ---------- 变更与版本（FR-4.2-9 / BR-4.2-03/04/42，task 8.1~8.3） ----------

    /**
     * 变更发起：仅已批准且无待审批变更。快照当前版本（版本号 = 变更前状态）→ 应用新行集 →
     * CURR_VERSION+1。金额未增直接 APPROVED 留痕（BR-4.2-04）；金额增加 PENDING + 分级
     * （>PRICE_TOLERANCE → 采购总监，C-4.2-07）并重跑价控与预算（BR-4.2-42，BLOCK 则整体回滚）。
     * 载荷：{chgType, chgReason, lines:[{lineId, qty?, reqDate?, unitPrice?, cancel?}], newLines:[...]}
     */
    @Override
    @Transactional
    public Map<String, Object> change(String id, Map<String, Object> payload) {
        PurchaseOrder po = requirePo(id);
        if (!APPROVED.equals(po.getStatus())) {
            throw new ServiceException(422, "仅已批准且未关闭的订单可变更（C-4.2-08 口径：未关闭方可变更），当前 "
                    + po.getStatus());
        }
        if (versionDao.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PurchaseOrderVersion>()
                .eq(PurchaseOrderVersion::getPoId, id)
                .eq(PurchaseOrderVersion::getApprovalStatus, "PENDING")) > 0) {
            throw new ServiceException(422, "存在待审批的变更，请先审批或驳回");
        }
        String chgType = str(payload.get("chgType"));
        if (!Set.of("QTY", "DATE", "PRICE", "LINE_CANCEL", "LINE_ADD").contains(chgType == null ? "" : chgType)) {
            throw new ServiceException(422, "chgType 须为 QTY/DATE/PRICE/LINE_CANCEL/LINE_ADD");
        }
        String reason = str(payload.get("chgReason"));
        if (reason == null || reason.trim().length() < 2) {
            throw new ServiceException(422, "变更原因必填（不少于 2 字）");
        }

        List<PurchaseOrderLine> cur = lineDao.selectList(new LambdaQueryWrapper<PurchaseOrderLine>()
                .eq(PurchaseOrderLine::getPoId, id)
                .orderByAsc(PurchaseOrderLine::getLineNo));
        if (cur.isEmpty()) {
            throw new ServiceException(422, "订单无明细行，不可变更");
        }
        // 先固化变更前快照（必须早于任何载荷应用——行对象为同引用，晚建会存到改后状态）
        String preSnapshot = buildSnapshot(po, cur);
        // 应用行更新/取消
        Map<String, PurchaseOrderLine> byId = new LinkedHashMap<>();
        cur.forEach(l -> byId.put(l.getId(), l));
        List<PurchaseOrderLine> kept = new ArrayList<>(cur);
        if (payload.get("lines") instanceof List<?> updates) {
            for (Object o : updates) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                String lineId = str(m.get("lineId"));
                PurchaseOrderLine target = lineId == null ? null : byId.get(lineId);
                if (target == null) {
                    throw new ServiceException(422, "变更行不存在：" + lineId);
                }
                if (Boolean.TRUE.equals(m.get("cancel"))) {
                    kept.removeIf(x -> x.getId().equals(lineId));
                    continue;
                }
                BigDecimal qty = toDecimal(m.get("qty"));
                BigDecimal price = toDecimal(m.get("unitPrice"));
                if (qty != null) {
                    if (qty.signum() <= 0) {
                        throw new ServiceException(422, "数量须大于 0");
                    }
                    target.setQty(qty);
                }
                if (price != null) {
                    if (price.signum() < 0) {
                        throw new ServiceException(422, "单价须为非负数");
                    }
                    target.setUnitPrice(price);
                }
                LocalDate reqDate = parseDate(m.get("reqDate"));
                if (reqDate != null) {
                    target.setReqDate(reqDate);
                }
            }
        }
        // 新增行
        if (payload.get("newLines") instanceof List<?> adds) {
            int no = kept.stream().mapToInt(PurchaseOrderLine::getLineNo).max().orElse(0);
            for (Object o : adds) {
                if (!(o instanceof Map<?, ?> m)) {
                    throw new ServiceException(422, "新增行格式非法");
                }
                String itemCode = str(m.get("itemCode"));
                BigDecimal qty = toDecimal(m.get("qty"));
                BigDecimal price = toDecimal(m.get("unitPrice"));
                if (itemCode == null || itemCode.trim().isEmpty()) {
                    throw new ServiceException(422, "新增行物料必填");
                }
                if (qty == null || qty.signum() <= 0) {
                    throw new ServiceException(422, itemCode + " 数量须大于 0");
                }
                if (price == null || price.signum() < 0) {
                    throw new ServiceException(422, itemCode + " 单价须为非负数");
                }
                PurchaseOrderLine l = new PurchaseOrderLine();
                l.setLineNo(++no);
                l.setItemCode(itemCode.trim());
                l.setItemName(str(m.get("itemName")) == null ? itemCode : str(m.get("itemName")));
                l.setQty(qty);
                l.setUnitPrice(price);
                l.setReqDate(parseDate(m.get("reqDate")));
                l.setReceivedQty(BigDecimal.ZERO);
                l.setPriceCtrlResult("PENDING");
                kept.add(l);
            }
        }
        if (kept.isEmpty()) {
            throw new ServiceException(422, "变更后订单不可为空（请使用关闭订单）");
        }
        // 重算金额
        BigDecimal newTotal = BigDecimal.ZERO;
        for (PurchaseOrderLine l : kept) {
            l.setAmount(l.getQty().multiply(l.getUnitPrice()).setScale(2, RoundingMode.HALF_UP));
            newTotal = newTotal.add(l.getAmount());
        }
        BigDecimal oldTotal = po.getTotalAmt() == null ? BigDecimal.ZERO : po.getTotalAmt();
        boolean increased = newTotal.compareTo(oldTotal) > 0;
        boolean overTolerance = increased && newTotal.compareTo(
                oldTotal.multiply(BigDecimal.ONE.add(priceTolerance)).setScale(2, RoundingMode.HALF_UP)) > 0;

        // 快照当前版本（变更前状态）+ 分级审批标记
        PurchaseOrderVersion v = new PurchaseOrderVersion();
        v.setPoId(id);
        v.setVersionNo(po.getCurrVersion());
        v.setChgType(chgType);
        v.setChgReason(reason.trim());
        v.setSnapshotJson(preSnapshot);
        if (increased) {
            v.setApprovalStatus("PENDING");
            v.setReqRole(overTolerance ? "PURCHASE_DIRECTOR" : "PURCHASE_MANAGER");
            v.setReqReason(overTolerance
                    ? "总金额 " + oldTotal + " → " + newTotal + " 超原值 ×(1+" + priceTolerance
                    + ")，升级采购总监（C-4.2-07 / BR-4.2-42）"
                    : "总金额 " + oldTotal + " → " + newTotal + "（≤容差，采购经理审批，FR-4.2-9-2）");
        } else {
            v.setApprovalStatus("APPROVED");   // 调减/未增：仅留痕（BR-4.2-04）
            v.setReqReason("金额未增加，留痕即生效（BR-4.2-04）");
        }
        v.setCreateBy(SecurityUtils.getCurrentUserId());
        versionDao.insert(v);

        // 应用新行集
        lineDao.delete(new LambdaQueryWrapper<PurchaseOrderLine>().eq(PurchaseOrderLine::getPoId, id));
        for (PurchaseOrderLine l : kept) {
            l.setPoId(id);
            l.setCreateBy(SecurityUtils.getCurrentUserId());
            lineDao.insert(l);
        }
        po.setTotalAmt(newTotal);
        po.setCurrVersion((po.getCurrVersion() == null ? 1 : po.getCurrVersion()) + 1);
        po.setUpdateBy(SecurityUtils.getCurrentUserId());
        poDao.updateById(po);

        // 金额增加 → 重跑价控与预算（BR-4.2-42；BLOCK 422 → 整体回滚本次变更）
        List<Map<String, Object>> pc = new ArrayList<>();
        if (increased) {
            pc = priceControlService.execute(po, kept, null);
        }
        log.info("PO {} change v{} type={} total {} -> {} approval={} reqRole={}",
                po.getPoNo(), v.getVersionNo(), chgType, oldTotal, newTotal,
                v.getApprovalStatus(), v.getReqRole());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", v);
        result.put("priceControl", pc);
        return result;
    }

    @Override
    public List<Map<String, Object>> versions(String id) {
        requirePo(id);
        List<Map<String, Object>> out = new ArrayList<>();
        for (PurchaseOrderVersion v : versionDao.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PurchaseOrderVersion>()
                        .eq(PurchaseOrderVersion::getPoId, id)
                        .orderByDesc(PurchaseOrderVersion::getVersionNo))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("versionNo", v.getVersionNo());
            row.put("chgType", v.getChgType());
            row.put("chgReason", v.getChgReason());
            row.put("approvalStatus", v.getApprovalStatus());
            row.put("reqRole", v.getReqRole());
            row.put("reqReason", v.getReqReason());
            row.put("reqApprovedBy", v.getReqApprovedBy());
            row.put("reqApprovedDate", v.getReqApprovedDate());
            row.put("createBy", v.getCreateBy());
            row.put("createDate", v.getCreateDate());
            out.add(row);
        }
        return out;
    }

    /** 变更审批：通过 → APPROVED；驳回 → 自动回退至变更前状态（原版本记 REJECTED + 回退留痕） */
    @Override
    @Transactional
    public Map<String, Object> approveChange(String id, int versionNo, boolean approved, String reason) {
        PurchaseOrder po = requirePo(id);
        PurchaseOrderVersion v = versionDao.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PurchaseOrderVersion>()
                        .eq(PurchaseOrderVersion::getPoId, id)
                        .eq(PurchaseOrderVersion::getVersionNo, versionNo));
        if (v == null) {
            throw new ServiceException(404, "版本不存在：" + versionNo);
        }
        if (!"PENDING".equals(v.getApprovalStatus())) {
            throw new ServiceException(422, "该版本不在待审批状态（当前 " + v.getApprovalStatus() + "）");
        }
        if (reason == null || reason.trim().length() < 2) {
            throw new ServiceException(422, "审批意见必填（不少于 2 字）");
        }
        v.setReqApprovedBy(SecurityUtils.getCurrentUserId());
        v.setReqApprovedDate(java.time.LocalDateTime.now());
        if (approved) {
            v.setApprovalStatus("APPROVED");
            v.setReqReason((v.getReqReason() == null ? "" : v.getReqReason() + "；")
                    + "审批意见：" + reason.trim());
            versionDao.updateById(v);
            log.info("PO {} change v{} approved by {}: {}", po.getPoNo(), versionNo,
                    v.getReqApprovedBy(), reason.trim());
        } else {
            v.setApprovalStatus("REJECTED");
            v.setReqReason((v.getReqReason() == null ? "" : v.getReqReason() + "；")
                    + "驳回意见：" + reason.trim());
            versionDao.updateById(v);
            // 自动回退：记录被驳回状态（版本 N+1 快照）→ 恢复变更前快照
            PurchaseOrderVersion rv = new PurchaseOrderVersion();
            rv.setPoId(id);
            rv.setVersionNo(po.getCurrVersion());
            rv.setChgType("ROLLBACK");
            rv.setChgReason("变更 v" + versionNo + " 被驳回，自动回退：" + reason.trim());
            rv.setSnapshotJson(buildSnapshot(po, lineDao.selectList(
                    new LambdaQueryWrapper<PurchaseOrderLine>().eq(PurchaseOrderLine::getPoId, id))));
            rv.setApprovalStatus("APPROVED");
            rv.setReqReason("驳回回退（FR-4.2-9-2）");
            rv.setCreateBy(SecurityUtils.getCurrentUserId());
            versionDao.insert(rv);
            applySnapshot(po, v.getSnapshotJson());
            po.setCurrVersion((po.getCurrVersion() == null ? 1 : po.getCurrVersion()) + 1);
            po.setUpdateBy(SecurityUtils.getCurrentUserId());
            poDao.updateById(po);
            log.info("PO {} change v{} rejected by {}, auto-rolled-back to pre-change state",
                    po.getPoNo(), versionNo, rv.getCreateBy());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("versionNo", versionNo);
        result.put("approvalStatus", v.getApprovalStatus());
        result.put("currVersion", po.getCurrVersion());
        return result;
    }

    /** 按旧版本回滚：生成新版本（BR-4.2-03 回滚生成新版本，旧版本永久只读） */
    @Override
    @Transactional
    public Map<String, Object> rollback(String id, int versionNo) {
        PurchaseOrder po = requirePo(id);
        if (!APPROVED.equals(po.getStatus())) {
            throw new ServiceException(422, "仅已批准订单可回滚，当前 " + po.getStatus());
        }
        if (versionDao.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PurchaseOrderVersion>()
                .eq(PurchaseOrderVersion::getPoId, id)
                .eq(PurchaseOrderVersion::getApprovalStatus, "PENDING")) > 0) {
            throw new ServiceException(422, "存在待审批的变更，请先审批或驳回");
        }
        if (po.getCurrVersion() != null && versionNo >= po.getCurrVersion()) {
            throw new ServiceException(422, "仅可回滚到小于当前版本（"
                    + po.getCurrVersion() + "）的历史版本");
        }
        PurchaseOrderVersion target = versionDao.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PurchaseOrderVersion>()
                        .eq(PurchaseOrderVersion::getPoId, id)
                        .eq(PurchaseOrderVersion::getVersionNo, versionNo));
        if (target == null || target.getSnapshotJson() == null) {
            throw new ServiceException(404, "目标版本快照不存在：" + versionNo);
        }
        // 收货调整快照（add-goods-receipt）非行集结构，不可作为回滚目标
        if ("RECEIPT_ADJUST".equals(target.getChgType())) {
            throw new ServiceException(422, "收货调整版本快照（RECEIPT_ADJUST）不可回滚，"
                    + "请回滚到普通变更版本或使用收货调整单反向操作");
        }
        // 当前状态存为新版本（回滚留痕）
        PurchaseOrderVersion rv = new PurchaseOrderVersion();
        rv.setPoId(id);
        rv.setVersionNo(po.getCurrVersion());
        rv.setChgType("ROLLBACK");
        rv.setChgReason("回滚至 v" + versionNo);
        rv.setSnapshotJson(buildSnapshot(po, lineDao.selectList(
                new LambdaQueryWrapper<PurchaseOrderLine>().eq(PurchaseOrderLine::getPoId, id))));
        rv.setApprovalStatus("APPROVED");
        rv.setCreateBy(SecurityUtils.getCurrentUserId());
        versionDao.insert(rv);

        applySnapshot(po, target.getSnapshotJson());
        po.setCurrVersion((po.getCurrVersion() == null ? 1 : po.getCurrVersion()) + 1);
        po.setUpdateBy(SecurityUtils.getCurrentUserId());
        poDao.updateById(po);
        log.info("PO {} rolled back to v{} (now currVersion={})", po.getPoNo(),
                versionNo, po.getCurrVersion());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("currVersion", po.getCurrVersion());
        result.put("restoredFrom", versionNo);
        result.put("totalAmt", po.getTotalAmt());
        return result;
    }

    // ---------- 快照辅助 ----------

    private String buildSnapshot(PurchaseOrder po, List<PurchaseOrderLine> lines) {
        try {
            Map<String, Object> head = new LinkedHashMap<>();
            head.put("poType", po.getPoType());
            head.put("totalAmt", po.getTotalAmt());
            head.put("taxRate", po.getTaxRate());
            head.put("taxCode", po.getTaxCode());
            head.put("currency", po.getCurrency());
            head.put("remark", po.getRemark());
            List<Map<String, Object>> ls = new ArrayList<>();
            for (PurchaseOrderLine l : lines) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("lineNo", l.getLineNo());
                m.put("itemCode", l.getItemCode());
                m.put("itemName", l.getItemName());
                m.put("qty", l.getQty());
                m.put("unit", l.getUnit());
                m.put("unitPrice", l.getUnitPrice());
                m.put("taxRate", l.getTaxRate());
                m.put("amount", l.getAmount());
                m.put("reqDate", l.getReqDate());
                m.put("agreementLineId", l.getAgreementLineId());
                m.put("prLineId", l.getPrLineId());
                m.put("priceCtrlResult", l.getPriceCtrlResult());
                ls.add(m);
            }
            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("head", head);
            snap.put("lines", ls);
            return objectMapper.writeValueAsString(snap);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ServiceException(500, "版本快照生成失败：" + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void applySnapshot(PurchaseOrder po, String json) {
        try {
            Map<String, Object> snap = objectMapper.readValue(json, Map.class);
            Map<String, Object> head = (Map<String, Object>) snap.get("head");
            List<Map<String, Object>> lines = (List<Map<String, Object>>) snap.get("lines");
            po.setPoType(str(head.get("poType")));
            po.setTotalAmt(toDecimal(head.get("totalAmt")));
            po.setTaxRate(toDecimal(head.get("taxRate")));
            po.setTaxCode(str(head.get("taxCode")));
            po.setCurrency(str(head.get("currency")));
            po.setRemark(str(head.get("remark")));
            lineDao.delete(new LambdaQueryWrapper<PurchaseOrderLine>()
                    .eq(PurchaseOrderLine::getPoId, po.getId()));
            for (Map<String, Object> m : lines) {
                PurchaseOrderLine l = new PurchaseOrderLine();
                l.setPoId(po.getId());
                l.setLineNo(Integer.valueOf(String.valueOf(m.get("lineNo"))));
                l.setItemCode(str(m.get("itemCode")));
                l.setItemName(str(m.get("itemName")));
                l.setQty(toDecimal(m.get("qty")));
                l.setUnit(str(m.get("unit")));
                l.setUnitPrice(toDecimal(m.get("unitPrice")));
                l.setTaxRate(toDecimal(m.get("taxRate")));
                l.setAmount(toDecimal(m.get("amount")));
                l.setReqDate(m.get("reqDate") == null ? null
                        : LocalDate.parse(String.valueOf(m.get("reqDate")).substring(0, 10)));
                l.setAgreementLineId(str(m.get("agreementLineId")));
                l.setPrLineId(str(m.get("prLineId")));
                l.setReceivedQty(BigDecimal.ZERO);
                l.setPriceCtrlResult(str(m.get("priceCtrlResult")));
                l.setCreateBy(SecurityUtils.getCurrentUserId());
                lineDao.insert(l);
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ServiceException(500, "版本快照解析失败：" + e.getMessage());
        }
    }

    @Override
    public String nextPoNo() {
        String prefix = "PO" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMM")) + "-";
        Integer max = poDao.selectMaxSeq(prefix);
        return prefix + String.format("%06d", (max == null ? 0 : max) + 1);
    }

    @Override
    public List<Map<String, Object>> linesOf(String poId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PurchaseOrderLine l : lineDao.selectList(new LambdaQueryWrapper<PurchaseOrderLine>()
                .eq(PurchaseOrderLine::getPoId, poId)
                .orderByAsc(PurchaseOrderLine::getLineNo))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", l.getId());
            row.put("lineNo", l.getLineNo());
            row.put("itemCode", l.getItemCode());
            row.put("itemName", l.getItemName());
            row.put("qty", l.getQty());
            row.put("unitPrice", l.getUnitPrice());
            row.put("amount", l.getAmount());
            row.put("reqDate", l.getReqDate());
            row.put("agreementLineId", l.getAgreementLineId());
            row.put("prLineId", l.getPrLineId());
            row.put("receivedQty", l.getReceivedQty());
            row.put("priceCtrlResult", l.getPriceCtrlResult());
            out.add(row);
        }
        return out;
    }

    // ---------- 私有 ----------

    private void insertLines(String poId, List<PurchaseOrderLine> rows) {
        for (PurchaseOrderLine l : rows) {
            l.setPoId(poId);
            l.setCreateBy(SecurityUtils.getCurrentUserId());
            lineDao.insert(l);
        }
    }

    private PurchaseOrder requirePo(String id) {
        PurchaseOrder po = poDao.selectById(id);
        if (po == null) {
            throw new ServiceException(404, "采购订单不存在");
        }
        return po;
    }

    /** BR-4.2-18：供应商状态非合格（冻结/停用/证照过期）硬阻断 */
    private void requireQualified(String supplierId) {
        if (!isNotBlank(supplierId)) {
            throw new ServiceException(422, "供应商必填");
        }
        MdmSupplier s = mdmSupplierDao.selectById(supplierId.trim());
        if (s == null) {
            throw new ServiceException(422, "供应商不存在：" + supplierId);
        }
        if (!"QUALIFIED".equals(s.getStatus())) {
            throw new ServiceException(422, "供应商状态 " + s.getStatus()
                    + " 不可创建或下达 PO（BR-4.2-18），请联系供应商管理员处理");
        }
    }

    private String supplierName(String supplierId) {
        MdmSupplier s = mdmSupplierDao.selectById(supplierId);
        return s == null ? supplierId : s.getSupplierName();
    }

    private LocalDate parseDate(Object o) {
        if (o == null || !isNotBlank(String.valueOf(o))) {
            return null;
        }
        try {
            return LocalDate.parse(String.valueOf(o).trim().substring(0, 10));
        } catch (Exception e) {
            throw new ServiceException(422, "日期格式非法（应为 yyyy-MM-dd）：" + o);
        }
    }

    private boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private BigDecimal toDecimal(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
