package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvWarehouseDao;
import com.erp.dao.inv.TransferOrderDao;
import com.erp.dao.inv.TransferOrderLineDao;
import com.erp.entity.inv.InvTransferOrder;
import com.erp.entity.inv.InvTransferOrderLine;
import com.erp.entity.inv.InvWarehouse;
import com.erp.service.SysParamService;
import com.erp.service.inv.InternalTransferAccountingService;
import com.erp.service.inv.StockPostingEngine;
import com.erp.service.inv.TransferOrderService;
import com.erp.service.system.NoticeService;
import com.erp.util.SecurityUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;

/**
 * 调拨单（spec transfer-order，design D3/D4）。
 * 三入口（4.12.1/4.5.3/4.4.4）共享同一后端动作；过账经 stock-posting-engine 位级执行；
 * 跨法人核算（凭证/内部发票/核销校验）由 InternalTransferAccountingService 在同事务接入（task 3.3）。
 */
@Slf4j
@Service
public class TransferOrderServiceImpl implements TransferOrderService {

    private final TransferOrderDao orderDao;
    private final TransferOrderLineDao lineDao;
    private final InvWarehouseDao warehouseDao;
    private final StockPostingEngine engine;
    private final ObjectMapper jsonMapper;
    private final InternalTransferAccountingService accounting;
    private final NoticeService noticeService;
    private final SysParamService sysParamService;

    public TransferOrderServiceImpl(TransferOrderDao orderDao,
                                    TransferOrderLineDao lineDao,
                                    InvWarehouseDao warehouseDao,
                                    StockPostingEngine engine,
                                    ObjectMapper jsonMapper,
                                    InternalTransferAccountingService accounting,
                                    NoticeService noticeService,
                                    SysParamService sysParamService) {
        this.orderDao = orderDao;
        this.lineDao = lineDao;
        this.warehouseDao = warehouseDao;
        this.engine = engine;
        this.jsonMapper = jsonMapper;
        this.accounting = accounting;
        this.noticeService = noticeService;
        this.sysParamService = sysParamService;
    }

    // ---------- 创建 / 编辑 / 作废 ----------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> create(InvTransferOrder head, List<InvTransferOrderLine> lines) {
        requireWrite("创建调拨单");
        InvTransferOrder o = prepare(head, lines);
        o.setTransferNo(nextNo());
        o.setStatus(InvTransferOrder.ST_DRAFT);
        o.setSuspendedFlag("0");
        o.setCreateBy(SecurityUtils.getCurrentUserId());
        orderDao.insert(o);
        insertLines(o.getId(), lines);
        return detail(o.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> update(String id, InvTransferOrder head,
                                      List<InvTransferOrderLine> lines) {
        requireWrite("编辑调拨单");
        InvTransferOrder o = lock(id);
        if (!InvTransferOrder.ST_DRAFT.equals(o.getStatus())) {
            throw new ServiceException(422, "仅草稿状态可编辑（当前 " + o.getStatus() + "）");
        }
        InvTransferOrder prep = prepare(head, lines);
        InvTransferOrder upd = new InvTransferOrder();
        upd.setId(id);
        upd.setVerNo(o.getVerNo());   // @Version 条件取锁行当前值（否则静默 no-op）
        upd.setOutWhCode(prep.getOutWhCode());
        upd.setInWhCode(prep.getInWhCode());
        upd.setOutLeCode(prep.getOutLeCode());
        upd.setInLeCode(prep.getInLeCode());
        upd.setCrossLe(prep.getCrossLe());
        upd.setTotalQty(prep.getTotalQty());
        upd.setTotalAmount(prep.getTotalAmount());
        upd.setRemark(prep.getRemark());
        orderDao.updateById(upd);
        lineDao.delete(new LambdaQueryWrapper<InvTransferOrderLine>()
                .eq(InvTransferOrderLine::getOrderId, id));
        insertLines(id, lines);
        return detail(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(String id, String reason) {
        requireWrite("作废调拨单");
        if (reason == null || reason.isBlank()) {
            throw new ServiceException(422, "作废原因必填");
        }
        InvTransferOrder o = lock(id);
        if (!InvTransferOrder.ST_DRAFT.equals(o.getStatus())) {
            throw new ServiceException(422, "仅草稿状态可作废（出库过账后须走冲销流程）");
        }
        InvTransferOrder upd = new InvTransferOrder();
        upd.setId(id);
        upd.setVerNo(o.getVerNo());
        upd.setStatus(InvTransferOrder.ST_CANCELLED);
        upd.setCancelReason(reason);
        orderDao.updateById(upd);
    }

    /** 校验 + LE 解析 + 合计（create/update 共用） */
    private InvTransferOrder prepare(InvTransferOrder head, List<InvTransferOrderLine> lines) {
        if (head == null || isBlank(head.getOutWhCode()) || isBlank(head.getInWhCode())) {
            throw new ServiceException(422, "调出仓与调入仓必填");
        }
        if (head.getOutWhCode().equals(head.getInWhCode())) {
            throw new ServiceException(422, "调出仓与调入仓不可相同");
        }
        if (lines == null || lines.isEmpty()) {
            throw new ServiceException(422, "调拨行不能为空");
        }
        InvWarehouse outWh = requireWh(head.getOutWhCode());
        InvWarehouse inWh = requireWh(head.getInWhCode());
        String outLe = isBlank(outWh.getLeCode()) ? "LE-0001" : outWh.getLeCode();
        String inLe = isBlank(inWh.getLeCode()) ? "LE-0001" : inWh.getLeCode();

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        int lineNo = 1;
        for (InvTransferOrderLine l : lines) {
            if (isBlank(l.getItemCode())) {
                throw new ServiceException(422, "行物料编码必填");
            }
            if (l.getQty() == null || l.getQty().signum() <= 0) {
                throw new ServiceException(422, "行数量必须大于 0：" + l.getItemCode());
            }
            if (l.getInternalPrice() == null || l.getInternalPrice().signum() <= 0) {
                throw new ServiceException(422, "行内部转移价必填且大于 0：" + l.getItemCode());
            }
            l.setLineNo(lineNo++);
            l.setBatchNo(l.getBatchNo() == null ? "" : l.getBatchNo().trim());
            l.setLineAmount(l.getQty().multiply(l.getInternalPrice())
                    .setScale(2, java.math.RoundingMode.HALF_UP));
            totalQty = totalQty.add(l.getQty());
            totalAmount = totalAmount.add(l.getLineAmount());
        }
        InvTransferOrder o = new InvTransferOrder();
        o.setOutWhCode(head.getOutWhCode());
        o.setInWhCode(head.getInWhCode());
        o.setOutLeCode(outLe);
        o.setInLeCode(inLe);
        o.setCrossLe(outLe.equals(inLe) ? "0" : "1");
        o.setTotalQty(totalQty);
        o.setTotalAmount(totalAmount);
        o.setRemark(head.getRemark());
        return o;
    }

    private void insertLines(String orderId, List<InvTransferOrderLine> lines) {
        for (InvTransferOrderLine l : lines) {
            l.setId(null);
            l.setOrderId(orderId);
            l.setCreateBy(SecurityUtils.getCurrentUserId());
            lineDao.insert(l);
        }
    }

    // ---------- 出库段 / 入库段过账 / 关闭 ----------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> postOut(String id) {
        requireWrite("调拨出库过账");
        InvTransferOrder o = lock(id);
        if (!InvTransferOrder.ST_DRAFT.equals(o.getStatus())) {
            throw new ServiceException(422, "仅草稿状态可执行出库过账（当前 " + o.getStatus() + "）");
        }
        // L1 税务资质校验前置（跨法人；须在引擎过账前阻断保证无残留，spec internal-transfer-accounting）
        accounting.checkTaxQualified(o);
        List<InvTransferOrderLine> lines = listLines(id);
        List<StockPostingEngine.Line> el = new ArrayList<>();
        for (InvTransferOrderLine l : lines) {
            StockPostingEngine.Line ln = new StockPostingEngine.Line();
            ln.warehouseCode = o.getOutWhCode();
            ln.itemCode = l.getItemCode();
            ln.itemName = l.getItemName();
            ln.batchNo = l.getBatchNo();
            ln.qty = l.getQty();
            el.add(ln);
        }
        StockPostingEngine.Result res = engine.post(StockPostingEngine.Request.of(
                "TRANSFER_OUT", "TRANSFER", o.getTransferNo(), el));
        // 分配结果回写头部（入库段按批入账依据；spec transfer-order 出库段）
        writeAlloc(o, lines, res.allocations);
        // 跨法人核算：内部销售凭证（借1461/贷1403）+ 内部销售发票（同事务，单法人幂等空操作）
        accounting.onOutPosted(o);
        transition(id, InvTransferOrder.ST_DRAFT, InvTransferOrder.ST_OUT_POSTED,
                new LambdaUpdateWrapper<InvTransferOrder>()
                        .set(InvTransferOrder::getOutPostAt, LocalDateTime.now())
                        .set(InvTransferOrder::getSuspendedFlag, "0"));
        log.info("transfer {} out-posted, txn={}, crossLe={}", o.getTransferNo(),
                res.txnNos.size(), o.getCrossLe());
        return detail(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> postIn(String id) {
        requireWrite("调拨入库过账");
        InvTransferOrder o = lock(id);
        if (!InvTransferOrder.ST_OUT_POSTED.equals(o.getStatus())) {
            if (InvTransferOrder.ST_DRAFT.equals(o.getStatus())) {
                throw new ServiceException(422, "须先完成出库过账");
            }
            throw new ServiceException(422, "当前状态不可入库过账（" + o.getStatus() + "）");
        }
        List<Map<String, Object>> allocs = readAlloc(o);
        List<StockPostingEngine.Line> el = new ArrayList<>();
        if (!allocs.isEmpty()) {
            for (Map<String, Object> a : allocs) {
                StockPostingEngine.Line ln = new StockPostingEngine.Line();
                ln.warehouseCode = o.getInWhCode();
                ln.itemCode = str(a.get("itemCode"));
                ln.batchNo = str(a.get("batchNo"));
                // 仓位不跨仓携带：调入仓重新上架（4.4.5 队列B 兜底），落 '' 未分配位
                ln.binCode = null;
                ln.qty = new BigDecimal(str(a.get("qty")));
                el.add(ln);
            }
        } else {
            // 兜底：无分配明细（不应出现）按行原批次入账
            for (InvTransferOrderLine l : listLines(id)) {
                StockPostingEngine.Line ln = new StockPostingEngine.Line();
                ln.warehouseCode = o.getInWhCode();
                ln.itemCode = l.getItemCode();
                ln.itemName = l.getItemName();
                ln.batchNo = l.getBatchNo();
                ln.qty = l.getQty();
                el.add(ln);
            }
        }
        StockPostingEngine.Result res = engine.post(StockPostingEngine.Request.of(
                "TRANSFER_IN", "TRANSFER", o.getTransferNo(), el));
        // 跨法人核算：内部采购凭证（借1403/贷1461 在途核销）+ 内部采购票 + 自动配对（同事务）
        accounting.onInPosted(o);
        transition(id, InvTransferOrder.ST_OUT_POSTED, InvTransferOrder.ST_IN_POSTED,
                new LambdaUpdateWrapper<InvTransferOrder>()
                        .set(InvTransferOrder::getInPostAt, LocalDateTime.now())
                        .set(InvTransferOrder::getSuspendedFlag, "0"));
        log.info("transfer {} in-posted, txn={}", o.getTransferNo(), res.txnNos.size());
        return detail(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> close(String id) {
        requireWrite("关闭调拨单");
        InvTransferOrder o = lock(id);
        if (!InvTransferOrder.ST_IN_POSTED.equals(o.getStatus())) {
            throw new ServiceException(422, "仅入库完成后可关闭（当前 " + o.getStatus() + "）");
        }
        // 跨法人核销校验：在途凭证成对 + 票对 SETTLED（否则 422 返回未核销清单）
        Map<String, Object> check = accounting.settlementCheck(o);
        if (!Boolean.TRUE.equals(check.get("settled"))) {
            @SuppressWarnings("unchecked")
            List<String> pending = (List<String>) check.get("pending");
            throw new ServiceException(422, "存在未核销项，阻断关闭："
                    + String.join("；", pending));
        }
        InvTransferOrder upd = new InvTransferOrder();
        upd.setId(id);
        upd.setVerNo(o.getVerNo());
        upd.setStatus(InvTransferOrder.ST_CLOSED);
        upd.setCloseBy(SecurityUtils.getCurrentUserId());
        upd.setCloseAt(LocalDateTime.now());
        orderDao.updateById(upd);
        return detail(id);
    }

    // ---------- 查询 ----------

    @Override
    public Map<String, Object> detail(String id) {
        InvTransferOrder o = orderDao.selectById(id);
        if (o == null) {
            throw new ServiceException(404, "调拨单不存在");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("order", o);
        out.put("lines", listLines(id));
        out.put("allocs", readAlloc(o));
        return out;
    }

    @Override
    public Map<String, Object> page(long current, long size, String status, String keyword) {
        LambdaQueryWrapper<InvTransferOrder> qw = new LambdaQueryWrapper<InvTransferOrder>()
                .eq(!isBlank(status), InvTransferOrder::getStatus, status)
                .and(!isBlank(keyword), w -> w
                        .like(InvTransferOrder::getTransferNo, keyword)
                        .or().like(InvTransferOrder::getOutWhCode, keyword)
                        .or().like(InvTransferOrder::getInWhCode, keyword))
                .orderByDesc(InvTransferOrder::getCreateDate);
        Page<InvTransferOrder> p = orderDao.selectPage(new Page<>(current, size), qw);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", p.getRecords());
        out.put("total", p.getTotal());
        return out;
    }

    @Override
    public Map<String, Object> intransitPage(long current, long size, String keyword) {
        Page<InvTransferOrder> p = orderDao.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<InvTransferOrder>()
                        .eq(InvTransferOrder::getStatus, InvTransferOrder.ST_OUT_POSTED)
                        .and(!isBlank(keyword), w -> w
                                .like(InvTransferOrder::getTransferNo, keyword)
                                .or().like(InvTransferOrder::getOutWhCode, keyword)
                                .or().like(InvTransferOrder::getInWhCode, keyword))
                        .orderByAsc(InvTransferOrder::getOutPostAt));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (InvTransferOrder o : p.getRecords()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", o.getId());
            m.put("transferNo", o.getTransferNo());
            m.put("outWhCode", o.getOutWhCode());
            m.put("inWhCode", o.getInWhCode());
            m.put("outLeCode", o.getOutLeCode());
            m.put("inLeCode", o.getInLeCode());
            m.put("crossLe", o.getCrossLe());
            m.put("totalQty", o.getTotalQty());
            m.put("totalAmount", o.getTotalAmount());
            m.put("outPostAt", o.getOutPostAt());
            m.put("inTransitDays", o.getOutPostAt() == null ? 0
                    : ChronoUnit.DAYS.between(o.getOutPostAt().toLocalDate(), LocalDate.now()));
            m.put("suspendedFlag", o.getSuspendedFlag());
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("total", p.getTotal());
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int sweepTransitOverdue() {
        int days = sysParamService.getInt("TRANSIT_ALERT_DAYS", 30);
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        List<InvTransferOrder> overdue = orderDao.selectList(
                new LambdaQueryWrapper<InvTransferOrder>()
                        .eq(InvTransferOrder::getStatus, InvTransferOrder.ST_OUT_POSTED)
                        .eq(InvTransferOrder::getSuspendedFlag, "0")
                        .isNotNull(InvTransferOrder::getOutPostAt)
                        .lt(InvTransferOrder::getOutPostAt, cutoff));
        int handled = 0;
        for (InvTransferOrder o : overdue) {
            // 条件更新（flag=0 前置）保证重复扫描幂等
            int n = orderDao.update(null, new LambdaUpdateWrapper<InvTransferOrder>()
                    .eq(InvTransferOrder::getId, o.getId())
                    .eq(InvTransferOrder::getSuspendedFlag, "0")
                    .set(InvTransferOrder::getSuspendedFlag, "1")
                    .setSql("VER_NO = VER_NO + 1"));
            if (n == 0) {
                continue;
            }
            noticeService.push("ROLE_WAREHOUSE", null,
                    "调拨在途超期挂起：" + o.getTransferNo(),
                    "调拨单 " + o.getTransferNo() + "（" + o.getOutWhCode() + " → "
                            + o.getInWhCode() + "）出库后超过 " + days
                            + " 天未入库，已自动挂起，请双方仓库主管与财务对账人员跟进（C-4.4-09）",
                    "TRANSFER_TRANSIT", o.getId());
            handled++;
            log.info("transfer {} transit overdue suspended", o.getTransferNo());
        }
        return handled;
    }

    // ---------- helpers ----------

    private List<InvTransferOrderLine> listLines(String orderId) {
        return lineDao.selectList(new LambdaQueryWrapper<InvTransferOrderLine>()
                .eq(InvTransferOrderLine::getOrderId, orderId)
                .orderByAsc(InvTransferOrderLine::getLineNo));
    }

    private InvTransferOrder lock(String id) {
        InvTransferOrder o = orderDao.selectByIdForUpdate(id);
        if (o == null) {
            throw new ServiceException(404, "调拨单不存在");
        }
        return o;
    }

    /** 条件状态迁移（状态前置 + 影响行数 0 → 409，防双入口并发） */
    private void transition(String id, String from, String to, LambdaUpdateWrapper<InvTransferOrder> extra) {
        LambdaUpdateWrapper<InvTransferOrder> uw = extra
                .eq(InvTransferOrder::getId, id)
                .eq(InvTransferOrder::getStatus, from)
                .set(InvTransferOrder::getStatus, to)
                .setSql("VER_NO = VER_NO + 1");
        if (orderDao.update(null, uw) == 0) {
            throw new ServiceException(409, "调拨单状态并发冲突，请重试");
        }
    }

    private void writeAlloc(InvTransferOrder o, List<InvTransferOrderLine> lines,
                            List<StockPostingEngine.Alloc> allocs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (StockPostingEngine.Alloc a : allocs) {
            InvTransferOrderLine src = a.lineIndex >= 0 && a.lineIndex < lines.size()
                    ? lines.get(a.lineIndex) : null;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lineNo", src == null ? a.lineIndex + 1 : src.getLineNo());
            m.put("itemCode", src == null ? "" : src.getItemCode());
            m.put("batchNo", a.batchNo == null ? "" : a.batchNo);
            m.put("binCode", a.binCode == null ? "" : a.binCode);
            m.put("qty", a.qty);
            out.add(m);
        }
        InvTransferOrder upd = new InvTransferOrder();
        upd.setId(o.getId());
        upd.setVerNo(o.getVerNo());
        try {
            upd.setAllocJson(jsonMapper.writeValueAsString(out));
        } catch (Exception e) {
            throw new ServiceException(422, "分配结果序列化失败：" + e.getMessage());
        }
        orderDao.updateById(upd);
    }

    private List<Map<String, Object>> readAlloc(InvTransferOrder o) {
        if (o.getAllocJson() == null || o.getAllocJson().isBlank()) {
            return List.of();
        }
        try {
            return jsonMapper.readValue(o.getAllocJson(), new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            log.warn("transfer alloc json unparsable: {}", e.getMessage());
            return List.of();
        }
    }

    private InvWarehouse requireWh(String whCode) {
        InvWarehouse wh = warehouseDao.selectOne(new LambdaQueryWrapper<InvWarehouse>()
                .eq(InvWarehouse::getWhCode, whCode));
        if (wh == null) {
            throw new ServiceException(422, "仓库不存在：" + whCode);
        }
        return wh;
    }

    private String nextNo() {
        String prefix = "TR" + LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM")) + "-";
        int max = 0;
        for (String no : orderDao.selectNosByPrefix(prefix + "%")) {
            try {
                max = Math.max(max, Integer.parseInt(no.substring(prefix.length())));
            } catch (Exception ignore) {
                // 非规范编号跳过
            }
        }
        return prefix + String.format("%04d", max + 1);
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

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
