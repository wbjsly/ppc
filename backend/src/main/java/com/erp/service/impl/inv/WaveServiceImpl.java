package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvRouteDao;
import com.erp.dao.inv.InvWaveAdjustDao;
import com.erp.dao.inv.InvWaveDao;
import com.erp.dao.inv.InvWaveDocDao;
import com.erp.dao.inv.InvWaveLineDao;
import com.erp.dao.sd.ShipmentDao;
import com.erp.entity.inv.InvCheckDiff;
import com.erp.entity.inv.InvRoute;
import com.erp.entity.inv.InvWave;
import com.erp.entity.inv.InvWaveAdjust;
import com.erp.entity.inv.InvWaveDoc;
import com.erp.entity.inv.InvWaveLine;
import com.erp.entity.sd.Shipment;
import com.erp.service.inv.WaveService;
import com.erp.service.SysParamService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 波次管理（4.8.1/4.8.2，spec wave-management；design D1~D6）：
 * 手动触发+自动聚类（偏差 D1，候选预览两步 design D2）、波次状态机事件驱动（D5）、
 * 改批行级锁+审批底座（D4）、分播/装车/发运三步（D6）。首期仅收 SALES_OUT（偏差 D2）。
 */
@Slf4j
@Service
public class WaveServiceImpl implements WaveService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 合法迁移表（spec 波次状态机）：CREATED→ALLOCATED→PICKING→SORTING→STAGING→SHIPPING→CLOSED；CANCELLED 仅未开拣 */
    private static final Map<String, Set<String>> TRANSITIONS = new HashMap<>();

    static {
        TRANSITIONS.put(InvWave.ST_CREATED, Set.of(InvWave.ST_ALLOCATED, InvWave.ST_CANCELLED));
        TRANSITIONS.put(InvWave.ST_ALLOCATED, Set.of(InvWave.ST_PICKING, InvWave.ST_CANCELLED));
        TRANSITIONS.put(InvWave.ST_PICKING, Set.of(InvWave.ST_SORTING));
        TRANSITIONS.put(InvWave.ST_SORTING, Set.of(InvWave.ST_STAGING));
        TRANSITIONS.put(InvWave.ST_STAGING, Set.of(InvWave.ST_SHIPPING));
        TRANSITIONS.put(InvWave.ST_SHIPPING, Set.of(InvWave.ST_CLOSED));
        TRANSITIONS.put(InvWave.ST_CLOSED, Set.of());
        TRANSITIONS.put(InvWave.ST_CANCELLED, Set.of());
    }

    private final InvWaveDao waveDao;
    private final InvWaveDocDao waveDocDao;
    private final InvWaveLineDao waveLineDao;
    private final InvWaveAdjustDao adjustDao;
    private final InvRouteDao routeDao;
    private final ShipmentDao shipmentDao;
    private final com.erp.dao.sd.ShipmentLineDao shipmentLineDao;
    private final com.erp.service.inv.BatchRecommendService recommendService;
    private final com.erp.dao.inv.InvBatchDao batchDao;
    private final com.erp.dao.inv.InvCheckDiffDao diffDao;
    private final com.erp.service.sd.ShipmentService shipmentService;
    private final com.erp.service.approval.ApprovalEngine approvalEngine;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final SysParamService paramService;

    public WaveServiceImpl(InvWaveDao waveDao, InvWaveDocDao waveDocDao,
                           InvWaveLineDao waveLineDao, InvWaveAdjustDao adjustDao,
                           InvRouteDao routeDao, ShipmentDao shipmentDao,
                           com.erp.dao.sd.ShipmentLineDao shipmentLineDao,
                           com.erp.service.inv.BatchRecommendService recommendService,
                           com.erp.dao.inv.InvBatchDao batchDao,
                           com.erp.dao.inv.InvCheckDiffDao diffDao,
                           com.erp.service.sd.ShipmentService shipmentService,
                           com.erp.service.approval.ApprovalEngine approvalEngine,
                           org.springframework.jdbc.core.JdbcTemplate jdbc,
                           SysParamService paramService) {
        this.waveDao = waveDao;
        this.waveDocDao = waveDocDao;
        this.waveLineDao = waveLineDao;
        this.adjustDao = adjustDao;
        this.routeDao = routeDao;
        this.shipmentDao = shipmentDao;
        this.shipmentLineDao = shipmentLineDao;
        this.recommendService = recommendService;
        this.batchDao = batchDao;
        this.diffDao = diffDao;
        this.shipmentService = shipmentService;
        this.approvalEngine = approvalEngine;
        this.jdbc = jdbc;
        this.paramService = paramService;
    }

    // ==================== 3.1 聚类候选预览（不落库） ====================

    @Override
    public Map<String, Object> preview() {
        requireWrite("生成波次候选");
        int maxDocs = Math.max(1, paramService.getInt("WAVE_MAX_DOCS", 50));
        List<Shipment> candidates = clusterableShipments();

        // 桶键：线路非空 → ROUTE；否则承运商非空 → CARRIER；否则 CUSTOMER（design D2 降级链，
        // 空线路单据彼此不与有线索单据同组）
        Map<String, List<Shipment>> buckets = new LinkedHashMap<>();
        for (Shipment s : candidates) {
            buckets.computeIfAbsent(clusterKeyOf(s), k -> new ArrayList<>()).add(s);
        }

        Map<String, String> routeNames = routeNames();
        List<Map<String, Object>> groups = new ArrayList<>();
        int totalDocs = 0;
        for (Map.Entry<String, List<Shipment>> e : buckets.entrySet()) {
            List<Shipment> docs = e.getValue();
            // 桶内按创建时间升序（稳定），再按单号
            docs.sort((a, b) -> {
                int c = nullSafe(a.getCreateDate()).compareTo(nullSafe(b.getCreateDate()));
                return c != 0 ? c : nullSafe(a.getShipNo()).compareTo(nullSafe(b.getShipNo()));
            });
            String type = e.getKey().substring(0, e.getKey().indexOf(':'));
            String key = e.getKey().substring(e.getKey().indexOf(':') + 1);
            int chunkNo = 0;
            for (int i = 0; i < docs.size(); i += maxDocs) {
                List<Shipment> chunk = docs.subList(i, Math.min(i + maxDocs, docs.size()));
                chunkNo++;
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("clusterType", type);
                g.put("clusterKey", key);
                g.put("clusterLabel", clusterLabel(type, key, routeNames));
                g.put("splitReason", docs.size() > maxDocs
                        ? String.format("超容量拆分（%d > WAVE_MAX_DOCS=%d，第 %d/%d 组）",
                        docs.size(), maxDocs, chunkNo,
                        (docs.size() + maxDocs - 1) / maxDocs)
                        : "");
                List<Map<String, Object>> rows = new ArrayList<>();
                for (Shipment s : chunk) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("shipId", s.getId());
                    row.put("shipNo", s.getShipNo());
                    row.put("customerName", s.getCustomerName());
                    row.put("logisticsCo", s.getLogisticsCo());
                    row.put("totalQty", s.getTotalQty());
                    row.put("warehouseCode", s.getWarehouseCode());
                    rows.add(row);
                }
                g.put("docs", rows);
                groups.add(g);
                totalDocs += chunk.size();
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("groups", groups);
        out.put("totalDocs", totalDocs);
        out.put("maxDocs", maxDocs);
        out.put("message", candidates.isEmpty()
                ? "无可合并单据：无待出库（DRAFT）销售发货单，不生成空波次（偏差 D2，单据仍走 4.6.3→4.7 链路）"
                : "");
        return out;
    }

    /** 可入波次的 DRAFT 发货单：排除已绑定未关闭波次的单据（防重复入波） */
    private List<Shipment> clusterableShipments() {
        List<Shipment> draft = shipmentDao.selectList(new LambdaQueryWrapper<Shipment>()
                .eq(Shipment::getStatus, Shipment.ST_DRAFT)
                .orderByAsc(Shipment::getShipNo));
        if (draft.isEmpty()) {
            return draft;
        }
        Set<String> bound = activeBoundShipIds();
        return draft.stream()
                .filter(s -> !bound.contains(s.getId()))
                .collect(Collectors.toList());
    }

    /** BOUND 且波次未关闭/未作废的发货单 ID（这些单已在波次中） */
    private Set<String> activeBoundShipIds() {
        List<InvWaveDoc> docs = waveDocDao.selectList(new LambdaQueryWrapper<InvWaveDoc>()
                .eq(InvWaveDoc::getBindStatus, InvWaveDoc.BIND_BOUND));
        if (docs.isEmpty()) {
            return Set.of();
        }
        Set<String> waveIds = docs.stream().map(InvWaveDoc::getWaveId)
                .collect(Collectors.toCollection(HashSet::new));
        List<InvWave> waves = waveDao.selectBatchIds(waveIds);
        Set<String> open = waves.stream()
                .filter(w -> !InvWave.ST_CLOSED.equals(w.getStatus())
                        && !InvWave.ST_CANCELLED.equals(w.getStatus()))
                .map(InvWave::getId)
                .collect(Collectors.toCollection(HashSet::new));
        return docs.stream()
                .filter(d -> open.contains(d.getWaveId()))
                .map(InvWaveDoc::getShipId)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private String clusterKeyOf(Shipment s) {
        if (s.getRouteId() != null && !s.getRouteId().trim().isEmpty()) {
            return "ROUTE:" + s.getRouteId().trim();
        }
        if (s.getLogisticsCo() != null && !s.getLogisticsCo().trim().isEmpty()) {
            return "CARRIER:" + s.getLogisticsCo().trim();
        }
        return "CUSTOMER:" + nullSafe(s.getCustomerCode());
    }

    private String clusterLabel(String type, String key, Map<String, String> routeNames) {
        switch (type) {
            case "ROUTE":
                return "线路：" + routeNames.getOrDefault(key, key);
            case "CARRIER":
                return "承运商：" + key;
            default:
                return "客户：" + key;
        }
    }

    private Map<String, String> routeNames() {
        Map<String, String> out = new HashMap<>();
        for (InvRoute r : routeDao.selectList(null)) {
            out.put(r.getId(), r.getRouteCode() + " " + r.getRouteName());
        }
        return out;
    }

    // ==================== 3.2 确认候选建波次 ====================

    @Override
    @Transactional
    public Map<String, Object> createFromPreview(List<Map<String, Object>> groups) {
        requireWrite("建波次");
        if (groups == null || groups.isEmpty()) {
            throw new ServiceException(422, "候选组为空：无可合并单据（不生成空波次）");
        }
        Set<String> bound = activeBoundShipIds();
        List<Map<String, Object>> created = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int totalNew = 0;

        for (Map<String, Object> g : groups) {
            Object docsRaw = g.get("docs");
            if (!(docsRaw instanceof List)) {
                continue;
            }
            List<String> shipIds = new ArrayList<>();
            for (Object o : (List<?>) docsRaw) {
                if (o instanceof Map) {
                    Object id = ((Map<?, ?>) o).get("shipId");
                    if (id != null) {
                        shipIds.add(String.valueOf(id));
                    }
                } else if (o instanceof String) {
                    shipIds.add((String) o);
                }
            }
            // 逐单校验：仍 DRAFT 且未被其他活跃波次绑定（并发失效剔除并说明）
            List<String> validIds = new ArrayList<>();
            for (String id : shipIds) {
                Shipment s = shipmentDao.selectById(id);
                if (s == null) {
                    skipped.add(id + "：单据不存在");
                } else if (!Shipment.ST_DRAFT.equals(s.getStatus())) {
                    skipped.add(s.getShipNo() + "：状态已非 DRAFT（" + s.getStatus() + "）");
                } else if (bound.contains(id)) {
                    skipped.add(s.getShipNo() + "：已归属其他活跃波次");
                } else {
                    validIds.add(id);
                }
            }
            if (validIds.isEmpty()) {
                continue;
            }

            InvWave wave = new InvWave();
            wave.setId(uuid());
            wave.setWaveNo(nextWaveNo());
            wave.setClusterType(str(g.get("clusterType")));
            wave.setClusterKey(str(g.get("clusterKey")));
            wave.setStatus(InvWave.ST_CREATED);
            wave.setDocCount(validIds.size());
            wave.setCancelReason(null);
            waveDao.insert(wave);

            int n = 0;
            for (String id : validIds) {
                Shipment s = shipmentDao.selectById(id);
                InvWaveDoc d = new InvWaveDoc();
                d.setId(uuid());
                d.setWaveId(wave.getId());
                d.setShipId(s.getId());
                d.setShipNo(s.getShipNo());
                d.setBindStatus(InvWaveDoc.BIND_BOUND);
                d.setSortStatus(InvWaveDoc.SORT_PENDING);
                d.setLoadStatus(InvWaveDoc.LOAD_PENDING);
                waveDocDao.insert(d);
                n++;
            }
            wave.setDocCount(n);
            waveDao.updateById(wave);
            totalNew += n;

            Map<String, Object> c = new LinkedHashMap<>();
            c.put("waveId", wave.getId());
            c.put("waveNo", wave.getWaveNo());
            c.put("clusterType", wave.getClusterType());
            c.put("clusterKey", wave.getClusterKey());
            c.put("docCount", n);
            created.add(c);
        }

        if (created.isEmpty()) {
            throw new ServiceException(422, "全部候选单据已失效（"
                    + String.join("；", skipped) + "），未建波次");
        }
        log.info("waves created: {} from preview, skipped={}", created.size(), skipped.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("waves", created);
        out.put("skipped", skipped);
        return out;
    }

    // ==================== 3.3 分页 / 详情 / 状态机 / 作废 ====================

    @Override
    public Map<String, Object> page(String status, String keyword, long current, long size) {
        Page<InvWave> p = waveDao.selectPage(new Page<>(Math.max(current, 1), Math.max(size, 1)),
                new LambdaQueryWrapper<InvWave>()
                        .eq(hasText(status), InvWave::getStatus, status)
                        .and(hasText(keyword), w -> w
                                .like(InvWave::getWaveNo, keyword.trim())
                                .or().like(InvWave::getClusterKey, keyword.trim()))
                        .orderByDesc(InvWave::getCreateDate));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", p.getRecords());
        out.put("total", p.getTotal());
        return out;
    }

    @Override
    public Map<String, Object> detail(String waveId) {
        InvWave wave = require(waveId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("wave", wave);
        out.put("docs", waveDocDao.selectList(new LambdaQueryWrapper<InvWaveDoc>()
                .eq(InvWaveDoc::getWaveId, waveId)
                .orderByAsc(InvWaveDoc::getShipNo)));
        out.put("lines", waveLineDao.selectList(new LambdaQueryWrapper<InvWaveLine>()
                .eq(InvWaveLine::getWaveId, waveId)
                .orderByAsc(InvWaveLine::getLineNo)));
        out.put("adjusts", adjustDao.selectList(new LambdaQueryWrapper<InvWaveAdjust>()
                .eq(InvWaveAdjust::getWaveId, waveId)
                .orderByDesc(InvWaveAdjust::getCreateDate)));
        return out;
    }

    @Override
    @Transactional
    public InvWave transition(String waveId, String fromStatus, String toStatus) {
        requireWrite("波次状态变更");
        InvWave wave = require(waveId);
        Set<String> allowed = TRANSITIONS.getOrDefault(wave.getStatus(), Set.of());
        if (!wave.getStatus().equals(fromStatus) || !allowed.contains(toStatus)) {
            throw new ServiceException(422, "非法状态迁移：当前 " + wave.getStatus()
                    + "（期望 " + fromStatus + "）→ " + toStatus);
        }
        InvWave patch = new InvWave();
        patch.setId(wave.getId());
        patch.setStatus(toStatus);
        patch.setVerNo(wave.getVerNo());
        stampTimes(patch, toStatus);
        if (waveDao.updateById(patch) == 0) {
            throw new ServiceException(409, "波次状态并发冲突，请刷新后重试");
        }
        return waveDao.selectById(wave.getId());
    }

    private void stampTimes(InvWave patch, String to) {
        LocalDateTime now = LocalDateTime.now();
        switch (to) {
            case InvWave.ST_ALLOCATED -> patch.setAllocAt(now);
            case InvWave.ST_SORTING -> patch.setPickAt(now);
            case InvWave.ST_STAGING -> patch.setSortAt(now);
            case InvWave.ST_SHIPPING -> patch.setLoadAt(now);
            case InvWave.ST_CLOSED -> patch.setCloseAt(now);
            default -> { }
        }
    }

    @Override
    @Transactional
    public Map<String, Object> cancel(String waveId, String reason) {
        requireWrite("作废波次");
        if (!hasText(reason)) {
            throw new ServiceException(422, "作废原因必填");
        }
        InvWave wave = require(waveId);
        if (!InvWave.ST_CREATED.equals(wave.getStatus())
                && !InvWave.ST_ALLOCATED.equals(wave.getStatus())) {
            throw new ServiceException(422, "仅未开拣（CREATED/ALLOCATED）波次可作废，当前 "
                    + wave.getStatus());
        }
        // 1) 解绑全部单据归属（保留行，C-0-05；单据回到 4.6.3→4.7 链路）
        waveDocDao.update(null, new LambdaUpdateWrapper<InvWaveDoc>()
                .eq(InvWaveDoc::getWaveId, waveId)
                .eq(InvWaveDoc::getBindStatus, InvWaveDoc.BIND_BOUND)
                .set(InvWaveDoc::getBindStatus, InvWaveDoc.BIND_UNBOUND)
                .set(InvWaveDoc::getUnboundReason, "波次作废：" + reason)
                .setSql("VER_NO = VER_NO + 1"));
        // 2) 分配行保留（分配视图留痕），锁全解
        waveLineDao.update(null, new LambdaUpdateWrapper<InvWaveLine>()
                .eq(InvWaveLine::getWaveId, waveId)
                .eq(InvWaveLine::getLockFlag, "1")
                .set(InvWaveLine::getLockFlag, "0")
                .setSql("VER_NO = VER_NO + 1"));
        // 3) 未决改批调整关闭留痕（回调按 adjust.status 校验，波次作废后签署不再改业务）
        adjustDao.update(null, new LambdaUpdateWrapper<InvWaveAdjust>()
                .eq(InvWaveAdjust::getWaveId, waveId)
                .eq(InvWaveAdjust::getStatus, InvWaveAdjust.ST_PENDING)
                .set(InvWaveAdjust::getStatus, InvWaveAdjust.ST_CANCELLED)
                .setSql("VER_NO = VER_NO + 1"));
        // 4) WAVE 任务作废（存在且未终态时）
        cancelWaveTask(wave, reason);

        InvWave patch = new InvWave();
        patch.setId(wave.getId());
        patch.setStatus(InvWave.ST_CANCELLED);
        patch.setCancelReason(reason);
        patch.setVerNo(wave.getVerNo());
        if (waveDao.updateById(patch) == 0) {
            throw new ServiceException(409, "波次状态并发冲突，请刷新后重试");
        }
        log.info("wave {} cancelled: {}", wave.getWaveNo(), reason);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("wave", waveDao.selectById(waveId));
        return out;
    }

    /** WAVE 任务作废（由任务服务执行，避免反向依赖；无任务/已终态空操作） */
    private void cancelWaveTask(InvWave wave, String reason) {
        // 延迟到任务组（5.1）接入：此处通过 bean 代理调用，未生成任务时无操作。
        try {
            waveTaskOps.cancelIfExists(wave.getWaveNo(), "波次作废：" + reason);
        } catch (RuntimeException e) {
            log.warn("cancel wave task {} failed (non-blocking): {}", wave.getWaveNo(), e.getMessage());
        }
    }

    // ==================== 4.2 波次级统一分配（design D3 共享预算） ====================

    @Override
    @Transactional
    public Map<String, Object> allocate(String waveId) {
        requireWrite("波次分配");
        InvWave wave = require(waveId);
        if (!InvWave.ST_CREATED.equals(wave.getStatus())) {
            throw new ServiceException(422, "仅 CREATED 波次可分配（当前 " + wave.getStatus() + "）");
        }
        // C-4.4-08：存在 LOCKED 改批行 → 不可重算（冻结拣货语义）
        Long locked = waveLineDao.selectCount(new LambdaQueryWrapper<InvWaveLine>()
                .eq(InvWaveLine::getWaveId, waveId)
                .eq(InvWaveLine::getLockFlag, "1"));
        if (locked != null && locked > 0) {
            throw new ServiceException(422, "存在改批审批中的行（LOCKED），不可重新分配");
        }

        List<InvWaveDoc> bound = waveDocDao.selectList(new LambdaQueryWrapper<InvWaveDoc>()
                .eq(InvWaveDoc::getWaveId, waveId)
                .eq(InvWaveDoc::getBindStatus, InvWaveDoc.BIND_BOUND)
                .orderByAsc(InvWaveDoc::getShipNo));
        if (bound.isEmpty()) {
            throw new ServiceException(422, "波次无在波单据（可能已全部拆出）");
        }

        // 迭代拆单（BR-4.4-47）：按单据创建序先到先得切分，取不满的单拆出 → 重算，直至稳定
        Set<String> unbound = new HashSet<>();
        List<Map<String, Object>> plan = new ArrayList<>();
        int rounds = 0;
        while (rounds++ <= bound.size()) {
            List<InvWaveDoc> active = bound.stream()
                    .filter(d -> !unbound.contains(d.getId()))
                    .collect(Collectors.toList());
            if (active.isEmpty()) {
                break;
            }
            PlanAttempt attempt = buildPlan(active, waveId);
            if (attempt.shortIds.isEmpty()) {
                plan = attempt.plan;
                break;
            }
            unbound.addAll(attempt.shortIds);
        }

        // 全部拆出 → 波次 CANCELLED（spec：全部订单被拆出时）
        if (unbound.size() == bound.size()) {
            Map<String, Object> res = cancel(waveId, "全部订单可用批次不足，波次拆空（BR-4.4-47）");
            res.put("allUnbound", true);
            res.put("message", "全部订单可用批次不足已拆出，波次取消（BR-4.4-47）");
            return res;
        }
        // 拆出部分单据（保留行，C-0-05）
        if (!unbound.isEmpty()) {
            for (InvWaveDoc d : bound) {
                if (unbound.contains(d.getId())) {
                    InvWaveDoc patch = new InvWaveDoc();
                    patch.setId(d.getId());
                    patch.setBindStatus(InvWaveDoc.BIND_UNBOUND);
                    patch.setUnboundReason("可用批次不足拆出波次转分批出库（BR-4.4-47）");
                    patch.setVerNo(d.getVerNo());
                    waveDocDao.updateById(patch);
                }
            }
        }

        // 落分配行（CREATED 期间幂等重算：清旧行重插）+ 回写发货行
        waveLineDao.delete(new LambdaQueryWrapper<InvWaveLine>()
                .eq(InvWaveLine::getWaveId, waveId));
        Map<String, InvWaveLine> firstSegOfShipLine = new LinkedHashMap<>();
        int seq = 0;
        for (Map<String, Object> a : plan) {
            InvWaveLine wl = new InvWaveLine();
            wl.setId(uuid());
            wl.setWaveId(waveId);
            wl.setShipId(str(a.get("shipId")));
            wl.setShipLineId(str(a.get("shipLineId")));
            wl.setLineNo((Integer) a.get("shipLineNo"));
            wl.setItemCode(str(a.get("itemCode")));
            wl.setItemName(str(a.get("itemName")));
            wl.setWarehouseCode(str(a.get("warehouseCode")));
            wl.setBatchNo(str(a.get("batchNo")));
            wl.setBinCode(str(a.get("binCode")));
            wl.setQty((java.math.BigDecimal) a.get("qty"));
            wl.setLockFlag("0");
            waveLineDao.insert(wl);
            firstSegOfShipLine.putIfAbsent(wl.getShipLineId(), wl);
            seq++;
        }
        // 双写发货行：回写首段 (batch, bin)（展示与 4.6.3 页面可见；
        // 过账按分配行展开——见 ShipmentServiceImpl.doPost 波次分支，跨批不依赖单值回写）
        for (Map.Entry<String, InvWaveLine> e : firstSegOfShipLine.entrySet()) {
            writeBackShipLine(e.getKey(), e.getValue().getBatchNo(), e.getValue().getBinCode());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("waveId", waveId);
        out.put("waveNo", wave.getWaveNo());
        out.put("allocatedLines", plan.size());
        out.put("unboundDocs", unbound.size());
        out.put("status", waveDao.selectById(waveId).getStatus());
        log.info("wave {} allocated: {} lines, unbound={}", wave.getWaveNo(), plan.size(),
                unbound.size());
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> confirmAllocate(String waveId) {
        requireWrite("确认波次分配");
        InvWave wave = require(waveId);
        if (!InvWave.ST_CREATED.equals(wave.getStatus())) {
            throw new ServiceException(422, "仅 CREATED 波次可确认分配（当前 " + wave.getStatus() + "）");
        }
        Long lines = waveLineDao.selectCount(new LambdaQueryWrapper<InvWaveLine>()
                .eq(InvWaveLine::getWaveId, waveId));
        if (lines == null || lines == 0) {
            throw new ServiceException(422, "尚未生成分配（请先执行波次分配）");
        }
        // C-4.4-08 前置（design D4）：LOCKED 行未解锁 → 422
        Long locked = waveLineDao.selectCount(new LambdaQueryWrapper<InvWaveLine>()
                .eq(InvWaveLine::getWaveId, waveId)
                .eq(InvWaveLine::getLockFlag, "1"));
        if (locked != null && locked > 0) {
            throw new ServiceException(422, "存在改批审批中的行（LOCKED），审批完成前不可确认分配（C-4.4-08）");
        }
        InvWave done = transition(waveId, InvWave.ST_CREATED, InvWave.ST_ALLOCATED);
        // FR-4.4-7-3：生成恰好 1 张 WAVE 合并任务（5.1 接入）
        waveTaskOps.createFromWave(waveId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("wave", done);
        return out;
    }

    /** 一次分配尝试：active 单据 → 聚合推荐 → 按创建序逐单逐行切分；取不满的单进 shortIds */
    private PlanAttempt buildPlan(List<InvWaveDoc> active, String waveId) {
        PlanAttempt r = new PlanAttempt();
        // 聚合需求 (wh,item) → 总量；行需求按单据创建序（波次归属单号序近似——创建序以发财单号为准）
        Map<String, java.math.BigDecimal> demand = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();   // 行需求（保持处理序）
        Set<String> soIds = new HashSet<>();
        Map<String, String> shipCreate = new HashMap<>();
        for (InvWaveDoc d : active) {
            Shipment ship = shipmentDao.selectById(d.getShipId());
            if (ship == null || !Shipment.ST_DRAFT.equals(ship.getStatus())) {
                // 非 DRAFT（并发过账等）→ 视为该单不可分配（拆出处理）
                r.shortIds.add(d.getId());
                continue;
            }
            shipCreate.put(ship.getId(), nullSafe(ship.getShipNo()));
            for (com.erp.entity.sd.ShipmentLine sl : shipmentLineDao.selectList(
                    new LambdaQueryWrapper<com.erp.entity.sd.ShipmentLine>()
                            .eq(com.erp.entity.sd.ShipmentLine::getShipId, ship.getId())
                            .ne(com.erp.entity.sd.ShipmentLine::getLineStatus,
                                    com.erp.entity.sd.ShipmentLine.LS_CANCELLED)
                            .orderByAsc(com.erp.entity.sd.ShipmentLine::getLineNo))) {
                if (sl.getQty() == null || sl.getQty().signum() <= 0) {
                    continue;
                }
                String key = sl.getWarehouseCode() + "|" + sl.getItemCode();
                demand.merge(key, sl.getQty(), java.math.BigDecimal::add);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("shipId", ship.getId());
                row.put("shipLineId", sl.getId());
                row.put("shipLineNo", sl.getLineNo());
                row.put("warehouseCode", sl.getWarehouseCode());
                row.put("itemCode", sl.getItemCode());
                row.put("itemName", sl.getItemName());
                row.put("qty", sl.getQty());
                row.put("docId", d.getId());
                rows.add(row);
                if (sl.getSoId() != null && !sl.getSoId().trim().isEmpty()) {
                    soIds.add(sl.getSoId());
                }
            }
        }
        if (!r.shortIds.isEmpty()) {
            return r;   // 有单据状态失效 → 整轮重试（该单进 shortIds 拆出）
        }

        // 逐 (wh,item) 波次级推荐（共享预算，排除波次自身预留）
        Map<String, List<Map<String, Object>>> pool = new LinkedHashMap<>();   // key → 展平 (batch,bin,qty)
        boolean anyUnsatisfied = false;
        for (Map.Entry<String, java.math.BigDecimal> e : demand.entrySet()) {
            String[] wh = e.getKey().split("\\|", 2);
            Map<String, Object> rec = recommendService.recommendForWave(
                    wh[0], wh[1], e.getValue(), true, new ArrayList<>(soIds));
            if (!Boolean.TRUE.equals(rec.get("satisfied"))) {
                anyUnsatisfied = true;
            }
            List<Map<String, Object>> flat = new ArrayList<>();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> lines = (List<Map<String, Object>>) rec.get("lines");
            for (Map<String, Object> line : lines) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> bins = (List<Map<String, Object>>)
                        line.getOrDefault("bins", List.of());
                if (bins.isEmpty()) {
                    Map<String, Object> seg = new LinkedHashMap<>();
                    seg.put("batchNo", line.get("batchNo"));
                    seg.put("binCode", "");
                    seg.put("take", line.get("take"));
                    flat.add(seg);
                } else {
                    for (Map<String, Object> b : bins) {
                        Map<String, Object> seg = new LinkedHashMap<>();
                        seg.put("batchNo", line.get("batchNo"));
                        seg.put("binCode", b.get("binCode"));
                        seg.put("take", b.get("take"));
                        flat.add(seg);
                    }
                }
            }
            pool.put(e.getKey(), flat);
        }

        // 按单据创建序（单号升序）逐单逐行从池头切分
        rows.sort(Comparator.comparing((Map<String, Object> m)
                -> shipCreate.getOrDefault(str(m.get("shipId")), "")));
        Set<String> shortDocs = new HashSet<>();
        for (Map<String, Object> row : rows) {
            if (shortDocs.contains(str(row.get("docId")))) {
                continue;   // 同单已有行取不满 → 该单整单拆出，后续行不再分配
            }
            String key = str(row.get("warehouseCode")) + "|" + str(row.get("itemCode"));
            List<Map<String, Object>> segs = pool.computeIfAbsent(key, k -> new ArrayList<>());
            java.math.BigDecimal remain = (java.math.BigDecimal) row.get("qty");
            // 池空且总需求未满足 → 该单取不满（总池不足时后到单拆出，先到先得）
            for (Map<String, Object> seg : segs) {
                if (remain.signum() <= 0) {
                    break;
                }
                java.math.BigDecimal availSeg = (java.math.BigDecimal) seg.get("take");
                if (availSeg.signum() <= 0) {
                    continue;
                }
                java.math.BigDecimal take = availSeg.min(remain);
                Map<String, Object> a = new LinkedHashMap<>(row);
                a.put("batchNo", seg.get("batchNo"));
                a.put("binCode", seg.get("binCode"));
                a.put("qty", take);
                r.plan.add(a);
                seg.put("take", availSeg.subtract(take));
                remain = remain.subtract(take);
            }
            if (remain.signum() > 0) {
                // 该单任一行取不满 → 整单拆出，回滚其已切分部分（下一轮重算）
                shortDocs.add(str(row.get("docId")));
            }
        }
        if (!shortDocs.isEmpty()) {
            // 整轮放弃该尝试：shortIds 返回触发外层拆出重算（plan 丢弃）
            r.plan.clear();
            r.shortIds.addAll(shortDocs);
            return r;
        }
        if (anyUnsatisfied && r.plan.stream().map(a -> str(a.get("docId")))
                .anyMatch(Objects::nonNull)) {
            // 推荐层已宣告不满足但行级恰好切完（预留回加边缘）→ 不拆，按 plan 落库
            log.debug("wave {} plan satisfied by lines despite rec gap", waveId);
        }
        return r;
    }

    private static final class PlanAttempt {
        final List<Map<String, Object>> plan = new ArrayList<>();
        final Set<String> shortIds = new HashSet<>();
    }

    /** 发货行回写（首段 batch/bin，展示口径；过账按分配行展开） */
    private void writeBackShipLine(String shipLineId, String batchNo, String binCode) {
        com.erp.entity.sd.ShipmentLine sl = shipmentLineDao.selectById(shipLineId);
        if (sl == null) {
            return;
        }
        sl.setBatchNo(batchNo);
        sl.setBinCode(binCode);
        if (shipmentLineDao.updateById(sl) == 0) {
            throw new ServiceException(409, "发货行回写冲突，请刷新后重试：行 " + sl.getLineNo());
        }
    }

    @Override
    @Transactional
    public Map<String, Object> adjust(String waveId, String lineId, String field,
                                      String newValue, String reason) {
        requireWrite("波次改批");
        InvWave wave = require(waveId);
        // 改批发生在分配之后确认之前（CREATED 有分配行）或 ALLOCATED 后（冻结拣货语义）：
        // spec 场景为「分配行存在」即可触发，终态（CLOSED/CANCELLED）不可改
        if (InvWave.ST_CLOSED.equals(wave.getStatus())
                || InvWave.ST_CANCELLED.equals(wave.getStatus())) {
            throw new ServiceException(422, "终态波次不可改批（当前 " + wave.getStatus() + "）");
        }
        if (!InvWaveLine.FIELD_BATCH.equals(field)
                && !InvWaveLine.FIELD_BIN.equals(field)) {
            throw new ServiceException(422, "调整字段仅支持 BATCH/BIN");
        }
        if (newValue == null || newValue.trim().isEmpty()) {
            throw new ServiceException(422, "新批次/仓位必填");
        }
        if (reason == null || reason.trim().isEmpty()) {
            throw new ServiceException(422, "调整原因必填（C-4.4-08 留痕）");
        }
        InvWaveLine line = waveLineDao.selectById(lineId);
        if (line == null || !waveId.equals(line.getWaveId())) {
            throw new ServiceException(404, "分配行不存在：" + lineId);
        }
        if ("1".equals(line.getLockFlag())) {
            throw new ServiceException(422, "该行已有未决改批（LOCKED），不可重复提交");
        }
        // 新批次校验（BATCH）：该物料台账存在且未效期锁定（锁定禁出，改入无意义）
        if (InvWaveLine.FIELD_BATCH.equals(field)) {
            com.erp.entity.inv.InvBatch lb = batchDao.selectOne(
                    new LambdaQueryWrapper<com.erp.entity.inv.InvBatch>()
                            .eq(com.erp.entity.inv.InvBatch::getItemCode, line.getItemCode())
                            .eq(com.erp.entity.inv.InvBatch::getBatchNo, newValue.trim())
                            .last("LIMIT 1"));
            if (lb == null) {
                throw new ServiceException(422, "批次不存在：" + newValue.trim());
            }
            if ("1".equals(str(lb.getExpiryLockFlag()))) {
                throw new ServiceException(422, "批次已效期锁定，不可改入：" + newValue.trim());
            }
        }
        // C-0-03 同人闭环第一道（design D4）：发起人排除后 ROLE_WAREHOUSE 无他人 → 422
        String applicant = com.erp.util.SecurityUtils.getCurrentUserId();
        Long others = warehouseMemberCount(applicant);
        if (others == null || others == 0) {
            throw new ServiceException(422, "无法审批：当前仓库主管角色除发起人外无其他成员"
                    + "（C-0-03 禁同人闭环，请先增加第二名仓库主管）");
        }

        String oldVal = InvWaveLine.FIELD_BATCH.equals(field)
                ? line.getBatchNo() : line.getBinCode();
        InvWaveAdjust adj = new InvWaveAdjust();
        adj.setId(uuid());
        adj.setWaveId(waveId);
        adj.setLineId(lineId);
        adj.setField(field);
        adj.setOldValue(oldVal);
        adj.setNewValue(newValue.trim());
        adj.setReason(reason.trim());
        adj.setStatus(InvWaveAdjust.ST_PENDING);
        adj.setAdjustBy(applicant);
        adjustDao.insert(adj);

        // 行 LOCKED（冻结拣货；C-4.4-08）
        InvWaveLine lockPatch = new InvWaveLine();
        lockPatch.setId(lineId);
        lockPatch.setLockFlag("1");
        lockPatch.setVerNo(line.getVerNo());
        if (waveLineDao.updateById(lockPatch) == 0) {
            throw new ServiceException(409, "分配行更新冲突，请刷新后重试");
        }

        // 提交审批底座（单节点 ROLE_WAREHOUSE，bizId=调整记录 ID，design D4）
        com.erp.entity.system.ApprovalInstance inst = approvalEngine.submit(
                "WaveAdjust", adj.getId(),
                "波次改批审批：" + wave.getWaveNo() + " " + field + " → " + newValue.trim(),
                null,
                List.of(List.of(com.erp.service.approval.ApprovalNodeSpec
                        .sign("ROLE_WAREHOUSE", "仓库主管审批"))));
        InvWaveAdjust apatch = new InvWaveAdjust();
        apatch.setId(adj.getId());
        apatch.setApprId(inst.getId());
        apatch.setVerNo(adj.getVerNo());
        adjustDao.updateById(apatch);
        log.info("wave {} adjust submitted: line={} {} {} -> {} appr={}",
                wave.getWaveNo(), lineId, field, oldVal, newValue.trim(), inst.getApprNo());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("adjustId", adj.getId());
        out.put("apprNo", inst.getApprNo());
        out.put("lockFlag", "1");
        return out;
    }

    /** ROLE_WAREHOUSE 其他成员数（排除发起人；同人防闭环第一道） */
    private Long warehouseMemberCount(String excludeUser) {
        // getCurrentUserId 返回用户 ID（如 user-wh-smoke）——ID 与 USERNAME 双条件排除，
        // 保证「发起人自己」无论以哪种表示都不计入他人
        String who = excludeUser == null ? "" : excludeUser;
        Long cnt = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT u.ID) FROM erp_admin_user u "
                        + "JOIN erp_admin_user_role ur ON ur.USER_ID = u.ID "
                        + "JOIN erp_admin_role r ON r.ID = ur.ROLE_ID "
                        + "WHERE r.ROLE_CODE = 'ROLE_WAREHOUSE' AND u.DEL_FLAG = '0' "
                        + "AND u.STATUS = '1' AND u.ID <> ? AND u.USERNAME <> ?",
                Long.class, who, who);
        return cnt;
    }

    // ==================== 6.1 分播复核（FR-4.4-7-5） ====================

    @Override
    @Transactional
    public Map<String, Object> sortConfirm(String waveId, String shipId,
                                           List<Map<String, Object>> actualLines) {
        requireWrite("分播复核");
        InvWave wave = require(waveId);
        InvWaveDoc doc = requireDoc(waveId, shipId);
        if (!InvWaveDoc.BIND_BOUND.equals(doc.getBindStatus())) {
            throw new ServiceException(422, "该单已拆出波次（UNBOUND），不参与分播");
        }
        if (!InvWave.ST_SORTING.equals(wave.getStatus())) {
            throw new ServiceException(422, "仅 SORTING（拣货完成）波次可分播，当前 "
                    + wave.getStatus());
        }
        if (actualLines == null || actualLines.isEmpty()) {
            throw new ServiceException(422, "实点明细必填");
        }

        // 基准 = 该单 BOUND 分配段按 (item, batch) 汇总
        Map<String, BigDecimal> expect = new LinkedHashMap<>();
        BigDecimal expectTotal = BigDecimal.ZERO;
        for (InvWaveLine seg : waveLineDao.selectList(
                new LambdaQueryWrapper<InvWaveLine>()
                        .eq(InvWaveLine::getWaveId, waveId)
                        .eq(InvWaveLine::getShipId, shipId))) {
            expect.merge(key(seg.getItemCode(), seg.getBatchNo()), seg.getQty(),
                    BigDecimal::add);
            expectTotal = expectTotal.add(seg.getQty());
        }
        if (expect.isEmpty()) {
            throw new ServiceException(422, "该单无分配段（未分配或已拆出）");
        }
        // 实点汇总
        Map<String, BigDecimal> actual = new LinkedHashMap<>();
        BigDecimal actualTotal = BigDecimal.ZERO;
        for (Map<String, Object> m : actualLines) {
            String item = str(m.get("itemCode"));
            String batch = str(m.get("batchNo"));
            BigDecimal q = m.get("qty") instanceof BigDecimal b ? b
                    : new BigDecimal(str(m.get("qty")));
            if (item.isEmpty() || q.signum() < 0) {
                throw new ServiceException(422, "实点行需物料与非负数量");
            }
            actual.merge(key(item, batch), q, BigDecimal::add);
            actualTotal = actualTotal.add(q);
        }

        // 比对：品种/批次/数量 —— 任一不平 → WAVE_SORT 差异（幂等），不置 PASSED
        List<String> diffs = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        keys.addAll(expect.keySet());
        keys.addAll(actual.keySet());
        BigDecimal delta = actualTotal.subtract(expectTotal);
        for (String k : keys) {
            BigDecimal e = expect.getOrDefault(k, BigDecimal.ZERO);
            BigDecimal a = actual.getOrDefault(k, BigDecimal.ZERO);
            if (e.compareTo(a) != 0) {
                diffs.add(k + " 期望 " + strip(e) + " 实点 " + strip(a));
            }
        }
        if (!diffs.isEmpty()) {
            InvCheckDiff d = registerWaveSortDiff(wave, doc, expectTotal, actualTotal,
                    delta, String.join("；", diffs));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("passed", false);
            out.put("diffId", d.getId());
            out.put("diffDetails", diffs);
            out.put("message", "分播不平已登记 WAVE_SORT 差异，闭环前该订单禁止发运（C-4.4-06 口径）");
            return out;
        }

        // 平 → PASSED + 复核快照（装车比对基准）
        InvWaveDoc patch = new InvWaveDoc();
        patch.setId(doc.getId());
        patch.setSortStatus(InvWaveDoc.SORT_PASSED);
        patch.setSortResult(toJson(actual));
        patch.setSortBy(com.erp.util.SecurityUtils.getCurrentUserId());
        patch.setSortAt(LocalDateTime.now());
        patch.setVerNo(doc.getVerNo());
        if (waveDocDao.updateById(patch) == 0) {
            throw new ServiceException(409, "分播状态并发冲突，请刷新重试");
        }
        boolean allPassed = waveDocDao.selectCount(new LambdaQueryWrapper<InvWaveDoc>()
                .eq(InvWaveDoc::getWaveId, waveId)
                .eq(InvWaveDoc::getBindStatus, InvWaveDoc.BIND_BOUND)
                .ne(InvWaveDoc::getSortStatus, InvWaveDoc.SORT_PASSED)) == 0;
        if (allPassed) {
            doTransition(wave, InvWave.ST_STAGING);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("passed", true);
        out.put("sortStatus", InvWaveDoc.SORT_PASSED);
        out.put("waveStatus", wave.getStatus());
        return out;
    }

    /** WAVE_SORT 差异登记（幂等：同单已有 PENDING 复用；srcDocNo=订单号使门闩零改动） */
    private InvCheckDiff registerWaveSortDiff(InvWave wave, InvWaveDoc doc,
                                              BigDecimal expectQty, BigDecimal actualQty,
                                              BigDecimal delta, String details) {
        List<InvCheckDiff> exist = diffDao.selectList(
                new LambdaQueryWrapper<InvCheckDiff>()
                        .eq(InvCheckDiff::getDiffType, InvCheckDiff.T_WAVE_SORT)
                        .eq(InvCheckDiff::getStatus, InvCheckDiff.ST_PENDING)
                        .eq(InvCheckDiff::getSrcDocNo, doc.getShipNo()));
        if (!exist.isEmpty()) {
            return exist.get(0);
        }
        InvCheckDiff d = new InvCheckDiff();
        d.setId(uuid());
        d.setDiffType(InvCheckDiff.T_WAVE_SORT);
        d.setSrcDocType("SALES_OUT");
        d.setSrcDocNo(doc.getShipNo());
        d.setWaveId(wave.getId());
        d.setWaveDocId(doc.getId());
        d.setDiffKind(delta.signum() == 0 ? InvCheckDiff.K_BATCH : InvCheckDiff.K_QTY);
        d.setItemCode("*");
        d.setExpectQty(expectQty);
        d.setActualQty(actualQty);
        d.setDeltaQty(delta);
        d.setDiffNote("分播不平：" + details);
        d.setStatus(InvCheckDiff.ST_PENDING);
        d.setCreateBy(com.erp.util.SecurityUtils.getCurrentUserId());
        diffDao.insert(d);
        log.info("wave {} sort diff registered: {} ({})", wave.getWaveNo(), doc.getShipNo(),
                details);
        return d;
    }

    // ==================== 6.2 装车确认（FR-4.4-7-6 / C-4.4-06） ====================

    @Override
    @Transactional
    public Map<String, Object> loadConfirm(String waveId, String shipId,
                                           List<Map<String, Object>> scannedLines) {
        requireWrite("装车确认");
        InvWave wave = require(waveId);
        InvWaveDoc doc = requireDoc(waveId, shipId);
        if (!InvWaveDoc.BIND_BOUND.equals(doc.getBindStatus())) {
            throw new ServiceException(422, "该单已拆出波次，不参与装车");
        }
        // 前置：分播通过，或差异已闭环（闭环后视为可装车，spec：闭环恢复可发运）
        boolean sortOk = InvWaveDoc.SORT_PASSED.equals(doc.getSortStatus());
        boolean diffClosed = !sortOk && noPendingSortDiff(doc.getShipNo());
        if (!sortOk && !diffClosed) {
            throw new ServiceException(422, "该订单尚未分播复核通过且存在未闭环差异，禁止装车："
                    + doc.getShipNo());
        }
        if (InvWave.ST_SORTING.equals(wave.getStatus())) {
            // 兜底推进：分播全过或差异全闭环 → SORTING→STAGING（差异闭环后首次装车触发）
            maybeAdvanceToStaging(wave);
        }
        if (!InvWave.ST_STAGING.equals(require(waveId).getStatus())) {
            throw new ServiceException(422, "波次未进入装车阶段（STAGING，需全部订单分播就绪），当前 "
                    + require(waveId).getStatus());
        }

        // 比对：扫描件数/批次 vs 分播复核快照（不一致 422 阻断该单，不落差异单——纯校验）
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshot = doc.getSortResult() == null ? Map.of()
                : (Map<String, Object>) (Object) fromJson(doc.getSortResult());
        if (snapshot == null || snapshot.isEmpty()) {
            if (!diffClosed) {
                throw new ServiceException(422, "缺分播复核快照，无法比对装车明细");
            }
            // 差异闭环路径：无快照 → 以扫描明细为装车基准（差异单即闭环记录）
            snapshot = new LinkedHashMap<>();
        }
        Map<String, BigDecimal> scanned = new LinkedHashMap<>();
        BigDecimal scanTotal = BigDecimal.ZERO;
        for (Map<String, Object> m : scannedLines == null ? List.<Map<String, Object>>of()
                : scannedLines) {
            String k = key(str(m.get("itemCode")), str(m.get("batchNo")));
            BigDecimal q = m.get("qty") instanceof BigDecimal b ? b
                    : new BigDecimal(str(m.get("qty")));
            scanned.merge(k, q, BigDecimal::add);
            scanTotal = scanTotal.add(q);
        }
        if (scanned.isEmpty()) {
            throw new ServiceException(422, "装车扫描明细必填");
        }
        if (!snapshot.isEmpty()) {
            List<String> mismatch = new ArrayList<>();
            Set<String> keys = new HashSet<>();
            keys.addAll(snapshot.keySet());
            keys.addAll(scanned.keySet());
            for (String k : keys) {
                BigDecimal e = dec(snapshot.get(k));
                BigDecimal a = scanned.getOrDefault(k, BigDecimal.ZERO);
                if (e.compareTo(a) != 0) {
                    mismatch.add(k + " 复核 " + strip(e) + " 装车扫描 " + strip(a));
                }
            }
            if (!mismatch.isEmpty()) {
                // C-4.4-06 L1：阻断该订单发运，波次内其他订单不受影响；纯校验不落差异单
                throw new ServiceException(422, "装车明细与分播复核结果不一致，阻断该订单发运"
                        + "（C-4.4-06）：" + String.join("；", mismatch));
            }
        }

        InvWaveDoc patch = new InvWaveDoc();
        patch.setId(doc.getId());
        patch.setLoadStatus(InvWaveDoc.LOAD_LOADED);
        patch.setLoadBy(com.erp.util.SecurityUtils.getCurrentUserId());
        patch.setLoadAt(LocalDateTime.now());
        patch.setVerNo(doc.getVerNo());
        if (waveDocDao.updateById(patch) == 0) {
            throw new ServiceException(409, "装车状态并发冲突，请刷新重试");
        }
        boolean allLoaded = waveDocDao.selectCount(new LambdaQueryWrapper<InvWaveDoc>()
                .eq(InvWaveDoc::getWaveId, waveId)
                .eq(InvWaveDoc::getBindStatus, InvWaveDoc.BIND_BOUND)
                .ne(InvWaveDoc::getLoadStatus, InvWaveDoc.LOAD_LOADED)) == 0;
        if (allLoaded) {
            doTransition(require(waveId), InvWave.ST_SHIPPING);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("loaded", true);
        out.put("scannedTotal", scanTotal);
        out.put("waveStatus", require(waveId).getStatus());
        return out;
    }

    // ==================== 6.3 发运确认（FR-4.4-7-7 / BR-4.4-46） ====================

    @Override
    // 无事务：逐单独立事务过账（BR-4.4-46 单失败仅回滚该订单）
    public Map<String, Object> shipConfirm(String waveId) {
        requireWrite("发运确认");
        InvWave wave = require(waveId);
        if (!InvWave.ST_SHIPPING.equals(wave.getStatus())) {
            throw new ServiceException(422, "仅 SHIPPING 波次可发运，当前 " + wave.getStatus());
        }
        List<InvWaveDoc> bound = waveDocDao.selectList(new LambdaQueryWrapper<InvWaveDoc>()
                .eq(InvWaveDoc::getWaveId, waveId)
                .eq(InvWaveDoc::getBindStatus, InvWaveDoc.BIND_BOUND)
                .orderByAsc(InvWaveDoc::getShipNo));
        List<Map<String, Object>> results = new ArrayList<>();
        int ok = 0;
        int fail = 0;
        for (InvWaveDoc doc : bound) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("shipNo", doc.getShipNo());
            try {
                shipmentService.post(doc.getShipId());
                markShipErr(doc, null);
                row.put("posted", true);
                ok++;
            } catch (RuntimeException e) {
                markShipErr(doc, e.getMessage());
                row.put("posted", false);
                row.put("error", e.getMessage());
                fail++;
            }
            results.add(row);
        }
        boolean allPosted = fail == 0 && !bound.isEmpty();
        if (allPosted) {
            doTransition(require(waveId), InvWave.ST_CLOSED);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("posted", ok);
        out.put("failed", fail);
        out.put("results", results);
        out.put("waveStatus", require(waveId).getStatus());
        out.put("message", allPosted ? "全部订单过账完成，波次关闭"
                : fail + " 单过账失败（已记录，可单独重试，BR-4.4-46）");
        return out;
    }

    @Override
    // 无事务：单订单独立过账
    public Map<String, Object> retryShip(String waveId, String shipId) {
        requireWrite("失败订单重试");
        InvWaveDoc doc = requireDoc(waveId, shipId);
        Shipment ship = shipmentDao.selectById(doc.getShipId());
        if (ship == null) {
            throw new ServiceException(404, "发货单不存在：" + doc.getShipId());
        }
        if (Shipment.ST_POSTED.equals(ship.getStatus())) {
            markShipErr(doc, null);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("posted", true);
            out.put("shipNo", doc.getShipNo());
            out.put("message", "该订单已过账（幂等）");
            return out;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shipNo", doc.getShipNo());
        try {
            shipmentService.post(doc.getShipId());
            markShipErr(doc, null);
            out.put("posted", true);
        } catch (RuntimeException e) {
            markShipErr(doc, e.getMessage());
            out.put("posted", false);
            out.put("error", e.getMessage());
        }
        // 单订单成功 → 检查全 POSTED → CLOSED（onDocPosted 同口径）
        onDocPosted(doc.getShipNo());
        out.put("waveStatus", require(waveId).getStatus());
        return out;
    }

    // ---------- 6.x 内部 ----------

    private InvWaveDoc requireDoc(String waveId, String shipId) {
        InvWaveDoc doc = waveDocDao.selectOne(new LambdaQueryWrapper<InvWaveDoc>()
                .eq(InvWaveDoc::getWaveId, waveId)
                .eq(InvWaveDoc::getShipId, shipId)
                .last("LIMIT 1"));
        if (doc == null) {
            throw new ServiceException(404, "波次单据归属不存在：" + shipId);
        }
        return doc;
    }

    private void markShipErr(InvWaveDoc doc, String err) {
        waveDocDao.update(null, new LambdaUpdateWrapper<InvWaveDoc>()
                .eq(InvWaveDoc::getId, doc.getId())
                .set(InvWaveDoc::getShipErr, err)
                .setSql("VER_NO = VER_NO + 1"));
    }

    private boolean noPendingSortDiff(String shipNo) {
        Long cnt = diffDao.selectCount(new LambdaQueryWrapper<InvCheckDiff>()
                .eq(InvCheckDiff::getDiffType, InvCheckDiff.T_WAVE_SORT)
                .eq(InvCheckDiff::getStatus, InvCheckDiff.ST_PENDING)
                .eq(InvCheckDiff::getSrcDocNo, shipNo));
        return cnt == null || cnt == 0;
    }

    /** 全 BOUND 单分播就绪（PASSED 或差异闭环）→ 推进 SORTING→STAGING */
    private void maybeAdvanceToStaging(InvWave wave) {
        List<InvWaveDoc> bound = waveDocDao.selectList(new LambdaQueryWrapper<InvWaveDoc>()
                .eq(InvWaveDoc::getWaveId, wave.getId())
                .eq(InvWaveDoc::getBindStatus, InvWaveDoc.BIND_BOUND));
        for (InvWaveDoc d : bound) {
            if (!InvWaveDoc.SORT_PASSED.equals(d.getSortStatus())
                    && !noPendingSortDiff(d.getShipNo())) {
                return;
            }
        }
        if (!bound.isEmpty()) {
            doTransition(wave, InvWave.ST_STAGING);
        }
    }

    private static String key(String item, String batch) {
        return item + "|" + (batch == null ? "" : batch.trim());
    }

    private static BigDecimal dec(Object o) {
        return o instanceof BigDecimal b ? b
                : new BigDecimal(o == null ? "0" : String.valueOf(o));
    }

    private static String strip(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }

    private String toJson(Object v) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(v);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ServiceException(500, "快照序列化失败：" + e.getMessage());
        }
    }

    private Object fromJson(String v) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(v, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 事件连带（design D5） ====================

    @Override
    public void onTaskStatus(String waveNo, String taskStatus) {
        InvWave wave = waveDao.selectOne(new LambdaQueryWrapper<InvWave>()
                .eq(InvWave::getWaveNo, waveNo).last("LIMIT 1"));
        if (wave == null) {
            return;
        }
        if ("PICKING".equals(taskStatus) && InvWave.ST_ALLOCATED.equals(wave.getStatus())) {
            transitionQuiet(wave, InvWave.ST_PICKING);
        } else if (("PICKED".equals(taskStatus) || "DONE".equals(taskStatus)
                || "REVIEWING".equals(taskStatus))
                && InvWave.ST_PICKING.equals(wave.getStatus())) {
            transitionQuiet(wave, InvWave.ST_SORTING);
        }
    }

    @Override
    public void onDocPosted(String shipNo) {
        List<InvWaveDoc> docs = waveDocDao.selectList(new LambdaQueryWrapper<InvWaveDoc>()
                .eq(InvWaveDoc::getShipNo, shipNo)
                .eq(InvWaveDoc::getBindStatus, InvWaveDoc.BIND_BOUND));
        if (docs.isEmpty()) {
            return;
        }
        InvWave wave = waveDao.selectById(docs.get(0).getWaveId());
        if (wave == null || !InvWave.ST_SHIPPING.equals(wave.getStatus())) {
            return;
        }
        List<InvWaveDoc> bound = waveDocDao.selectList(new LambdaQueryWrapper<InvWaveDoc>()
                .eq(InvWaveDoc::getWaveId, wave.getId())
                .eq(InvWaveDoc::getBindStatus, InvWaveDoc.BIND_BOUND));
        Set<String> ids = bound.stream().map(InvWaveDoc::getShipId)
                .collect(Collectors.toCollection(HashSet::new));
        List<Shipment> ships = ids.isEmpty() ? List.of()
                : shipmentDao.selectBatchIds(ids);
        boolean allPosted = ships.size() == bound.size() && ships.stream()
                .allMatch(x -> Shipment.ST_POSTED.equals(x.getStatus()));
        if (allPosted) {
            doTransition(wave, InvWave.ST_CLOSED);
            log.info("wave {} CLOSED (all {} docs posted)", wave.getWaveNo(), ships.size());
        }
    }

    private void transitionQuiet(InvWave wave, String to) {
        doTransition(wave, to);
    }

    /** 系统事件迁移（连带钩子/发运内部用，免角色校验）：状态守卫同 transition，冲突忽略 */
    private void doTransition(InvWave wave, String to) {
        Set<String> allowed = TRANSITIONS.getOrDefault(wave.getStatus(), Set.of());
        if (!allowed.contains(to)) {
            log.debug("wave {} transition {} skipped (from={})", wave.getWaveNo(), to, wave.getStatus());
            return;
        }
        InvWave patch = new InvWave();
        patch.setId(wave.getId());
        patch.setStatus(to);
        patch.setVerNo(wave.getVerNo());
        stampTimes(patch, to);
        if (waveDao.updateById(patch) == 0) {
            log.debug("wave {} transition {} conflict (stale verNo)", wave.getWaveNo(), to);
            return;
        }
        wave.setStatus(to);
        log.info("wave {} -> {}", wave.getWaveNo(), to);
    }

    // ==================== 内部工具 ====================

    /** WAVE 任务联动（5.1 接入后由 Spring 注入实现；构造期自引用安全） */
    interface WaveTaskOps {
        void cancelIfExists(String waveNo, String reason);

        void onTaskStatus(String waveNo, String taskStatus);

        /** 确认分配后生成 WAVE 合并任务（5.1 接入；默认无操作） */
        void createFromWave(String waveId);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WaveTaskOps waveTaskOps = new WaveTaskOps() {
        @Override
        public void cancelIfExists(String waveNo, String reason) {
            // 默认无操作：任务组（5.1）以 @Component 实现覆盖
        }

        @Override
        public void onTaskStatus(String waveNo, String taskStatus) {
            // 默认无操作
        }

        @Override
        public void createFromWave(String waveId) {
            // 默认无操作：任务组（5.1）以 @Component 实现覆盖
        }
    };

    private InvWave require(String waveId) {
        InvWave w = waveDao.selectById(waveId);
        if (w == null) {
            throw new ServiceException(404, "波次不存在：" + waveId);
        }
        return w;
    }

    private String nextWaveNo() {
        String prefix = "WV" + LocalDate.now().format(DAY);
        Long cnt = waveDao.selectCount(new LambdaQueryWrapper<InvWave>()
                .likeRight(InvWave::getWaveNo, prefix));
        long seq = (cnt == null ? 0 : cnt) + 1;
        return prefix + String.format("%04d", seq);
    }

    private void requireWrite(String action) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        List<String> roles = new ArrayList<>();
        auth.getAuthorities().forEach(a -> roles.add(a.getAuthority()));
        if (roles.stream().anyMatch(r -> "ROLE_ADMIN".equalsIgnoreCase(r)
                || "ROLE_WAREHOUSE".equalsIgnoreCase(r))) {
            return;
        }
        throw new ServiceException(403, action + "：需要仓库主管或管理员角色");
    }

    private static boolean hasText(String v) {
        return v != null && !v.trim().isEmpty();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String nullSafe(String v) {
        return v == null ? "" : v;
    }

    private static LocalDateTime nullSafe(LocalDateTime v) {
        return v == null ? LocalDateTime.MIN : v;
    }

    private static String uuid() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }
}
