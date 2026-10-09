package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvStockDao;
import com.erp.dao.inv.ScrapOrderDao;
import com.erp.dao.inv.ScrapOrderLineDao;
import com.erp.dao.qms.NcrDao;
import com.erp.entity.inv.InvScrapOrder;
import com.erp.entity.inv.InvScrapOrderLine;
import com.erp.entity.inv.InvStock;
import com.erp.entity.qms.Ncr;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.fin.GlVoucherService;
import com.erp.service.inv.ScrapOrderService;
import com.erp.service.inv.StockPostingEngine;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;

/**
 * 报废出库单实现（spec scrap-order，design D5/D6）。
 * 门槛分流：STALE=三方会签（C-4.4-14 过账点 L1）、QUALITY=NCR 处置=SCRAP 校验（方案 a）、
 * DAMAGE/OTHER=直批免会签（偏差 D1）。两步凭证锚定报废单号（sourceType=SCRAP）。
 * 过账要求行单位成本合计 >0（gl-voucher 借贷平衡不收零额——创建期可空、过账前必补）。
 */
@Slf4j
@Service
public class ScrapOrderServiceImpl implements ScrapOrderService {

    private static final String BIZ_SCRAP = "Scrap";
    private static final String VT_SCRAP = "SCR";
    private static final String SOURCE_SCRAP = "SCRAP";

    private final ScrapOrderDao scrapDao;
    private final ScrapOrderLineDao lineDao;
    private final InvStockDao stockDao;
    private final NcrDao ncrDao;
    private final ApprovalEngine approvalEngine;
    private final StockPostingEngine engine;
    private final GlVoucherService voucherService;

    private final com.erp.service.inv.PickTaskGate pickTaskGate;
    private final com.erp.service.inv.PickTaskService pickTaskService;
    /** 召回处置回调（spec trace-recall：带 TR 关联的报废过账后置流向行 DISPOSED） */
    private final com.erp.service.inv.RecallService recallService;

    public ScrapOrderServiceImpl(ScrapOrderDao scrapDao,
                                 ScrapOrderLineDao lineDao,
                                 InvStockDao stockDao,
                                 NcrDao ncrDao,
                                 ApprovalEngine approvalEngine,
                                 StockPostingEngine engine,
                                 GlVoucherService voucherService,
                                 com.erp.service.inv.PickTaskGate pickTaskGate,
                                 com.erp.service.inv.PickTaskService pickTaskService,
                                 com.erp.service.inv.RecallService recallService) {
        this.scrapDao = scrapDao;
        this.lineDao = lineDao;
        this.stockDao = stockDao;
        this.ncrDao = ncrDao;
        this.approvalEngine = approvalEngine;
        this.engine = engine;
        this.voucherService = voucherService;
        this.pickTaskGate = pickTaskGate;
        this.pickTaskService = pickTaskService;
        this.recallService = recallService;
    }

    // ---------- 创建 / 编辑 / 作废（4.1） ----------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> create(InvScrapOrder head, List<InvScrapOrderLine> lines) {
        requireWrite("创建报废单");
        return doCreate(head, lines);
    }

    @Override
    public Map<String, Object> createFromEval(InvScrapOrder head, List<InvScrapOrderLine> lines) {
        // 效期评估链内部通道：质量角色判定触发，跳过 WAREHOUSE 校验（design D4）
        return doCreate(head, lines);
    }

    private Map<String, Object> doCreate(InvScrapOrder head, List<InvScrapOrderLine> lines) {
        InvScrapOrder o = prepare(head, lines);
        o.setScrapNo(nextNo());
        o.setStatus(InvScrapOrder.ST_DRAFT);
        o.setCreateBy(SecurityUtils.getCurrentUserId());
        scrapDao.insert(o);
        insertLines(o.getId(), lines);
        return detail(o.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> update(String id, InvScrapOrder head,
                                      List<InvScrapOrderLine> lines) {
        requireWrite("编辑报废单");
        InvScrapOrder o = lock(id);
        if (!InvScrapOrder.ST_DRAFT.equals(o.getStatus())) {
            throw new ServiceException(422, "仅草稿状态可编辑（当前 " + o.getStatus() + "）");
        }
        InvScrapOrder prep = prepare(head, lines);
        InvScrapOrder upd = new InvScrapOrder();
        upd.setId(id);
        upd.setVerNo(o.getVerNo());
        upd.setReason(prep.getReason());
        upd.setNcrNo(prep.getNcrNo());
        upd.setTraceNo(prep.getTraceNo());
        upd.setWarehouseCode(prep.getWarehouseCode());
        upd.setTotalQty(prep.getTotalQty());
        upd.setTotalAmount(prep.getTotalAmount());
        upd.setRemark(prep.getRemark());
        scrapDao.updateById(upd);
        lineDao.delete(new LambdaQueryWrapper<InvScrapOrderLine>()
                .eq(InvScrapOrderLine::getOrderId, id));
        insertLines(id, lines);
        return detail(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(String id, String reason) {
        requireWrite("作废报废单");
        if (reason == null || reason.isBlank()) {
            throw new ServiceException(422, "作废原因必填");
        }
        InvScrapOrder o = lock(id);
        if (!InvScrapOrder.ST_DRAFT.equals(o.getStatus())) {
            throw new ServiceException(422, "仅草稿状态可作废");
        }
        InvScrapOrder upd = new InvScrapOrder();
        upd.setId(id);
        upd.setVerNo(o.getVerNo());
        upd.setStatus(InvScrapOrder.ST_CANCELLED);
        upd.setCancelReason(reason);
        scrapDao.updateById(upd);
    }

    /** 校验 + 合计 + 库龄固化（create/update 共用） */
    private InvScrapOrder prepare(InvScrapOrder head, List<InvScrapOrderLine> lines) {
        if (head == null || isBlank(head.getWarehouseCode())) {
            throw new ServiceException(422, "报废仓库必填");
        }
        String reason = head.getReason() == null ? "" : head.getReason().trim().toUpperCase();
        if (!List.of(InvScrapOrder.R_STALE, InvScrapOrder.R_QUALITY,
                InvScrapOrder.R_DAMAGE, InvScrapOrder.R_OTHER).contains(reason)) {
            throw new ServiceException(422, "报废原因取值域：呆滞 STALE / 质量 QUALITY / 损坏 DAMAGE / 其他 OTHER");
        }
        if (InvScrapOrder.R_QUALITY.equals(reason)) {
            if (isBlank(head.getNcrNo())) {
                throw new ServiceException(422, "质量原因必填 NCR 关联号");
            }
            Ncr ncr = findNcr(head.getNcrNo().trim());
            if (ncr == null) {
                throw new ServiceException(422, "NCR 不存在：" + head.getNcrNo());
            }
        }
        if (lines == null || lines.isEmpty()) {
            throw new ServiceException(422, "报废行不能为空");
        }
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        int lineNo = 1;
        for (InvScrapOrderLine l : lines) {
            if (isBlank(l.getItemCode())) {
                throw new ServiceException(422, "行物料编码必填");
            }
            if (isBlank(l.getBatchNo())) {
                throw new ServiceException(422, "报废须指定批次：" + l.getItemCode());
            }
            if (l.getQty() == null || l.getQty().signum() <= 0) {
                throw new ServiceException(422, "行数量必须大于 0：" + l.getItemCode());
            }
            BigDecimal onHand = batchOnHand(head.getWarehouseCode(), l.getItemCode(),
                    l.getBatchNo().trim());
            if (onHand.signum() <= 0) {
                throw new ServiceException(422, "批次无库存：" + l.getItemCode()
                        + " / " + l.getBatchNo());
            }
            if (l.getQty().compareTo(onHand) > 0) {
                throw new ServiceException(422, "行数量超在手量：可报废 "
                        + strip(onHand) + "，本次 " + strip(l.getQty()) + "（"
                        + l.getItemCode() + " 批次 " + l.getBatchNo() + "）");
            }
            l.setLineNo(lineNo++);
            l.setBatchNo(l.getBatchNo().trim());
            // 库龄快照：该仓该批次位行 MIN(INBOUND_DATE) 距今天数（创建时固化，spec scrap-order）
            l.setStockAgeDays(batchAgeDays(head.getWarehouseCode(), l.getItemCode(),
                    l.getBatchNo()));
            l.setLineAmount(l.getUnitCost() == null ? BigDecimal.ZERO
                    : l.getQty().multiply(l.getUnitCost()).setScale(2,
                            java.math.RoundingMode.HALF_UP));
            totalQty = totalQty.add(l.getQty());
            totalAmount = totalAmount.add(l.getLineAmount());
        }
        InvScrapOrder o = new InvScrapOrder();
        o.setReason(reason);
        o.setNcrNo(isBlank(head.getNcrNo()) ? null : head.getNcrNo().trim());
        o.setTraceNo(isBlank(head.getTraceNo()) ? null : head.getTraceNo().trim());
        o.setWarehouseCode(head.getWarehouseCode());
        o.setTotalQty(totalQty);
        o.setTotalAmount(totalAmount);
        o.setRemark(head.getRemark());
        return o;
    }

    private void insertLines(String orderId, List<InvScrapOrderLine> lines) {
        for (InvScrapOrderLine l : lines) {
            l.setId(null);
            l.setOrderId(orderId);
            l.setCreateBy(SecurityUtils.getCurrentUserId());
            lineDao.insert(l);
        }
    }

    // ---------- 4.2 会签与批准 ----------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> submitForApproval(String id) {
        requireWrite("提交报废会签");
        InvScrapOrder o = lock(id);
        if (!InvScrapOrder.R_STALE.equals(o.getReason())) {
            throw new ServiceException(422, "仅呆滞（STALE）报废须三方会签，当前原因 " + o.getReason());
        }
        if (!InvScrapOrder.ST_DRAFT.equals(o.getStatus())) {
            throw new ServiceException(422, "仅草稿状态可提交会签（当前 " + o.getStatus() + "）");
        }
        if (!isBlank(o.getApprId())) {
            throw new ServiceException(422, "该单已在会签中，不可重复提交");
        }
        // 一个 SEQ 三个 JOINT 节点 = 三方会签（approval-workflow 原生，design D5）
        var inst = approvalEngine.submit(BIZ_SCRAP, id,
                "呆滞报废三方会签：" + o.getScrapNo() + " / " + strip(o.getTotalQty()) + " 件",
                "ROLE_GM",
                List.of(List.of(
                        ApprovalNodeSpec.joint("ROLE_TECH_OWNER", "技术会签"),
                        ApprovalNodeSpec.joint("ROLE_QUALITY_MGR", "质量会签"),
                        ApprovalNodeSpec.joint("ROLE_FINANCE_MGR", "财务会签"))));
        InvScrapOrder upd = new InvScrapOrder();
        upd.setId(id);
        upd.setVerNo(o.getVerNo());
        upd.setApprId(inst.getId());
        scrapDao.updateById(upd);
        log.info("scrap {} submitted for joint approval {}", o.getScrapNo(), inst.getId());
        return detail(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> approve(String id) {
        requireWrite("批准报废单");
        InvScrapOrder o = lock(id);
        if (InvScrapOrder.R_STALE.equals(o.getReason())) {
            throw new ServiceException(422, "呆滞报废须技术/质量/财务三方会签，不可直接批准（C-4.4-14）");
        }
        if (!InvScrapOrder.ST_DRAFT.equals(o.getStatus())) {
            throw new ServiceException(422, "仅草稿状态可批准（当前 " + o.getStatus() + "）");
        }
        // QUALITY/DAMAGE/OTHER 直批（免会签——质量域审批已在 NCR/无要求，偏差 D1）
        InvScrapOrder upd = new InvScrapOrder();
        upd.setId(id);
        upd.setVerNo(o.getVerNo());
        upd.setStatus(InvScrapOrder.ST_APPROVED);
        scrapDao.updateById(upd);
        return detail(id);
    }

    // ---------- 4.3 过账 / 4.4 处置核销 ----------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> post(String id) {
        requireWrite("报废出库过账");
        InvScrapOrder o = lock(id);
        if (!InvScrapOrder.ST_APPROVED.equals(o.getStatus())) {
            if (InvScrapOrder.ST_DRAFT.equals(o.getStatus())
                    && InvScrapOrder.R_STALE.equals(o.getReason())) {
                throw new ServiceException(422,
                        "三方会签未完成，阻断报废出库过账（C-4.4-14）：技术/质量/财务会签全部通过后方可过账");
            }
            throw new ServiceException(422, "仅已批准状态可过账（当前 " + o.getStatus() + "）");
        }
        // 拣货差异过账门闩（spec picking-review，BR-4.4-29）
        pickTaskGate.assertClear("SCRAP_OUT", o.getScrapNo());
        // 门槛分流（S1）
        if (InvScrapOrder.R_STALE.equals(o.getReason())) {
            requireJointApproved(o);
        } else if (InvScrapOrder.R_QUALITY.equals(o.getReason())) {
            requireNcrScrapDisposition(o);
        }
        // 两步凭证金额前提：行单位成本合计 >0（创建期可空、过账前必补）
        if (o.getTotalAmount() == null || o.getTotalAmount().signum() <= 0) {
            throw new ServiceException(422,
                    "报废单位成本缺失，无法生成报废凭证（借1901/贷1403），请补全行单位成本");
        }
        List<InvScrapOrderLine> lines = listLines(id);
        List<StockPostingEngine.Line> el = new ArrayList<>();
        Set<String> traceBatches = new java.util.LinkedHashSet<>();
        for (InvScrapOrderLine l : lines) {
            StockPostingEngine.Line ln = new StockPostingEngine.Line();
            ln.warehouseCode = o.getWarehouseCode();
            ln.itemCode = l.getItemCode();
            ln.itemName = l.getItemName();
            ln.batchNo = l.getBatchNo();
            // 拣货推荐回写仓位（4.6.3）：有值则引擎按指定仓位扣减
            ln.binCode = l.getBinCode();
            ln.qty = l.getQty();
            ln.serials = parseSerials(l.getSerials());
            // qcFirst（spec scrap-order MODIFIED / trace-recall 处置闭环）：
            // 批次 QC 冻结列有量则先核销 QC_QTY、不足再扣 AVAILABLE；合计不足由引擎 422 兜底
            if (batchQcQty(o.getWarehouseCode(), l.getItemCode(), l.getBatchNo()).signum() > 0) {
                ln.qcFirst = true;
            }
            if (!isBlank(o.getTraceNo())) {
                traceBatches.add(l.getBatchNo());
            }
            el.add(ln);
        }
        StockPostingEngine.Result res = engine.post(StockPostingEngine.Request.of(
                "SCRAP_OUT", SOURCE_SCRAP, o.getScrapNo(), el));
        // 凭证一：借 1901 待处理财产损溢 / 贷 1403 原材料
        voucherService.create(VT_SCRAP, LocalDate.now(),
                "报废出库 " + o.getScrapNo() + "（" + o.getReason() + "）",
                SOURCE_SCRAP, o.getScrapNo(), null,
                List.of(
                        GlVoucherService.FinVoucherLineSpec.of("1901", "DR", o.getTotalAmount(),
                                "报废待处理 " + o.getScrapNo()),
                        GlVoucherService.FinVoucherLineSpec.of("1403", "CR", o.getTotalAmount(),
                                "报废出库核减原材料")));
        transition(id, InvScrapOrder.ST_APPROVED, InvScrapOrder.ST_POSTED,
                new LambdaUpdateWrapper<InvScrapOrder>()
                        .set(InvScrapOrder::getPostBy, SecurityUtils.getCurrentUserId())
                        .set(InvScrapOrder::getPostAt, LocalDateTime.now()));
        // 过账成功联动：任务 DONE → COMPLETED（无任务跳过）
        pickTaskService.markCompleted("SCRAP_OUT", o.getScrapNo());
        // 召回处置回调（design D6）：带 TR 关联的报废把 FROZEN/RECEIVED 流向行置 DISPOSED
        if (!isBlank(o.getTraceNo())) {
            recallService.markDisposed(o.getTraceNo(), traceBatches);
        }
        log.info("scrap {} posted, txn={}, voucher 1901/1403={}", o.getScrapNo(),
                res.txnNos.size(), o.getTotalAmount());
        return detail(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> dispose(String id) {
        requireWrite("报废处置核销");
        InvScrapOrder o = lock(id);
        if (InvScrapOrder.ST_DISPOSED.equals(o.getStatus())) {
            throw new ServiceException(422, "该报废单已处置核销，不可重复操作");
        }
        if (!InvScrapOrder.ST_POSTED.equals(o.getStatus())) {
            throw new ServiceException(422, "须先完成出库过账方可处置核销（当前 " + o.getStatus() + "）");
        }
        // 凭证二：借 6711 营业外支出 / 贷 1901 待处理财产损溢（a1 两步法核销）
        voucherService.create(VT_SCRAP, LocalDate.now(),
                "报废处置核销 " + o.getScrapNo(),
                SOURCE_SCRAP, o.getScrapNo(), null,
                List.of(
                        GlVoucherService.FinVoucherLineSpec.of("6711", "DR", o.getTotalAmount(),
                                "报废损失 " + o.getScrapNo()),
                        GlVoucherService.FinVoucherLineSpec.of("1901", "CR", o.getTotalAmount(),
                                "待处理财产损溢核销")));
        transition(id, InvScrapOrder.ST_POSTED, InvScrapOrder.ST_DISPOSED,
                new LambdaUpdateWrapper<InvScrapOrder>()
                        .set(InvScrapOrder::getDisposeBy, SecurityUtils.getCurrentUserId())
                        .set(InvScrapOrder::getDisposeAt, LocalDateTime.now()));
        return detail(id);
    }

    // ---------- 查询 ----------

    @Override
    public Map<String, Object> detail(String id) {
        InvScrapOrder o = scrapDao.selectById(id);
        if (o == null) {
            throw new ServiceException(404, "报废单不存在");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("order", o);
        out.put("lines", listLines(id));
        return out;
    }

    @Override
    public Map<String, Object> page(long current, long size, String status, String keyword) {
        Page<InvScrapOrder> p = scrapDao.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<InvScrapOrder>()
                        .eq(!isBlank(status), InvScrapOrder::getStatus, status)
                        .and(!isBlank(keyword), w -> w
                                .like(InvScrapOrder::getScrapNo, keyword)
                                .or().like(InvScrapOrder::getNcrNo, keyword)
                                .or().like(InvScrapOrder::getWarehouseCode, keyword)
                                .or().like(InvScrapOrder::getReason, keyword))
                        .orderByDesc(InvScrapOrder::getCreateDate));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", p.getRecords());
        out.put("total", p.getTotal());
        return out;
    }

    // ---------- helpers ----------

    /** STALE：会签实例必须 APPROVED（防御——状态 APPROVED 由回调写入） */
    private void requireJointApproved(InvScrapOrder o) {
        if (isBlank(o.getApprId())) {
            throw new ServiceException(422,
                    "三方会签未完成，阻断报废出库过账（C-4.4-14）：尚未提交会签");
        }
        var inst = approvalEngine.getInstance(o.getApprId());
        if (inst == null || !"APPROVED".equals(inst.getStatus())) {
            String progress = inst == null ? "实例缺失" : inst.getStatus();
            throw new ServiceException(422,
                    "三方会签未完成，阻断报废出库过账（C-4.4-14）：会签状态 " + progress);
        }
    }

    /** QUALITY：NCR 存在且处置=SCRAP（方案 a 正向条件），否则阻断 */
    private void requireNcrScrapDisposition(InvScrapOrder o) {
        Ncr ncr = findNcr(o.getNcrNo());
        if (ncr == null) {
            throw new ServiceException(422, "NCR 不存在：" + o.getNcrNo());
        }
        if (!"SCRAP".equals(ncr.getDisposition())) {
            throw new ServiceException(422, "NCR 处置非报废，阻断报废出库过账："
                    + o.getNcrNo() + " 处置=" + ncr.getDisposition());
        }
    }

    private List<InvScrapOrderLine> listLines(String orderId) {
        return lineDao.selectList(new LambdaQueryWrapper<InvScrapOrderLine>()
                .eq(InvScrapOrderLine::getOrderId, orderId)
                .orderByAsc(InvScrapOrderLine::getLineNo));
    }

    private InvScrapOrder lock(String id) {
        InvScrapOrder o = scrapDao.selectByIdForUpdate(id);
        if (o == null) {
            throw new ServiceException(404, "报废单不存在");
        }
        return o;
    }

    private void transition(String id, String from, String to,
                            LambdaUpdateWrapper<InvScrapOrder> extra) {
        LambdaUpdateWrapper<InvScrapOrder> uw = extra
                .eq(InvScrapOrder::getId, id)
                .eq(InvScrapOrder::getStatus, from)
                .set(InvScrapOrder::getStatus, to)
                .setSql("VER_NO = VER_NO + 1");
        if (scrapDao.update(null, uw) == 0) {
            throw new ServiceException(409, "报废单状态并发冲突，请重试");
        }
    }

    /** 序列清单拆分（逗号/分号/顿号/空白分隔；空 → null 交引擎按 serialFlag 判定） */
    private List<String> parseSerials(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String s : raw.split("[,，;；、\\s]+")) {
            if (!s.isBlank()) {
                out.add(s.trim());
            }
        }
        return out.isEmpty() ? null : out;
    }

    private Ncr findNcr(String ncrNo) {
        if (isBlank(ncrNo)) {
            return null;
        }
        return ncrDao.selectOne(new LambdaQueryWrapper<Ncr>()
                .eq(Ncr::getNcrNo, ncrNo.trim())
                .last("LIMIT 1"));
    }

    private BigDecimal batchOnHand(String wh, String item, String batch) {
        BigDecimal total = BigDecimal.ZERO;
        for (InvStock r : stockDao.selectList(new LambdaQueryWrapper<InvStock>()
                .eq(InvStock::getWarehouseCode, wh)
                .eq(InvStock::getItemCode, item)
                .eq(InvStock::getBatchNo, batch))) {
            total = total.add(r.getQty() == null ? BigDecimal.ZERO : r.getQty());
        }
        return total;
    }

    private int batchAgeDays(String wh, String item, String batch) {
        LocalDate min = stockDao.selectList(new LambdaQueryWrapper<InvStock>()
                        .eq(InvStock::getWarehouseCode, wh)
                        .eq(InvStock::getItemCode, item)
                        .eq(InvStock::getBatchNo, batch)
                        .orderByAsc(InvStock::getInboundDate))
                .stream()
                .map(InvStock::getInboundDate)
                .filter(d -> d != null)
                .min(LocalDate::compareTo)
                .orElse(LocalDate.now());
        return (int) ChronoUnit.DAYS.between(min, LocalDate.now());
    }

    private String nextNo() {
        String prefix = "SC" + LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM")) + "-";
        int max = 0;
        for (String no : scrapDao.selectNosByPrefix(prefix + "%")) {
            try {
                max = Math.max(max, Integer.parseInt(no.substring(prefix.length())));
            } catch (Exception ignore) {
                // 非规范编号跳过
            }
        }
        return prefix + String.format("%04d", max + 1);
    }

    /** 批次 QC 冻结列余量（qcFirst 判定用；维度不存在按 0） */
    private BigDecimal batchQcQty(String warehouseCode, String itemCode, String batchNo) {
        InvStock s = stockDao.selectOne(new LambdaQueryWrapper<InvStock>()
                .eq(InvStock::getWarehouseCode, warehouseCode)
                .eq(InvStock::getItemCode, itemCode)
                .eq(InvStock::getBatchNo, batchNo)
                .last("LIMIT 1"));
        return s == null || s.getQcQty() == null ? BigDecimal.ZERO : s.getQcQty();
    }

    private void requireWrite(String action) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        List<String> roles = new ArrayList<>();
        auth.getAuthorities().forEach(a -> roles.add(a.getAuthority()));
        if (roles.stream().anyMatch(r -> "ROLE_ADMIN".equalsIgnoreCase(r))) {
            return;
        }
        for (String r : roles) {
            if ("ROLE_WAREHOUSE".equalsIgnoreCase(r)) {
                return;
            }
        }
        throw new ServiceException(403, "无权" + action + "（需 WAREHOUSE）");
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String strip(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }
}
