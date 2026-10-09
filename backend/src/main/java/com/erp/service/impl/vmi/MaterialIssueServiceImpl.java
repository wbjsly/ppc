package com.erp.service.impl.vmi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvStockDao;
import com.erp.dao.vmi.MaterialIssueDao;
import com.erp.dao.vmi.MaterialIssueLineDao;
import com.erp.dao.vmi.VmiStockDao;
import com.erp.entity.fin.FinAccrual;
import com.erp.entity.inv.InvStock;
import com.erp.entity.vmi.MaterialIssue;
import com.erp.entity.vmi.MaterialIssueLine;
import com.erp.entity.vmi.VmiStock;
import com.erp.service.fin.AccrualService;
import com.erp.service.vmi.MaterialIssueService;
import com.erp.service.vmi.VmiAgreementService;
import com.erp.service.vmi.VmiAlertService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 领料出库实现（spec material-issue，design D5/D6）。
 * FIFO：自有库存按批次首入（stock 行 CREATE_DATE）升序；寄售按 INBOUND_DATE 升序。
 */
@Slf4j
@Service
public class MaterialIssueServiceImpl implements MaterialIssueService {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    private final MaterialIssueDao issueDao;
    private final MaterialIssueLineDao lineDao;
    private final InvStockDao stockDao;
    private final VmiStockDao vmiStockDao;
    private final com.erp.dao.mdm.MdmSupplierDao supplierDao;
    private final VmiAgreementService agreementService;
    private final VmiAlertService alertService;
    private final AccrualService accrualService;
    private final com.erp.service.inv.StockPostingEngine stockPostingEngine;
    private final com.erp.service.inv.FifoStrategyService fifoStrategyService;

    private final com.erp.service.inv.PickTaskGate pickTaskGate;
    private final com.erp.service.inv.PickTaskService pickTaskService;

    public MaterialIssueServiceImpl(MaterialIssueDao issueDao,
                                    MaterialIssueLineDao lineDao,
                                    InvStockDao stockDao,
                                    VmiStockDao vmiStockDao,
                                    com.erp.dao.mdm.MdmSupplierDao supplierDao,
                                    VmiAgreementService agreementService,
                                    VmiAlertService alertService,
                                    AccrualService accrualService,
                                    com.erp.service.inv.StockPostingEngine stockPostingEngine,
                                    com.erp.service.inv.FifoStrategyService fifoStrategyService,
                                    com.erp.service.inv.PickTaskGate pickTaskGate,
                                    com.erp.service.inv.PickTaskService pickTaskService) {
        this.issueDao = issueDao;
        this.lineDao = lineDao;
        this.stockDao = stockDao;
        this.vmiStockDao = vmiStockDao;
        this.supplierDao = supplierDao;
        this.agreementService = agreementService;
        this.alertService = alertService;
        this.accrualService = accrualService;
        this.stockPostingEngine = stockPostingEngine;
        this.fifoStrategyService = fifoStrategyService;
        this.pickTaskGate = pickTaskGate;
        this.pickTaskService = pickTaskService;
    }

    // ---------- 创建（DRAFT，FIFO 配批固化） ----------

    @Override
    public Map<String, Object> preview(Map<String, Object> payload) {
        MaterialIssue head = fromPayload(payload, new MaterialIssue());
        List<MaterialIssueLine> plan = fifoPlan(head, requested(payload));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("issueType", head.getIssueType());
        out.put("supplierId", head.getSupplierId());
        out.put("lines", plan);
        out.put("totalQty", plan.stream().map(MaterialIssueLine::getQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> create(Map<String, Object> payload) {
        requireRole("创建领料单", "ROLE_WAREHOUSE");
        MaterialIssue head = fromPayload(payload, new MaterialIssue());
        head.setIssueNo(nextIssueNo());
        head.setStatus(MaterialIssue.ST_DRAFT);
        head.setCreateBy(SecurityUtils.getCurrentUserId());
        issueDao.insert(head);
        List<MaterialIssueLine> plan = fifoPlan(head, requested(payload));
        int no = 1;
        for (MaterialIssueLine l : plan) {
            l.setIssueId(head.getId());
            l.setLineNo(no++);
            lineDao.insert(l);
        }
        log.info("领料单 {} created type={} wo={} lines={}", head.getIssueNo(),
                head.getIssueType(), head.getWorkOrderNo(), plan.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("issue", head);
        out.put("lines", plan);
        return out;
    }

    // ---------- 查询 ----------

    @Override
    public Page<Map<String, Object>> page(long current, long size, String issueType,
                                          String status, String workOrderNo, String keyword) {
        Page<MaterialIssue> p = issueDao.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<MaterialIssue>()
                        .eq(hasText(issueType), MaterialIssue::getIssueType, issueType)
                        .eq(hasText(status), MaterialIssue::getStatus, status)
                        .eq(hasText(workOrderNo), MaterialIssue::getWorkOrderNo, workOrderNo)
                        .and(hasText(keyword), w -> w
                                .like(MaterialIssue::getIssueNo, keyword.trim())
                                .or().like(MaterialIssue::getPurpose, keyword.trim())
                                .or().like(MaterialIssue::getDept, keyword.trim()))
                        .orderByDesc(MaterialIssue::getCreateDate));
        Page<Map<String, Object>> out = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MaterialIssue h : p.getRecords()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", h.getId());
            row.put("issueNo", h.getIssueNo());
            row.put("issueType", h.getIssueType());
            row.put("workOrderNo", h.getWorkOrderNo());
            row.put("dept", h.getDept());
            row.put("purpose", h.getPurpose());
            row.put("status", h.getStatus());
            row.put("transferDocNo", h.getTransferDocNo());
            row.put("cancelReason", h.getCancelReason());
            row.put("postDate", h.getPostDate());
            row.put("createDate", h.getCreateDate());
            row.put("lineCount", lineDao.selectCount(new LambdaQueryWrapper<MaterialIssueLine>()
                    .eq(MaterialIssueLine::getIssueId, h.getId())));
            rows.add(row);
        }
        out.setRecords(rows);
        return out;
    }

    @Override
    public Map<String, Object> detail(String id) {
        MaterialIssue h = require(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("issue", h);
        out.put("lines", lineDao.selectList(new LambdaQueryWrapper<MaterialIssueLine>()
                .eq(MaterialIssueLine::getIssueId, h.getId())
                .orderByAsc(MaterialIssueLine::getLineNo)));
        return out;
    }

    // ---------- 过账（POSTED，单事务） ----------

    @Override
    @Transactional
    public Map<String, Object> post(String id) {
        requireRole("过账领料单", "ROLE_WAREHOUSE");
        MaterialIssue h = require(id);
        if (!MaterialIssue.ST_DRAFT.equals(h.getStatus())) {
            throw new ServiceException(422, "仅草稿领料单可过账，当前 " + h.getStatus());
        }
        // 拣货差异过账门闩（spec picking-review，BR-4.4-29）
        pickTaskGate.assertClear("MATERIAL_OUT", h.getIssueNo());
        List<MaterialIssueLine> lines = lineDao.selectList(new LambdaQueryWrapper<MaterialIssueLine>()
                .eq(MaterialIssueLine::getIssueId, h.getId())
                .orderByAsc(MaterialIssueLine::getLineNo));
        if (lines.isEmpty()) {
            throw new ServiceException(422, "领料单无明细行");
        }
        if (MaterialIssue.TYPE_VMI.equals(h.getIssueType())) {
            postVmi(h, lines);
        } else {
            postOwn(h, lines);
        }
        h.setStatus(MaterialIssue.ST_POSTED);
        h.setPostBy(SecurityUtils.getCurrentUserId());
        h.setPostDate(LocalDateTime.now());
        h.setUpdateBy(SecurityUtils.getCurrentUserId());
        issueDao.updateById(h);
        if (MaterialIssue.TYPE_VMI.equals(h.getIssueType())) {
            // 低于最低水位补货建议扫描（BR-4.2-8-1 / design D4，领用后即时触发）
            alertService.scanReplenish();
        }
        // 过账成功联动：任务 DONE → COMPLETED（无任务跳过）
        pickTaskService.markCompleted("MATERIAL_OUT", h.getIssueNo());
        log.info("领料单 {} posted type={} lines={} transfer={}", h.getIssueNo(),
                h.getIssueType(), lines.size(), h.getTransferDocNo());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("issue", h);
        out.put("lineCount", lines.size());
        return out;
    }

    /** 自有库存：逐批复核当前可用量（防并发）→ 扣减 */
    private void postOwn(MaterialIssue h, List<MaterialIssueLine> lines) {
        // 扣减经通用引擎（add-stock-posting-engine，material-issue MODIFIED 过账）：
        // 指定批次直扣（配批行携带批次）、MATERIAL_OUT 流水、行级恒等由引擎承载
        List<com.erp.service.inv.StockPostingEngine.Line> els = new ArrayList<>();
        for (MaterialIssueLine l : lines) {
            com.erp.service.inv.StockPostingEngine.Line el =
                    new com.erp.service.inv.StockPostingEngine.Line();
            el.warehouseCode = InvStock.DEFAULT_WH;
            el.itemCode = l.getItemCode();
            el.itemName = l.getItemName();
            el.batchNo = l.getBatchNo();
            // 拣货推荐回写仓位（4.6.3）：有值则引擎按指定仓位扣减，空保持位级 FIFO
            el.binCode = l.getBinCode();
            el.qty = l.getQty();
            el.batchSpecified = true;
            els.add(el);
        }
        stockPostingEngine.post(com.erp.service.inv.StockPostingEngine.Request.of(
                "MATERIAL_OUT", "MATERIAL_ISSUE", h.getIssueNo(), els));
    }

    /** VMI 物权转移内核（design D6，spec vmi-consignment R5 / material-issue R2 ③） */
    private void postVmi(MaterialIssue h, List<MaterialIssueLine> lines) {
        String transferNo = nextTransferNo();
        // 1) 领用时点协议价（C-4.2-09：缺失/过期/价格条款未覆盖 → 422 阻断整体回滚）
        for (MaterialIssueLine l : lines) {
            var pl = agreementService.priceLineOn(h.getSupplierId(), l.getItemCode(),
                    java.time.LocalDate.now());
            if (pl == null) {
                throw new ServiceException(422, "协议价缺失或协议过期，阻断物权转移（C-4.2-09）："
                        + l.getItemCode() + "。请先维护/续签 VMI 协议价格条款");
            }
            l.setUnitPrice(pl.getUnitPrice());
            l.setAmount(l.getQty().multiply(pl.getUnitPrice()).setScale(2, RoundingMode.HALF_UP));
            lineDao.updateById(l);
        }
        // 2) 扣寄售库存（条件更新防并发）+ 累计领用
        for (MaterialIssueLine l : lines) {
            VmiStock s = vmiStockDao.selectOne(new LambdaQueryWrapper<VmiStock>()
                    .eq(VmiStock::getItemCode, l.getItemCode())
                    .eq(VmiStock::getBatchNo, l.getBatchNo() == null ? "" : l.getBatchNo())
                    .eq(VmiStock::getSupplierId, h.getSupplierId())
                    .last("LIMIT 1"));
            if (s == null) {
                throw new ServiceException(422, "寄售库存批次不存在：" + l.getItemCode()
                        + " / " + l.getBatchNo());
            }
            int upd = vmiStockDao.update(null, new LambdaUpdateWrapper<VmiStock>()
                    .eq(VmiStock::getId, s.getId())
                    .apply("QTY >= {0}", l.getQty())
                    .setSql("QTY = QTY - " + l.getQty().toPlainString())
                    .setSql("ISSUED_QTY = ISSUED_QTY + " + l.getQty().toPlainString()));
            if (upd == 0) {
                throw new ServiceException(422, "寄售库存不足（并发领用），请刷新后重试："
                        + l.getItemCode() + " 批次 " + l.getBatchNo());
            }
            // 3) 加自有库存（按协议价计入存货，BR-4.2-01 成本确认时点）
            addOwnStock(l.getItemCode(), l.getItemName(), l.getBatchNo(), l.getQty(),
                    transferNo);
            // 4) 逐行生成 VMI_TRANSFER 暂估（借存货/贷应付暂估，同一转自有凭证号）
            accrualService.createFromVmiTransfer(FinAccrual.SRC_VMI, transferNo,
                    h.getSupplierId(), supplierNameOf(h.getSupplierId()), null,
                    l.getQty(), l.getUnitPrice());
        }
        h.setTransferDocNo(transferNo);
    }

    private void addOwnStock(String itemCode, String itemName, String batchNo, BigDecimal qty,
                             String docNo) {
        // 物权转移入自有库存经通用引擎（material-issue MODIFIED ②：VMI_TRANSFER_IN 流水）
        com.erp.service.inv.StockPostingEngine.Line el =
                new com.erp.service.inv.StockPostingEngine.Line();
        el.warehouseCode = InvStock.DEFAULT_WH;
        el.itemCode = itemCode;
        el.itemName = itemName;
        el.batchNo = batchNo;
        el.qty = qty;
        stockPostingEngine.post(com.erp.service.inv.StockPostingEngine.Request.of(
                "VMI_TRANSFER_IN", "MATERIAL_ISSUE", docNo, List.of(el)));
    }

    // ---------- 作废 ----------

    @Override
    @Transactional
    public void cancel(String id, String reason) {
        requireRole("作废领料单", "ROLE_WAREHOUSE");
        MaterialIssue h = require(id);
        if (!MaterialIssue.ST_DRAFT.equals(h.getStatus())) {
            throw new ServiceException(422, "仅草稿领料单可作废，当前 " + h.getStatus());
        }
        if (!hasText(reason) || reason.trim().length() < 2) {
            throw new ServiceException(422, "作废原因必填（不少于 2 字）");
        }
        h.setStatus(MaterialIssue.ST_CANCELLED);
        h.setCancelReason(reason.trim());
        h.setUpdateBy(SecurityUtils.getCurrentUserId());
        issueDao.updateById(h);
        log.info("领料单 {} cancelled: {}", h.getIssueNo(), reason);
    }

    // ---------- 私有：FIFO 配批与校验 ----------

    /** 请求行：[{itemCode, qty}]（VMI 可带 supplierId 于头） */
    private List<Map<String, Object>> requested(Map<String, Object> payload) {
        Object raw = payload.get("lines");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new ServiceException(422, "领料明细行必填");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                throw new ServiceException(422, "明细行格式非法");
            }
            String item = m.get("itemCode") == null ? null : String.valueOf(m.get("itemCode"));
            if (!hasText(item)) {
                throw new ServiceException(422, "物料编码必填");
            }
            BigDecimal qty;
            try {
                qty = new BigDecimal(String.valueOf(m.get("qty")).trim());
            } catch (RuntimeException e) {
                throw new ServiceException(422, item + " 数量格式非法");
            }
            if (qty.signum() <= 0) {
                throw new ServiceException(422, item + " 数量须大于 0");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("itemCode", item.trim());
            row.put("itemName", m.get("itemName"));
            row.put("qty", qty);
            // 行级指定批次可选（material-issue MODIFIED）：透传 batchNo 覆盖配批结果
            row.put("batchNo", m.get("batchNo"));
            // 改批偏离原因（outbound-strategy：改写推荐值时必填，空则 422）
            row.put("deviationReason", m.get("deviationReason"));
            out.add(row);
        }
        return out;
    }

    /** FIFO 配批：逐需求行按最早批次拆分；任一需求不足 422（spec material-issue 创建 R1） */
    private List<MaterialIssueLine> fifoPlan(MaterialIssue head, List<Map<String, Object>> req) {
        boolean vmi = MaterialIssue.TYPE_VMI.equals(head.getIssueType());
        List<MaterialIssueLine> plan = new ArrayList<>();
        for (Map<String, Object> r : req) {
            String item = String.valueOf(r.get("itemCode"));
            BigDecimal need = (BigDecimal) r.get("qty");
            String reqName = r.get("itemName") == null ? null : String.valueOf(r.get("itemName"));
            String overrideBatch = r.get("batchNo") == null ? null
                    : String.valueOf(r.get("batchNo")).trim();
            int no = plan.size() + 1;

            if (!vmi && overrideBatch != null && !overrideBatch.isEmpty()) {
                // 行级指定批次可选（material-issue MODIFIED 创建）：按指定批次预检（提示性，
                // 按 AVAILABLE 口径；位行粒度下同批次跨多仓位 → 批次合计，selectOne 会撞 TooManyResults）
                List<InvStock> batchRows = stockDao.selectList(new LambdaQueryWrapper<InvStock>()
                        .eq(InvStock::getWarehouseCode, InvStock.DEFAULT_WH)
                        .eq(InvStock::getItemCode, item)
                        .eq(InvStock::getBatchNo, overrideBatch));
                BigDecimal basis = BigDecimal.ZERO;
                String batchItemName = null;
                for (InvStock br : batchRows) {
                    basis = basis.add(nvl(br.getAvailableQty()));
                    if (batchItemName == null) {
                        batchItemName = br.getItemName();
                    }
                }
                if (basis.compareTo(need) < 0) {
                    throw new ServiceException(422, "自有可用量不足："
                            + item + " 需要 " + need.stripTrailingZeros().toPlainString()
                            + "，可配 " + basis.stripTrailingZeros().toPlainString()
                            + "，缺口 " + need.subtract(basis).stripTrailingZeros().toPlainString());
                }
                // 改批偏离留痕（spec outbound-strategy，C-4.4-08 非波次首期仅留痕）：
                // 推荐值 = 缺省 FIFO 分配的首批次（只读试算，异常时视为无推荐值）；
                // create（issueNo 已生成）落台账，preview 不落账；原因必填由 recordDeviation 强制
                if (head.getIssueNo() != null) {
                    String recommended = null;
                    try {
                        List<com.erp.service.inv.StockPostingEngine.Alloc> rec =
                                stockPostingEngine.allocate(InvStock.DEFAULT_WH, item, need);
                        if (!rec.isEmpty()) {
                            recommended = rec.get(0).batchNo;
                        }
                    } catch (RuntimeException ignore) {
                        // 推荐失败（如缺省池不足）→ 无推荐值，仍按实际指定批次留痕
                    }
                    String reason = r.get("deviationReason") == null ? null
                            : String.valueOf(r.get("deviationReason"));
                    fifoStrategyService.recordDeviation("MATERIAL_ISSUE", head.getIssueNo(),
                            no, item, InvStock.DEFAULT_WH, recommended, overrideBatch, reason);
                }
                MaterialIssueLine ol = new MaterialIssueLine();
                ol.setLineNo(no);
                ol.setItemCode(item);
                ol.setItemName(reqName == null ? batchItemName : reqName);
                ol.setBatchNo(overrideBatch);
                ol.setQty(need);
                ol.setStockType(head.getIssueType());
                ol.setSupplierId(null);
                ol.setCreateBy(SecurityUtils.getCurrentUserId());
                plan.add(ol);
                continue;
            }

            if (vmi) {
                // 寄售配批保持域内 FIFO（INBOUND_DATE 升序，vmi 表不入引擎）
                BigDecimal remain = need;
                for (StockRow row : vmiRows(head.getSupplierId(), item)) {
                    if (remain.signum() <= 0) {
                        break;
                    }
                    BigDecimal take = row.available.min(remain);
                    if (take.signum() <= 0) {
                        continue;
                    }
                    MaterialIssueLine l = new MaterialIssueLine();
                    l.setLineNo(no++);
                    l.setItemCode(item);
                    l.setItemName(reqName == null ? row.itemName : reqName);
                    l.setBatchNo(row.batchNo);
                    l.setQty(take);
                    l.setStockType(head.getIssueType());
                    l.setSupplierId(head.getSupplierId());
                    l.setCreateBy(SecurityUtils.getCurrentUserId());
                    plan.add(l);
                    remain = remain.subtract(take);
                }
                if (remain.signum() > 0) {
                    BigDecimal avail = need.subtract(remain);
                    throw new ServiceException(422, "寄售可用量不足："
                            + item + " 需要 " + need.stripTrailingZeros().toPlainString()
                            + "，可配 " + avail.stripTrailingZeros().toPlainString()
                            + "，缺口 " + remain.stripTrailingZeros().toPlainString());
                }
            } else {
                // 自有配批经引擎分配（FIFO+效期统一分配器，material-issue MODIFIED 创建；
                // 缺口 422 文案「可用量不足…缺口」由引擎给出）
                for (com.erp.service.inv.StockPostingEngine.Alloc a
                        : stockPostingEngine.allocate(InvStock.DEFAULT_WH, item, need)) {
                    MaterialIssueLine l = new MaterialIssueLine();
                    l.setLineNo(no++);
                    l.setItemCode(item);
                    l.setItemName(reqName == null ? a.itemName : reqName);
                    l.setBatchNo(a.batchNo);
                    l.setQty(a.qty);
                    l.setStockType(head.getIssueType());
                    l.setSupplierId(null);
                    l.setCreateBy(SecurityUtils.getCurrentUserId());
                    plan.add(l);
                }
            }
        }
        return plan;
    }

    private record StockRow(String batchNo, String itemName, BigDecimal available) {
    }

    private List<StockRow> ownRows(String itemCode) {
        List<StockRow> out = new ArrayList<>();
        for (InvStock s : stockDao.selectList(new LambdaQueryWrapper<InvStock>()
                .eq(InvStock::getWarehouseCode, InvStock.DEFAULT_WH)
                .eq(InvStock::getItemCode, itemCode)
                .gt(InvStock::getAvailableQty, BigDecimal.ZERO)
                .orderByAsc(InvStock::getCreateDate))) {   // FIFO：批次首入（行创建时间）近似
            out.add(new StockRow(s.getBatchNo(), s.getItemName(), nvl(s.getAvailableQty())));
        }
        return out;
    }

    private List<StockRow> vmiRows(String supplierId, String itemCode) {
        if (!hasText(supplierId)) {
            throw new ServiceException(422, "寄售领用须指定供应商（supplierId）");
        }
        List<StockRow> out = new ArrayList<>();
        for (VmiStock s : vmiStockDao.selectList(new LambdaQueryWrapper<VmiStock>()
                .eq(VmiStock::getSupplierId, supplierId)
                .eq(VmiStock::getItemCode, itemCode)
                .gt(VmiStock::getQty, BigDecimal.ZERO)
                .orderByAsc(VmiStock::getInboundDate)
                .orderByAsc(VmiStock::getCreateDate))) {
            out.add(new StockRow(s.getBatchNo(), s.getItemName(), nvl(s.getQty())));
        }
        return out;
    }

    private MaterialIssue fromPayload(Map<String, Object> payload, MaterialIssue h) {
        String type = payload.get("issueType") == null ? null
                : String.valueOf(payload.get("issueType")).trim();
        if (!MaterialIssue.TYPE_OWN.equals(type) && !MaterialIssue.TYPE_VMI.equals(type)) {
            throw new ServiceException(422, "issueType 须为 OWN 或 VMI");
        }
        h.setIssueType(type);
        String wo = payload.get("workOrderNo") == null ? null
                : String.valueOf(payload.get("workOrderNo")).trim();
        if (!hasText(wo)) {
            throw new ServiceException(422, "工单号必填（BR-4.2-37 逐笔记录）");
        }
        h.setWorkOrderNo(wo);
        h.setDept(payload.get("dept") == null ? null : String.valueOf(payload.get("dept")));
        h.setPurpose(payload.get("purpose") == null ? null : String.valueOf(payload.get("purpose")));
        String supplierId = payload.get("supplierId") == null ? null
                : String.valueOf(payload.get("supplierId")).trim();
        if (MaterialIssue.TYPE_VMI.equals(type)) {
            if (!hasText(supplierId)) {
                throw new ServiceException(422, "寄售领用须指定供应商（supplierId）");
            }
            h.setSupplierId(supplierId);
            h.setSupplierName(supplierNameOf(supplierId));
        }
        return h;
    }

    private String supplierNameOf(String supplierId) {
        var s = supplierDao.selectById(supplierId);
        return s == null ? supplierId : s.getSupplierName();
    }

    private MaterialIssue require(String id) {
        MaterialIssue h = issueDao.selectById(id);
        if (h == null) {
            throw new ServiceException(404, "领料单不存在：" + id);
        }
        return h;
    }

    private String nextIssueNo() {
        String prefix = "MI" + LocalDateTime.now().format(MONTH) + "-";
        Integer max = issueDao.selectMaxSeq(prefix);
        return prefix + String.format("%06d", (max == null ? 0 : max) + 1);
    }

    private String nextTransferNo() {
        String prefix = "VT" + LocalDateTime.now().format(MONTH) + "-";
        Integer max = issueDao.selectMaxTransferSeq(prefix);
        return prefix + String.format("%06d", (max == null ? 0 : max) + 1);
    }

    /** 服务层二次校验（design D11：领料写操作限 ADMIN/WAREHOUSE，SecurityConfig 之外的兜底） */
    private void requireRole(String action, String... allowed) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        List<String> userRoles = new ArrayList<>();
        auth.getAuthorities().forEach(a -> userRoles.add(a.getAuthority()));
        if (userRoles.stream().anyMatch(r -> "ROLE_ADMIN".equalsIgnoreCase(r))) {
            return;
        }
        for (String want : allowed) {
            for (String r : userRoles) {
                if (r.equalsIgnoreCase(want)) {
                    return;
                }
            }
        }
        throw new ServiceException(403, "当前角色无权" + action);
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
