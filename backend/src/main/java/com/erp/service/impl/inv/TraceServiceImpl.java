package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvBatchDao;
import com.erp.dao.inv.InvFreezeDao;
import com.erp.dao.inv.InvSerialDao;
import com.erp.dao.inv.InvStockDao;
import com.erp.dao.inv.TraceFlowDao;
import com.erp.dao.inv.TraceLogDao;
import com.erp.dao.inv.TraceOrderDao;
import com.erp.entity.inv.InvBatch;
import com.erp.entity.inv.InvFreeze;
import com.erp.entity.inv.InvSerial;
import com.erp.entity.inv.InvStock;
import com.erp.entity.inv.TraceFlow;
import com.erp.entity.inv.TraceLog;
import com.erp.entity.inv.TraceOrder;
import com.erp.service.inv.FreezeService;
import com.erp.service.inv.TraceService;
import com.erp.service.sd.ReservationService;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 追溯召回实现（4.13 流程 8，spec trace-recall；design D1~D4/D8/D9）。
 * 三索引归一 → 五类流向一次集合查询物化 → 冻结联动 → 拦截/召回登记 → 结案召回率。
 * 动作级分权：发起/拦截/召回/结案 = 质量角色；冻结执行复用 4.9 冻结链校验。
 */
@Slf4j
@Service
public class TraceServiceImpl implements TraceService {

    private static final DateTimeFormatter NO_DAY = DateTimeFormatter.ofPattern("yyMMdd");

    /** 结案终态集合（design D8：FROZEN→DISPOSED、LINKED、INTERCEPTED、RECEIVED/REJECTED、FAILED） */
    private static final Set<String> TERMINAL = Set.of(
            TraceFlow.ST_DISPOSED, TraceFlow.ST_LINKED, TraceFlow.ST_INTERCEPTED,
            TraceFlow.ST_RECEIVED, TraceFlow.ST_REJECTED, TraceFlow.ST_FAILED);

    private final TraceOrderDao orderDao;
    private final TraceFlowDao flowDao;
    private final TraceLogDao logDao;
    private final InvBatchDao batchDao;
    private final InvSerialDao serialDao;
    private final InvStockDao stockDao;
    private final InvFreezeDao freezeDao;
    private final FreezeService freezeService;
    private final ReservationService reservationService;
    private final com.erp.service.system.NoticeService noticeService;

    public TraceServiceImpl(TraceOrderDao orderDao, TraceFlowDao flowDao, TraceLogDao logDao,
                            InvBatchDao batchDao, InvSerialDao serialDao, InvStockDao stockDao,
                            InvFreezeDao freezeDao, FreezeService freezeService,
                            ReservationService reservationService,
                            com.erp.service.system.NoticeService noticeService) {
        this.orderDao = orderDao;
        this.flowDao = flowDao;
        this.logDao = logDao;
        this.batchDao = batchDao;
        this.serialDao = serialDao;
        this.stockDao = stockDao;
        this.freezeDao = freezeDao;
        this.freezeService = freezeService;
        this.reservationService = reservationService;
        this.noticeService = noticeService;
    }

    // ================= 8.1 发起 + 8.2 流向物化 =================

    @Override
    @Transactional
    public Map<String, Object> analyze(String indexType, String indexValue, String defectReason) {
        requireQuality("发起追溯");
        if (isBlank(indexType) || isBlank(indexValue)) {
            throw new ServiceException(422, "索引类型与索引值必填");
        }
        if (isBlank(defectReason)) {
            throw new ServiceException(422, "缺陷描述必填");
        }
        String type = indexType.trim().toUpperCase();
        if (!TraceOrder.IDX_BATCH.equals(type) && !TraceOrder.IDX_SERIAL.equals(type)
                && !TraceOrder.IDX_SUPPLIER_BATCH.equals(type)) {
            throw new ServiceException(422, "索引类型仅支持批次号/序列号/供应商批次号");
        }
        String value = indexValue.trim();
        String batchNo = resolveBatch(type, value);   // 三索引归一（422 核对批次信息/歧义）

        // 重复发起拦截：同批次存在非 CLOSED 追溯单
        TraceOrder dup = orderDao.selectOne(new LambdaQueryWrapper<TraceOrder>()
                .eq(TraceOrder::getBatchNo, batchNo)
                .ne(TraceOrder::getStatus, TraceOrder.ST_CLOSED)
                .last("LIMIT 1"));
        if (dup != null) {
            throw new ServiceException(422, "该批次存在未结案追溯单 " + dup.getTraceNo()
                    + "，请先结案后再发起（重复发起拦截）");
        }

        InvBatch ledger = batchDao.selectOne(new LambdaQueryWrapper<InvBatch>()
                .eq(InvBatch::getBatchNo, batchNo).last("LIMIT 1"));

        TraceOrder o = new TraceOrder();
        o.setTraceNo(nextTraceNo());
        o.setIndexType(type);
        o.setIndexValue(value);
        o.setBatchNo(batchNo);
        o.setItemCode(ledger == null ? null : ledger.getItemCode());
        o.setItemName(ledger == null ? null : ledger.getItemName());
        o.setDefectReason(defectReason.trim());
        o.setStatus(TraceOrder.ST_ANALYZING);
        o.setCreateBy(SecurityUtils.getCurrentUserId());
        orderDao.insert(o);
        writeLog(o.getId(), A_ANALYZE, "ORDER", o.getId(), null,
                "index=" + type + ":" + value + " batch=" + batchNo);

        materialize(o, ledger);
        Map<String, Object> out = orderMap(o);
        log.info("trace {} created: type={} value={} batch={} flows materialized",
                o.getTraceNo(), type, value, batchNo);
        return out;
    }

    /** 三索引归一到唯一批次（FR-4.4-8-1）：解析不到/歧义 422 */
    private String resolveBatch(String type, String value) {
        switch (type) {
            case TraceOrder.IDX_BATCH: {
                InvBatch b = batchDao.selectOne(new LambdaQueryWrapper<InvBatch>()
                        .eq(InvBatch::getBatchNo, value).last("LIMIT 1"));
                if (b == null) {
                    throw new ServiceException(422, "批次不存在，请核对批次信息");
                }
                return b.getBatchNo();
            }
            case TraceOrder.IDX_SERIAL: {
                List<InvSerial> rows = serialDao.selectList(new LambdaQueryWrapper<InvSerial>()
                        .eq(InvSerial::getSerialNo, value));
                Set<String> batches = new LinkedHashSet<>();
                for (InvSerial s : rows) {
                    if (!isBlank(s.getBatchNo())) {
                        batches.add(s.getBatchNo());
                    }
                }
                if (batches.isEmpty()) {
                    throw new ServiceException(422, "序列号不存在或未关联批次，请核对批次信息");
                }
                if (batches.size() > 1) {
                    throw new ServiceException(422, "序列号关联多个批次（歧义），请改用批次号发起：" + batches);
                }
                String b = batches.iterator().next();
                if (batchDao.selectCount(new LambdaQueryWrapper<InvBatch>()
                        .eq(InvBatch::getBatchNo, b)) == 0) {
                    throw new ServiceException(422, "序列号所在批次在台账不存在，请核对批次信息");
                }
                return b;
            }
            case TraceOrder.IDX_SUPPLIER_BATCH: {
                List<InvBatch> rows = batchDao.selectList(new LambdaQueryWrapper<InvBatch>()
                        .eq(InvBatch::getSupplierBatchNo, value));
                if (rows.isEmpty()) {
                    throw new ServiceException(422, "供应商批次号不存在，请核对批次信息");
                }
                if (rows.size() > 1) {
                    List<String> nos = new ArrayList<>();
                    rows.forEach(r -> nos.add(r.getBatchNo()));
                    throw new ServiceException(422, "供应商批次号对应多个内部批次（歧义），请改用批次号发起：" + nos);
                }
                return rows.get(0).getBatchNo();
            }
            default:
                throw new ServiceException(422, "不支持的索引类型：" + type);
        }
    }

    /** 五类流向物化（FR-4.4-8-2/BR-4.4-48）：每类一次集合查询（design D3），缺口照建标核查 */
    private void materialize(TraceOrder o, InvBatch ledger) {
        String batch = o.getBatchNo();
        String itemCode = o.getItemCode();
        List<TraceFlow> flows = new ArrayList<>();

        // ① 在库：位行可用/冻结分列
        Set<String> frozenWhCovered = new LinkedHashSet<>();
        for (InvStock s : stockDao.selectList(new LambdaQueryWrapper<InvStock>()
                .eq(InvStock::getBatchNo, batch))) {
            if (s.getAvailableQty() != null && s.getAvailableQty().signum() > 0) {
                flows.add(stockFlow(o, TraceFlow.F_STOCK_AVAILABLE, s, s.getAvailableQty(), null));
            }
            BigDecimal frozen = nvl(s.getQcQty()).add(nvl(s.getFinQty()));
            if (frozen.signum() > 0) {
                flows.add(stockFlow(o, TraceFlow.F_STOCK_FROZEN, s, frozen, null));
                frozenWhCovered.add(str(s.getWarehouseCode()));
            }
        }
        // ② ACTIVE 冻结台账（QC/FIN 列为 0 但冻结台账生效的仓库补覆盖）
        for (InvFreeze f : freezeDao.selectList(new LambdaQueryWrapper<InvFreeze>()
                .eq(InvFreeze::getBatchNo, batch)
                .eq(InvFreeze::getStatus, InvFreeze.ST_ACTIVE))) {
            if (frozenWhCovered.contains(str(f.getWarehouseCode()))) {
                continue;
            }
            TraceFlow fl = new TraceFlow();
            fl.setTraceId(o.getId());
            fl.setFlowType(TraceFlow.F_STOCK_FROZEN);
            fl.setStatus(TraceFlow.ST_PENDING);
            fl.setWarehouseCode(f.getWarehouseCode());
            fl.setBinCode("");
            fl.setItemCode(f.getItemCode());
            fl.setItemName(f.getItemName());
            fl.setBatchNo(batch);
            fl.setQty(nvl(f.getQty()));
            fl.setNote("来源冻结单 " + f.getFreezeNo());
            flows.add(fl);
        }
        // ③ 调拨在途（OUT_POSTED 未 IN_POSTED）
        for (Map<String, Object> r : flowDao.selectInTransitByBatch(batch)) {
            flows.add(docFlow(o, TraceFlow.F_IN_TRANSIT, r, null));
        }
        // ④ 发运（未签收 / 已签收）+ ⑤ 数据缺口（批次缺失，照建标核查）
        addShipmentFlows(o, flowDao.selectShipmentByBatch(batch), false, flows);
        if (!isBlank(itemCode)) {
            addShipmentFlows(o, flowDao.selectShipmentGapByItem(itemCode), true, flows);
        }

        int checks = 0;
        Map<String, Object> snapshot = new LinkedHashMap<>();
        List<Map<String, Object>> summary = new ArrayList<>();
        Map<String, Map<String, Object>> byType = new LinkedHashMap<>();
        for (TraceFlow fl : flows) {
            fl.setCreateBy(SecurityUtils.getCurrentUserId());
            flowDao.insert(fl);
            if ("1".equals(fl.getCheckFlag())) {
                checks++;
            }
            Map<String, Object> t = byType.computeIfAbsent(fl.getFlowType(), k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("flowType", k);
                m.put("count", 0);
                m.put("qty", BigDecimal.ZERO);
                return m;
            });
            t.put("count", ((Integer) t.get("count")) + 1);
            t.put("qty", ((BigDecimal) t.get("qty")).add(nvl(fl.getQty())));
        }
        summary.addAll(byType.values());
        snapshot.put("flows", summary);
        snapshot.put("checkCount", checks);
        snapshot.put("analyzedAt", LocalDateTime.now().toString());

        o.setReportJson(toJson(snapshot));
        o.setStatus(TraceOrder.ST_EXECUTING);
        orderDao.updateById(o);
        writeLog(o.getId(), A_MATERIALIZE, "ORDER", o.getId(),
                TraceOrder.ST_ANALYZING, TraceOrder.ST_EXECUTING + " flows=" + flows.size());
    }

    private void addShipmentFlows(TraceOrder o, List<Map<String, Object>> rows, boolean gap,
                                  List<TraceFlow> flows) {
        for (Map<String, Object> r : rows) {
            String shipStatus = str(r.get("SHIP_STATUS"));
            boolean signed = "SIGNED".equals(shipStatus);
            TraceFlow fl = docFlow(o, signed ? TraceFlow.F_OUT_SIGNED : TraceFlow.F_OUT_UNSIGNED,
                    r, gap ? "1" : null);
            if (signed) {
                fl.setExpectQty(nvl(toDec(r.get("QTY"))));   // 应召量物化定格（FR-4.4-8-5）
                fl.setSignAt(toTime(r.get("SIGN_AT")));
            }
            flows.add(fl);
        }
    }

    private TraceFlow stockFlow(TraceOrder o, String type, InvStock s, BigDecimal qty, String check) {
        TraceFlow fl = new TraceFlow();
        fl.setTraceId(o.getId());
        fl.setFlowType(type);
        fl.setStatus(TraceFlow.ST_PENDING);
        fl.setWarehouseCode(s.getWarehouseCode());
        fl.setBinCode(str(s.getBinCode()));
        fl.setItemCode(s.getItemCode());
        fl.setItemName(s.getItemName());
        fl.setBatchNo(str(s.getBatchNo()));
        fl.setQty(qty);
        fl.setCheckFlag(check);
        return fl;
    }

    private TraceFlow docFlow(TraceOrder o, String type, Map<String, Object> r, String check) {
        TraceFlow fl = new TraceFlow();
        fl.setTraceId(o.getId());
        fl.setFlowType(type);
        fl.setStatus(TraceFlow.ST_PENDING);
        fl.setWarehouseCode(str(r.get("WAREHOUSE_CODE")).isEmpty() ? str(r.get("WH_CODE")) : str(r.get("WAREHOUSE_CODE")));
        fl.setItemCode(str(r.get("ITEM_CODE")));
        fl.setItemName(str(r.get("ITEM_NAME")));
        fl.setBatchNo(str(r.get("BATCH_NO")));
        fl.setQty(nvl(toDec(r.get("QTY"))));
        fl.setSrcDocNo(str(r.get("DOC_NO")));
        fl.setSrcDocType(TraceFlow.F_IN_TRANSIT.equals(type) ? TraceFlow.SRC_TRANSFER : TraceFlow.SRC_SHIPMENT);
        fl.setCustomerCode(str(r.get("CUSTOMER_CODE")));
        fl.setCustomerName(str(r.get("CUSTOMER_NAME")));
        fl.setCheckFlag(check);
        if ("1".equals(check)) {
            fl.setNote("批次字段缺失、按物料命中疑似流向，待人工核查");
        }
        return fl;
    }

    // ================= 3.3 分页与详情 =================

    @Override
    public Map<String, Object> page(String status, String keyword, long current, long size) {
        Page<TraceOrder> p = orderDao.selectPage(new Page<>(Math.max(current, 1), Math.max(size, 1)),
                new LambdaQueryWrapper<TraceOrder>()
                        .eq(!isBlank(status), TraceOrder::getStatus, trimOrNull(status))
                        .and(!isBlank(keyword), w -> w.like(TraceOrder::getTraceNo, keyword)
                                .or().like(TraceOrder::getBatchNo, keyword)
                                .or().like(TraceOrder::getDefectReason, keyword))
                        .orderByDesc(TraceOrder::getCreateDate));
        List<Map<String, Object>> out = new ArrayList<>();
        for (TraceOrder t : p.getRecords()) {
            out.add(orderMap(t));
        }
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("records", out);
        page.put("total", p.getTotal());
        return page;
    }

    @Override
    public Map<String, Object> detail(String traceId) {
        TraceOrder o = requireOrder(traceId);
        Map<String, Object> out = orderMap(o);
        List<Map<String, Object>> flows = new ArrayList<>();
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (TraceFlow f : flowDao.selectList(new LambdaQueryWrapper<TraceFlow>()
                .eq(TraceFlow::getTraceId, o.getId())
                .orderByAsc(TraceFlow::getFlowType)
                .orderByAsc(TraceFlow::getWarehouseCode))) {
            Map<String, Object> m = flowMap(f);
            flows.add(m);
            grouped.computeIfAbsent(f.getFlowType(), k -> new ArrayList<>()).add(m);
        }
        out.put("flows", flows);
        out.put("flowsByType", grouped);
        out.put("reportJson", o.getReportJson());
        return out;
    }

    // ================= 8.3 批量冻结联动 =================

    @Override
    @Transactional
    public Map<String, Object> freezeStock(String traceId) {
        TraceOrder o = requireOrder(traceId);
        if (TraceOrder.ST_CLOSED.equals(o.getStatus())) {
            throw new ServiceException(422, "追溯单已结案");
        }
        List<TraceFlow> pendingAvail = flowDao.selectList(new LambdaQueryWrapper<TraceFlow>()
                .eq(TraceFlow::getTraceId, o.getId())
                .eq(TraceFlow::getFlowType, TraceFlow.F_STOCK_AVAILABLE)
                .eq(TraceFlow::getStatus, TraceFlow.ST_PENDING));
        List<TraceFlow> pendingFrozen = flowDao.selectList(new LambdaQueryWrapper<TraceFlow>()
                .eq(TraceFlow::getTraceId, o.getId())
                .eq(TraceFlow::getFlowType, TraceFlow.F_STOCK_FROZEN)
                .eq(TraceFlow::getStatus, TraceFlow.ST_PENDING));
        if (pendingAvail.isEmpty() && pendingFrozen.isEmpty()) {
            throw new ServiceException(422, "无可执行的冻结行（已全部处理）");
        }

        int frozenWh = 0;
        int released = 0;
        // 仓库分组按批次维度冻结（FreezeService 角色/存在性校验在此复用——design D9 不重复校验）
        Map<String, BigDecimal> qtyByWh = new LinkedHashMap<>();
        for (TraceFlow f : pendingAvail) {
            qtyByWh.merge(str(f.getWarehouseCode()), nvl(f.getQty()), BigDecimal::add);
        }
        for (Map.Entry<String, BigDecimal> e : qtyByWh.entrySet()) {
            InvFreeze req = new InvFreeze();
            req.setFreezeType(InvFreeze.T_QUALITY);
            req.setWarehouseCode(e.getKey());
            req.setItemCode(o.getItemCode());
            req.setBatchNo(o.getBatchNo());
            req.setQty(e.getValue());
            req.setScope(InvFreeze.SCOPE_BATCH);
            req.setReason("追溯冻结 " + o.getTraceNo() + "：" + o.getDefectReason());
            freezeService.apply(req);
            released += reservationService.releaseByBatch(e.getKey(), o.getItemCode(),
                    o.getBatchNo(), "追溯冻结释放 " + o.getTraceNo());   // BR-4.4-49
            frozenWh++;
            for (TraceFlow f : pendingAvail) {
                if (e.getKey().equals(str(f.getWarehouseCode()))) {
                    f.setStatus(TraceFlow.ST_FROZEN);
                    flowDao.updateById(f);
                }
            }
        }
        for (TraceFlow f : pendingFrozen) {
            f.setStatus(TraceFlow.ST_LINKED);   // 已冻结行只关联不重复冻
            f.setNote(concat(f.getNote(), "关联追溯单 " + o.getTraceNo()));
            flowDao.updateById(f);
        }
        writeLog(o.getId(), A_FREEZE, "ORDER", o.getId(),
                "PENDING", "FROZEN wh=" + frozenWh + " releasedResv=" + released);
        log.info("trace {} freeze: {} warehouse(s), {} reservation(s) released",
                o.getTraceNo(), frozenWh, released);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("frozenWarehouses", frozenWh);
        out.put("linkedFrozenRows", pendingFrozen.size());
        out.put("releasedReservations", released);
        return out;
    }

    // ================= 8.4 拦截登记 =================

    @Override
    @Transactional
    public Map<String, Object> registerIntercept(String flowId, boolean success) {
        requireQuality("登记拦截结果");
        TraceFlow f = requireFlow(flowId);
        if (!TraceFlow.F_IN_TRANSIT.equals(f.getFlowType())
                && !TraceFlow.F_OUT_UNSIGNED.equals(f.getFlowType())) {
            throw new ServiceException(422, "仅在途/未签收流向行可登记拦截");
        }
        if (!TraceFlow.ST_PENDING.equals(f.getStatus())) {
            throw new ServiceException(422, "该行已登记（当前 " + f.getStatus() + "）");
        }
        TraceOrder o = requireOrder(f.getTraceId());
        if (success) {
            f.setStatus(TraceFlow.ST_INTERCEPTED);
            f.setNote(concat(f.getNote(), "拦截成功"));
            flowDao.updateById(f);
            writeLog(o.getId(), A_INTERCEPT, "FLOW", f.getId(),
                    TraceFlow.ST_PENDING, TraceFlow.ST_INTERCEPTED);
            return Map.of("flowId", f.getId(), "status", f.getStatus());
        }
        // 拦截失败 → 原行失败终态 + 升级出已签收召回清单行（应召=原在途数量）
        f.setStatus(TraceFlow.ST_FAILED);
        f.setRejectReason("拦截失败（货已签收），升级召回");
        flowDao.updateById(f);
        TraceFlow up = new TraceFlow();
        up.setTraceId(o.getId());
        up.setFlowType(TraceFlow.F_OUT_SIGNED);
        up.setStatus(TraceFlow.ST_PENDING);
        up.setWarehouseCode(f.getWarehouseCode());
        up.setItemCode(f.getItemCode());
        up.setItemName(f.getItemName());
        up.setBatchNo(f.getBatchNo());
        up.setQty(f.getQty());
        up.setExpectQty(nvl(f.getQty()));   // 应召定格
        up.setSrcDocType(f.getSrcDocType());
        up.setSrcDocNo(f.getSrcDocNo());
        up.setCustomerCode(f.getCustomerCode());
        up.setCustomerName(f.getCustomerName());
        up.setNote("拦截失败升级召回（原行 " + f.getSrcDocNo() + "）");
        up.setCreateBy(SecurityUtils.getCurrentUserId());
        flowDao.insert(up);
        writeLog(o.getId(), A_INTERCEPT_FAIL, "FLOW", f.getId(),
                TraceFlow.ST_PENDING, TraceFlow.ST_FAILED + " → 升级召回行 " + up.getId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("flowId", f.getId());
        out.put("status", f.getStatus());
        out.put("upgradedFlowId", up.getId());
        return out;
    }

    // ================= 8.5 召回登记 =================

    @Override
    @Transactional
    public Map<String, Object> registerReturn(String flowId, BigDecimal actualQty,
                                              String returnedBatch, String rejectReason) {
        requireQuality("登记召回结果");
        TraceFlow f = requireFlow(flowId);
        if (!TraceFlow.F_OUT_SIGNED.equals(f.getFlowType())) {
            throw new ServiceException(422, "仅已签收召回清单行可登记");
        }
        if (!TraceFlow.ST_PENDING.equals(f.getStatus())) {
            throw new ServiceException(422, "该行已登记（当前 " + f.getStatus()
                    + "），应召数量物化后不可重复修改");
        }
        boolean reject = !isBlank(rejectReason);
        if (reject) {
            f.setStatus(TraceFlow.ST_REJECTED);
            f.setRejectReason(rejectReason.trim());
            f.setActualQty(BigDecimal.ZERO);
            flowDao.updateById(f);
            writeLog(f.getTraceId(), A_RETURN, "FLOW", f.getId(),
                    "PENDING", "REJECTED 原因=" + rejectReason.trim());
            try {
                noticeService.push("ROLE_QUALITY_MGR", null,
                        "召回拒退待复核：" + f.getCustomerName(),
                        "批次 " + f.getBatchNo() + " 客户 " + str(f.getCustomerName())
                                + " 拒退，原因：" + rejectReason.trim() + "（追溯单关联）",
                        "TRACE_REJECT", f.getId());
            } catch (Exception e) {
                log.warn("trace reject notify failed: {}", e.getMessage());
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("flowId", f.getId());
            out.put("status", f.getStatus());
            return out;
        }
        if (actualQty == null || actualQty.signum() <= 0) {
            throw new ServiceException(422, "实退数量必填且大于 0（或登记拒退原因）");
        }
        BigDecimal expect = nvl(f.getExpectQty());
        if (expect.signum() > 0 && actualQty.compareTo(expect) > 0) {
            throw new ServiceException(422, "实退数量不可超过应召数量 " + strip(expect)
                    + "（应召量物化定格）");
        }
        f.setStatus(TraceFlow.ST_RECEIVED);
        f.setActualQty(actualQty);
        f.setReturnedBatch(returnedBatch);
        flowDao.updateById(f);
        writeLog(f.getTraceId(), A_RETURN, "FLOW", f.getId(),
                "PENDING", "RECEIVED 实退=" + strip(actualQty));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("flowId", f.getId());
        out.put("status", f.getStatus());
        return out;
    }

    // ================= 8.7 结案 =================

    @Override
    @Transactional
    public Map<String, Object> close(String traceId) {
        requireQualityMgr("生成结案报告");
        TraceOrder o = requireOrder(traceId);
        if (TraceOrder.ST_CLOSED.equals(o.getStatus())) {
            throw new ServiceException(422, "追溯单已结案");
        }
        List<TraceFlow> all = flowDao.selectList(new LambdaQueryWrapper<TraceFlow>()
                .eq(TraceFlow::getTraceId, o.getId()));
        if (all.isEmpty()) {
            throw new ServiceException(422, "无流向行，无法结案");
        }
        // 未闭环校验（spec 结案需求）
        List<Map<String, Object>> open = new ArrayList<>();
        for (TraceFlow f : all) {
            if (!TERMINAL.contains(f.getStatus())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("flowId", f.getId());
                m.put("flowType", f.getFlowType());
                m.put("status", f.getStatus());
                m.put("customerName", f.getCustomerName());
                m.put("srcDocNo", f.getSrcDocNo());
                m.put("qty", f.getQty());
                open.add(m);
            }
        }
        if (!open.isEmpty()) {
            throw new ServiceException(422, "存在 " + open.size()
                    + " 条未闭环流向行，请先完成冻结/拦截/召回登记：" + open);
        }

        // 召回率 = Σ实退 / Σ应召（BR-4.4-52）+ <100% 逐笔未召回
        BigDecimal expectSum = BigDecimal.ZERO;
        BigDecimal actualSum = BigDecimal.ZERO;
        List<Map<String, Object>> unreturned = new ArrayList<>();
        for (TraceFlow f : all) {
            if (!TraceFlow.F_OUT_SIGNED.equals(f.getFlowType())) {
                continue;
            }
            expectSum = expectSum.add(nvl(f.getExpectQty()));
            actualSum = actualSum.add(nvl(f.getActualQty()));
            BigDecimal short2 = nvl(f.getExpectQty()).subtract(nvl(f.getActualQty()));
            if (short2.signum() > 0) {
                Map<String, Object> u = new LinkedHashMap<>();
                u.put("customerName", f.getCustomerName());
                u.put("srcDocNo", f.getSrcDocNo());
                u.put("expectQty", f.getExpectQty());
                u.put("actualQty", f.getActualQty());
                u.put("shortQty", short2);
                u.put("reason", TraceFlow.ST_REJECTED.equals(f.getStatus())
                        ? str(f.getRejectReason()) : "未退回");
                unreturned.add(u);
            }
        }
        BigDecimal rate = expectSum.signum() > 0
                ? actualSum.multiply(new BigDecimal("100")).divide(expectSum, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("traceNo", o.getTraceNo());
        report.put("batchNo", o.getBatchNo());
        report.put("expectTotal", expectSum);
        report.put("actualTotal", actualSum);
        report.put("recallRate", rate);
        report.put("unreturned", unreturned);
        report.put("closedAt", LocalDateTime.now().toString());
        report.put("closedBy", SecurityUtils.getCurrentUserId());

        // REPORT_JSON 双段：保留流向快照 + 追加结案报告
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("flow", parseJson(o.getReportJson()));
        doc.put("close", report);
        o.setReportJson(toJson(doc));
        o.setStatus(TraceOrder.ST_CLOSED);
        o.setCloseBy(SecurityUtils.getCurrentUserId());
        o.setCloseAt(LocalDateTime.now());
        if (orderDao.updateById(o) == 0) {
            throw new ServiceException(409, "追溯单状态冲突，请刷新重试");
        }
        writeLog(o.getId(), A_CLOSE, "ORDER", o.getId(),
                TraceOrder.ST_EXECUTING, TraceOrder.ST_CLOSED + " recallRate=" + rate + "%");
        log.info("trace {} CLOSED: recallRate={}%", o.getTraceNo(), rate);
        Map<String, Object> out = orderMap(o);
        out.put("report", report);
        return out;
    }

    // ================= 8.2 审计查询 =================

    @Override
    public List<Map<String, Object>> auditLogs(String traceId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TraceLog l : logDao.selectList(new LambdaQueryWrapper<TraceLog>()
                .eq(TraceLog::getTraceId, isBlank(traceId) ? "" : traceId.trim())
                .orderByAsc(TraceLog::getOpAt)
                .orderByAsc(TraceLog::getId))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.getId());
            m.put("actionUser", l.getActionUser());
            m.put("action", l.getAction());
            m.put("objectType", l.getObjectType());
            m.put("objectId", l.getObjectId());
            m.put("beforeVal", l.getBeforeVal());
            m.put("afterVal", l.getAfterVal());
            m.put("opAt", l.getOpAt());
            out.add(m);
        }
        return out;
    }

    // ---------- helpers ----------

    private TraceOrder requireOrder(String id) {
        TraceOrder o = isBlank(id) ? null : orderDao.selectById(id);
        if (o == null) {
            throw new ServiceException(422, "追溯单不存在");
        }
        return o;
    }

    private TraceFlow requireFlow(String id) {
        TraceFlow f = isBlank(id) ? null : flowDao.selectById(id);
        if (f == null) {
            throw new ServiceException(422, "流向行不存在");
        }
        return f;
    }

    private Map<String, Object> orderMap(TraceOrder o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.getId());
        m.put("traceNo", o.getTraceNo());
        m.put("indexType", o.getIndexType());
        m.put("indexValue", o.getIndexValue());
        m.put("batchNo", o.getBatchNo());
        m.put("itemCode", o.getItemCode());
        m.put("itemName", o.getItemName());
        m.put("defectReason", o.getDefectReason());
        m.put("status", o.getStatus());
        m.put("createBy", o.getCreateBy());
        m.put("createDate", o.getCreateDate());
        m.put("closeBy", o.getCloseBy());
        m.put("closeAt", o.getCloseAt());
        return m;
    }

    private Map<String, Object> flowMap(TraceFlow f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.getId());
        m.put("flowType", f.getFlowType());
        m.put("status", f.getStatus());
        m.put("warehouseCode", f.getWarehouseCode());
        m.put("binCode", f.getBinCode());
        m.put("itemCode", f.getItemCode());
        m.put("itemName", f.getItemName());
        m.put("batchNo", f.getBatchNo());
        m.put("qty", f.getQty());
        m.put("expectQty", f.getExpectQty());
        m.put("actualQty", f.getActualQty());
        m.put("returnedBatch", f.getReturnedBatch());
        m.put("rejectReason", f.getRejectReason());
        m.put("srcDocType", f.getSrcDocType());
        m.put("srcDocNo", f.getSrcDocNo());
        m.put("customerCode", f.getCustomerCode());
        m.put("customerName", f.getCustomerName());
        m.put("signAt", f.getSignAt());
        m.put("checkFlag", f.getCheckFlag());
        m.put("note", f.getNote());
        return m;
    }

    private void writeLog(String traceId, String action, String objectType, String objectId,
                          String before, String after) {
        TraceLog l = new TraceLog();
        l.setTraceId(traceId);
        l.setActionUser(str(SecurityUtils.getCurrentUserId()));
        l.setAction(action);
        l.setObjectType(objectType);
        l.setObjectId(objectId);
        l.setBeforeVal(before);
        l.setAfterVal(after);
        l.setOpAt(LocalDateTime.now());
        logDao.insert(l);
    }

    /** TR+yyMMdd+4位日流水（唯一键冲突重试，design D1） */
    private String nextTraceNo() {
        String prefix = "TR" + LocalDate.now().format(NO_DAY) + "-";
        for (int attempt = 0; attempt < 5; attempt++) {
            Long cnt = orderDao.selectCount(new LambdaQueryWrapper<TraceOrder>()
                    .likeRight(TraceOrder::getTraceNo, prefix));
            String no = prefix + String.format("%04d", (cnt == null ? 0 : cnt) + 1 + attempt);
            Long dup = orderDao.selectCount(new LambdaQueryWrapper<TraceOrder>()
                    .eq(TraceOrder::getTraceNo, no));
            if (dup == null || dup == 0) {
                return no;
            }
        }
        return prefix + System.nanoTime() % 100000;
    }

    private void requireQuality(String action) {
        requireAny(action, "ROLE_QUALITY_ENG", "ROLE_QUALITY_MGR", "ROLE_ADMIN");
    }

    private void requireQualityMgr(String action) {
        requireAny(action, "ROLE_QUALITY_MGR", "ROLE_ADMIN");
    }

    private void requireAny(String action, String... roles) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        List<String> own = new ArrayList<>();
        auth.getAuthorities().forEach(a -> own.add(a.getAuthority()));
        for (String r : roles) {
            if (own.stream().anyMatch(x -> r.equalsIgnoreCase(x))) {
                return;
            }
        }
        throw new ServiceException(403, action + "：需要 " + String.join("/", roles) + " 角色");
    }

    private static String toJson(Object o) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ServiceException(422, "JSON 序列化失败：" + e.getMessage());
        }
    }

    private static Object parseJson(String s) {
        if (isBlank(s)) {
            return Map.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(s, Object.class);
        } catch (Exception e) {
            return Map.of("raw", s);
        }
    }

    private static BigDecimal toDec(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof BigDecimal d) {
            return d;
        }
        if (o instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        try {
            return new BigDecimal(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDateTime toTime(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof LocalDateTime t) {
            return t;
        }
        try {
            return LocalDateTime.parse(String.valueOf(o));
        } catch (Exception e) {
            return null;
        }
    }

    private static String concat(String old, String add) {
        if (old == null || old.isEmpty()) {
            return add;
        }
        return old + "；" + add;
    }

    private static String strip(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String str(Object s) {
        return s == null ? "" : String.valueOf(s);
    }

    private static String trimOrNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
