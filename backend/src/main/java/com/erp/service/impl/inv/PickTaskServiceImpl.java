package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.inv.ScrapOrderDao;
import com.erp.dao.inv.ScrapOrderLineDao;
import com.erp.dao.inv.TransferOrderDao;
import com.erp.dao.inv.TransferOrderLineDao;
import com.erp.dao.inv.PickTaskDao;
import com.erp.dao.inv.PickTaskLineDao;
import com.erp.dao.sd.ShipmentDao;
import com.erp.dao.sd.ShipmentLineDao;
import com.erp.dao.vmi.MaterialIssueDao;
import com.erp.dao.vmi.MaterialIssueLineDao;
import com.erp.entity.inv.InvScrapOrder;
import com.erp.entity.inv.InvScrapOrderLine;
import com.erp.entity.inv.InvTransferOrder;
import com.erp.entity.inv.InvTransferOrderLine;
import com.erp.entity.inv.PickTask;
import com.erp.entity.inv.PickTaskLine;
import com.erp.entity.sd.Shipment;
import com.erp.entity.sd.ShipmentLine;
import com.erp.entity.vmi.MaterialIssue;
import com.erp.entity.vmi.MaterialIssueLine;
import com.erp.service.inv.PickTaskService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 拣货任务实现（4.7.1，spec picking-review；design D1/D3）：
 * createFromDoc = 锁定读幂等（活跃任务同步行，仅 CANCELLED 时重建）+ 状态机 CAS（非法迁移 422、失配 409）。
 */
@Slf4j
@Service
public class PickTaskServiceImpl implements PickTaskService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 合法迁移表（design D3）：from → 允许 to */
    private static final Map<String, Set<String>> TRANSITIONS = new HashMap<>();

    static {
        TRANSITIONS.put(PickTask.ST_CREATED, Set.of(
                PickTask.ST_PICKING, PickTask.ST_CANCELLED));
        TRANSITIONS.put(PickTask.ST_PICKING, Set.of(
                PickTask.ST_PICKED, PickTask.ST_DIFF_PENDING, PickTask.ST_QUALITY_PENDING));
        TRANSITIONS.put(PickTask.ST_PICKED, Set.of(
                PickTask.ST_REVIEWING, PickTask.ST_DIFF_PENDING, PickTask.ST_QUALITY_PENDING));
        TRANSITIONS.put(PickTask.ST_REVIEWING, Set.of(
                PickTask.ST_DONE, PickTask.ST_PICKING, PickTask.ST_DIFF_PENDING,
                PickTask.ST_QUALITY_PENDING));
        // DIFF_PENDING 闭环回迁：有未复核行→回 PICKING，全部已复核→进 DONE
        TRANSITIONS.put(PickTask.ST_DIFF_PENDING, Set.of(
                PickTask.ST_PICKING, PickTask.ST_DONE));
        // QUALITY_PENDING 闭环回主链（冻结审批/质量处置完成后）
        TRANSITIONS.put(PickTask.ST_QUALITY_PENDING, Set.of(
                PickTask.ST_PICKING, PickTask.ST_DONE));
        TRANSITIONS.put(PickTask.ST_DONE, Set.of(PickTask.ST_COMPLETED));
        TRANSITIONS.put(PickTask.ST_COMPLETED, Set.of());
        TRANSITIONS.put(PickTask.ST_CANCELLED, Set.of());
    }

    private final PickTaskDao taskDao;
    private final PickTaskLineDao lineDao;
    private final ShipmentDao shipmentDao;
    private final ShipmentLineDao shipmentLineDao;
    private final MaterialIssueDao materialIssueDao;
    private final MaterialIssueLineDao materialIssueLineDao;
    private final com.erp.dao.inv.TransferOrderDao transferOrderDao;
    private final com.erp.dao.inv.TransferOrderLineDao transferOrderLineDao;
    private final com.erp.dao.inv.ScrapOrderDao scrapOrderDao;
    private final com.erp.dao.inv.ScrapOrderLineDao scrapOrderLineDao;

    public PickTaskServiceImpl(PickTaskDao taskDao, PickTaskLineDao lineDao,
                               ShipmentDao shipmentDao, ShipmentLineDao shipmentLineDao,
                               MaterialIssueDao materialIssueDao,
                               MaterialIssueLineDao materialIssueLineDao,
                               com.erp.dao.inv.TransferOrderDao transferOrderDao,
                               com.erp.dao.inv.TransferOrderLineDao transferOrderLineDao,
                               com.erp.dao.inv.ScrapOrderDao scrapOrderDao,
                               com.erp.dao.inv.ScrapOrderLineDao scrapOrderLineDao,
                               @org.springframework.context.annotation.Lazy
                               com.erp.service.inv.WaveService waveService,
                               com.erp.dao.inv.InvWaveDao waveDao,
                               com.erp.dao.inv.InvWaveLineDao waveLineDao,
                               com.erp.dao.inv.InvWaveDocDao waveDocDao) {
        this.taskDao = taskDao;
        this.lineDao = lineDao;
        this.shipmentDao = shipmentDao;
        this.shipmentLineDao = shipmentLineDao;
        this.materialIssueDao = materialIssueDao;
        this.materialIssueLineDao = materialIssueLineDao;
        this.transferOrderDao = transferOrderDao;
        this.transferOrderLineDao = transferOrderLineDao;
        this.scrapOrderDao = scrapOrderDao;
        this.scrapOrderLineDao = scrapOrderLineDao;
        this.waveService = waveService;
        this.waveDao = waveDao;
        this.waveLineDao = waveLineDao;
        this.waveDocDao = waveDocDao;
    }

    /** 波次连带（@Lazy 打破 PickTask→Wave→Bridge→PickTask 构造环，spec wave-management design D5） */
    private final com.erp.service.inv.WaveService waveService;

    private final com.erp.dao.inv.InvWaveDao waveDao;
    private final com.erp.dao.inv.InvWaveLineDao waveLineDao;
    private final com.erp.dao.inv.InvWaveDocDao waveDocDao;

    // ---------- 行归一装载（四类单据 → 统一行结构） ----------

    private record NormLine(String srcLineId, int lineNo, String itemCode, String itemName,
                            String wh, String batchNo, String binCode, BigDecimal qty) {
    }

    private record DocCtx(String docId, String docNo, String status, List<NormLine> lines) {
    }

    private DocCtx loadDoc(String type, String docNo) {
        if (docNo == null || docNo.trim().isEmpty()) {
            throw new ServiceException(422, "单据号必填");
        }
        String no = docNo.trim();
        switch (type) {
            case "SALES_OUT" -> {
                Shipment s = shipmentDao.selectOne(new LambdaQueryWrapper<Shipment>()
                        .eq(Shipment::getShipNo, no).last("LIMIT 1"));
                if (s == null) {
                    throw new ServiceException(404, "发货单不存在：" + no);
                }
                List<NormLine> lines = new ArrayList<>();
                for (ShipmentLine l : shipmentLineDao.selectList(
                        new LambdaQueryWrapper<ShipmentLine>().eq(ShipmentLine::getShipId, s.getId())
                                .orderByAsc(ShipmentLine::getLineNo))) {
                    if (ShipmentLine.LS_CANCELLED.equals(l.getLineStatus())) {
                        continue;
                    }
                    lines.add(new NormLine(l.getId(), l.getLineNo(), l.getItemCode(),
                            l.getItemName(), l.getWarehouseCode(), l.getBatchNo(), l.getBinCode(),
                            l.getQty()));
                }
                return new DocCtx(s.getId(), no, s.getStatus(), lines);
            }
            case "MATERIAL_OUT" -> {
                MaterialIssue m = materialIssueDao.selectOne(new LambdaQueryWrapper<MaterialIssue>()
                        .eq(MaterialIssue::getIssueNo, no).last("LIMIT 1"));
                if (m == null) {
                    throw new ServiceException(404, "领料单不存在：" + no);
                }
                List<NormLine> lines = new ArrayList<>();
                for (MaterialIssueLine l : materialIssueLineDao.selectList(
                        new LambdaQueryWrapper<MaterialIssueLine>()
                                .eq(MaterialIssueLine::getIssueId, m.getId())
                                .orderByAsc(MaterialIssueLine::getLineNo))) {
                    lines.add(new NormLine(l.getId(), l.getLineNo(), l.getItemCode(),
                            l.getItemName(), com.erp.entity.inv.InvStock.DEFAULT_WH,
                            l.getBatchNo(), l.getBinCode(), l.getQty()));
                }
                return new DocCtx(m.getId(), no, m.getStatus(), lines);
            }
            case "TRANSFER_OUT" -> {
                InvTransferOrder o = transferOrderDao.selectOne(
                        new LambdaQueryWrapper<InvTransferOrder>()
                                .eq(InvTransferOrder::getTransferNo, no).last("LIMIT 1"));
                if (o == null) {
                    throw new ServiceException(404, "调拨单不存在：" + no);
                }
                List<NormLine> lines = new ArrayList<>();
                for (InvTransferOrderLine l : transferOrderLineDao.selectList(
                        new LambdaQueryWrapper<InvTransferOrderLine>()
                                .eq(InvTransferOrderLine::getOrderId, o.getId())
                                .orderByAsc(InvTransferOrderLine::getLineNo))) {
                    lines.add(new NormLine(l.getId(), l.getLineNo(), l.getItemCode(),
                            l.getItemName(), o.getOutWhCode(), l.getBatchNo(), l.getBinCode(),
                            l.getQty()));
                }
                return new DocCtx(o.getId(), no, o.getStatus(), lines);
            }
            case "SCRAP_OUT" -> {
                InvScrapOrder o = scrapOrderDao.selectOne(new LambdaQueryWrapper<InvScrapOrder>()
                        .eq(InvScrapOrder::getScrapNo, no).last("LIMIT 1"));
                if (o == null) {
                    throw new ServiceException(404, "报废单不存在：" + no);
                }
                List<NormLine> lines = new ArrayList<>();
                for (InvScrapOrderLine l : scrapOrderLineDao.selectList(
                        new LambdaQueryWrapper<InvScrapOrderLine>()
                                .eq(InvScrapOrderLine::getOrderId, o.getId())
                                .orderByAsc(InvScrapOrderLine::getLineNo))) {
                    lines.add(new NormLine(l.getId(), l.getLineNo(), l.getItemCode(),
                            l.getItemName(), o.getWarehouseCode(), l.getBatchNo(), l.getBinCode(),
                            l.getQty()));
                }
                return new DocCtx(o.getId(), no, o.getStatus(), lines);
            }
            default -> throw new ServiceException(422, "不支持的出库类型：" + type);
        }
    }

    // ---------- createFromDoc（design D1） ----------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PickTask createFromDoc(String srcType, String srcDocNo) {
        DocCtx doc = loadDoc(srcType, srcDocNo);

        // 锁定读幂等：FOR UPDATE 锁 (SRC_TYPE,SRC_DOC_NO) 索引段，防并发双建；
        // 活跃任务（非 CANCELLED）→ 同步行；仅 CANCELLED → 允许重建新任务
        List<PickTask> existing = taskDao.selectList(new LambdaQueryWrapper<PickTask>()
                .eq(PickTask::getSrcType, srcType)
                .eq(PickTask::getSrcDocNo, srcDocNo)
                .last("FOR UPDATE"));
        PickTask active = null;
        for (PickTask t : existing) {
            if (!PickTask.ST_CANCELLED.equals(t.getStatus())) {
                active = t;
                break;
            }
        }
        if (active != null) {
            syncLines(active, doc);
            return active;
        }

        PickTask task = new PickTask();
        task.setTaskNo(nextTaskNo());
        task.setSrcType(srcType);
        task.setSrcDocId(doc.docId());
        task.setSrcDocNo(doc.docNo());
        task.setStatus(PickTask.ST_CREATED);
        task.setCreateBy(SecurityUtils.getCurrentUserId());
        taskDao.insert(task);
        for (NormLine l : doc.lines()) {
            lineDao.insert(newLine(task.getId(), l));
        }
        log.info("pick task {} created: {} {} lines={}", task.getTaskNo(), srcType, srcDocNo,
                doc.lines().size());
        return task;
    }

    /** 活跃任务同步：行批次/仓位/数量取最新回写值（状态不动）；多退少补 */
    private void syncLines(PickTask task, DocCtx doc) {
        List<PickTaskLine> cur = lineDao.selectList(new LambdaQueryWrapper<PickTaskLine>()
                .eq(PickTaskLine::getTaskId, task.getId()));
        Map<Integer, PickTaskLine> byNo = new HashMap<>();
        for (PickTaskLine l : cur) {
            byNo.put(l.getLineNo(), l);
        }
        for (NormLine nl : doc.lines()) {
            PickTaskLine exist = byNo.remove(nl.lineNo());
            if (exist == null) {
                lineDao.insert(newLine(task.getId(), nl));
                continue;
            }
            // 已开拣（PICKED 及之后）的行不覆盖实拣结果，仅同步目标值
            lineDao.update(null, new LambdaUpdateWrapper<PickTaskLine>()
                    .eq(PickTaskLine::getId, exist.getId())
                    .set(PickTaskLine::getBatchNo, nl.batchNo())
                    .set(PickTaskLine::getBinCode, nl.binCode())
                    .set(PickTaskLine::getQty, nl.qty())
                    .set(PickTaskLine::getSrcLineId, nl.srcLineId()));
        }
        for (PickTaskLine orphan : byNo.values()) {
            lineDao.deleteById(orphan.getId());
        }
    }

    private PickTaskLine newLine(String taskId, NormLine nl) {
        PickTaskLine l = new PickTaskLine();
        l.setTaskId(taskId);
        l.setLineNo(nl.lineNo());
        l.setSrcLineId(nl.srcLineId());
        l.setItemCode(nl.itemCode());
        l.setItemName(nl.itemName());
        l.setWarehouseCode(nl.wh());
        l.setBatchNo(nl.batchNo());
        l.setBinCode(nl.binCode());
        l.setQty(nl.qty());
        l.setLineStatus(PickTaskLine.LS_PENDING);
        l.setScanOkFlag("0");
        l.setCreateBy(SecurityUtils.getCurrentUserId());
        return l;
    }

    private String nextTaskNo() {
        String prefix = "PT" + LocalDate.now().format(DAY) + "-";
        Long cnt = taskDao.selectCount(new LambdaQueryWrapper<PickTask>()
                .likeRight(PickTask::getTaskNo, prefix));
        int seq = (cnt == null ? 0 : cnt.intValue()) + 1;
        for (int i = 0; i < 5; i++) {
            String no = prefix + String.format("%06d", seq + i);
            Long hit = taskDao.selectCount(new LambdaQueryWrapper<PickTask>()
                    .eq(PickTask::getTaskNo, no));
            if (hit == 0) {
                return no;
            }
        }
        throw new ServiceException(409, "任务号生成冲突，请重试");
    }

    // ---------- 查询 ----------

    @Override
    public Map<String, Object> detail(String taskId) {
        PickTask task = require(taskId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("task", task);
        out.put("lines", lineDao.selectList(new LambdaQueryWrapper<PickTaskLine>()
                .eq(PickTaskLine::getTaskId, taskId)
                .orderByAsc(PickTaskLine::getLineNo)));
        return out;
    }

    @Override
    public Map<String, Object> page(String status, String srcType, String keyword,
                                    long current, long size) {
        Page<PickTask> p = taskDao.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<PickTask>()
                        .eq(notBlank(status), PickTask::getStatus, trimOrNull(status))
                        .eq(notBlank(srcType), PickTask::getSrcType, trimOrNull(srcType))
                        .and(notBlank(keyword), w -> w.like(PickTask::getTaskNo, keyword)
                                .or().like(PickTask::getSrcDocNo, keyword)
                                .or().like(PickTask::getPicker, keyword))
                        .orderByDesc(PickTask::getCreateDate));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", p.getRecords());
        out.put("total", p.getTotal());
        return out;
    }

    // ---------- 改派 / 作废 ----------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> assign(String taskId, String picker) {
        requireWrite("拣货任务改派");
        PickTask task = require(taskId);
        if (picker == null || picker.trim().isEmpty()) {
            throw new ServiceException(422, "拣货员必填");
        }
        if (!PickTask.ST_CREATED.equals(task.getStatus())
                && !PickTask.ST_PICKING.equals(task.getStatus())) {
            throw new ServiceException(422, "仅 CREATED/PICKING 状态可改派（当前 "
                    + task.getStatus() + "）");
        }
        PickTask upd = new PickTask();
        upd.setId(taskId);
        upd.setPrevPicker(task.getPicker());
        upd.setPicker(picker.trim());
        upd.setAssignBy(SecurityUtils.getCurrentUserId());
        upd.setAssignAt(LocalDateTime.now());
        upd.setVerNo(task.getVerNo());
        if (taskDao.updateById(upd) == 0) {
            throw new ServiceException(409, "任务更新冲突，请刷新重试");
        }
        log.info("pick task {} reassigned: {} -> {}", task.getTaskNo(), task.getPicker(),
                picker.trim());
        return Map.of("task", require(taskId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> cancel(String taskId, String reason) {
        requireWrite("拣货任务作废");
        PickTask task = require(taskId);
        if (reason == null || reason.trim().isEmpty()) {
            throw new ServiceException(422, "作废原因必填");
        }
        transition(taskId, PickTask.ST_CREATED, PickTask.ST_CANCELLED);
        taskDao.update(null, new LambdaUpdateWrapper<PickTask>()
                .eq(PickTask::getId, taskId)
                .set(PickTask::getCancelReason, reason.trim()));
        log.info("pick task {} cancelled: {}", task.getTaskNo(), reason.trim());
        return Map.of("task", require(taskId));
    }

    // ---------- 状态机（design D3） ----------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PickTask transition(String taskId, String fromStatus, String toStatus) {
        requireWrite("拣货任务状态变更");   // spec：拣货/复核动作服务层强制
        PickTask task = require(taskId);
        Set<String> allowed = TRANSITIONS.get(task.getStatus());
        if (allowed == null || !fromStatus.equals(task.getStatus())
                || !allowed.contains(toStatus)) {
            throw new ServiceException(422, "非法状态迁移：" + task.getStatus() + " → "
                    + toStatus + "（期望前置 " + fromStatus + "）");
        }
        // CAS：WHERE id + 当前状态（@Version 乐观锁由 updateById 承载）
        int rows = taskDao.update(null, new LambdaUpdateWrapper<PickTask>()
                .eq(PickTask::getId, taskId)
                .eq(PickTask::getStatus, fromStatus)
                .set(PickTask::getStatus, toStatus)
                .setSql("VER_NO = VER_NO + 1"));
        if (rows == 0) {
            throw new ServiceException(409, "任务状态并发冲突，请刷新重试："
                    + task.getTaskNo());
        }
        // WAVE 任务状态连带波次（spec wave-management design D5 事件驱动）：
        // 进 PICKING → 波次 PICKING；PICKED/DONE/REVIEWING → 波次 SORTING（拣毕进分播）
        if ("WAVE".equals(task.getSrcType())
                && (PickTask.ST_PICKING.equals(toStatus)
                || PickTask.ST_PICKED.equals(toStatus)
                || PickTask.ST_DONE.equals(toStatus)
                || PickTask.ST_REVIEWING.equals(toStatus))) {
            try {
                waveService.onTaskStatus(task.getSrcDocNo(), toStatus);
            } catch (RuntimeException e) {
                log.warn("wave link on task {} -> {} failed (non-blocking): {}",
                        task.getTaskNo(), toStatus, e.getMessage());
            }
        }
        return require(taskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public com.erp.entity.inv.PickTask createFromWave(String waveId) {
        com.erp.entity.inv.InvWave wave = waveDao.selectById(waveId);
        if (wave == null) {
            throw new ServiceException(404, "波次不存在：" + waveId);
        }
        // 锁定读幂等（同 createFromDoc 口径）
        List<PickTask> existing = taskDao.selectList(new LambdaQueryWrapper<PickTask>()
                .eq(PickTask::getSrcType, "WAVE")
                .eq(PickTask::getSrcDocNo, wave.getWaveNo())
                .last("FOR UPDATE"));
        PickTask active = null;
        for (PickTask t : existing) {
            if (!PickTask.ST_CANCELLED.equals(t.getStatus())) {
                active = t;
                break;
            }
        }
        // 分配行（BOUND 单据的段；UNBOUND 拆出行的段不进任务）
        List<com.erp.entity.inv.InvWaveLine> segs = waveLineDao.selectList(
                new LambdaQueryWrapper<com.erp.entity.inv.InvWaveLine>()
                        .eq(com.erp.entity.inv.InvWaveLine::getWaveId, waveId));
        Map<String, com.erp.entity.inv.InvWaveDoc> docById = new HashMap<>();
        for (com.erp.entity.inv.InvWaveDoc d : waveDocDao.selectList(
                new LambdaQueryWrapper<com.erp.entity.inv.InvWaveDoc>()
                        .eq(com.erp.entity.inv.InvWaveDoc::getWaveId, waveId)
                        .eq(com.erp.entity.inv.InvWaveDoc::getBindStatus,
                                com.erp.entity.inv.InvWaveDoc.BIND_BOUND))) {
            docById.put(d.getShipId(), d);
        }
        // 聚合 (bin, batch) → qty 合计 + 归属明细
        Map<WaveSeg, java.math.BigDecimal> agg = new LinkedHashMap<>();
        Map<WaveSeg, List<Map<String, Object>>> allocBy = new LinkedHashMap<>();
        for (com.erp.entity.inv.InvWaveLine seg : segs) {
            com.erp.entity.inv.InvWaveDoc doc = docById.get(seg.getShipId());
            if (doc == null) {
                continue;   // 拆出行段不进任务
            }
            WaveSeg k = new WaveSeg(nullSafe(seg.getBinCode()), nullSafe(seg.getBatchNo()),
                    seg.getItemCode(), seg.getItemName(), seg.getWarehouseCode());
            agg.merge(k, seg.getQty(), java.math.BigDecimal::add);
            allocBy.computeIfAbsent(k, x -> new ArrayList<>())
                    .add(new java.util.LinkedHashMap<>(Map.of(
                            "shipId", seg.getShipId(),
                            "shipNo", doc.getShipNo(),
                            "qty", seg.getQty())));
        }
        if (agg.isEmpty()) {
            throw new ServiceException(422, "波次无分配行（未分配或全部拆出）：" + wave.getWaveNo());
        }
        // 行序：仓位号升序（偏差 D5 路径）→ 批次
        List<WaveSeg> order = new ArrayList<>(agg.keySet());
        order.sort(Comparator.comparing(WaveSeg::bin).thenComparing(WaveSeg::batch));

        if (active != null) {
            // 幂等同步：全删重建行（WAVE 行无实拣态可覆盖问题——有实拣时任务已过 PICKING，
            // 而 createFromWave 只在确认分配（CREATED 任务）时被调，允许重建）
            lineDao.delete(new LambdaQueryWrapper<PickTaskLine>()
                    .eq(PickTaskLine::getTaskId, active.getId()));
            insertWaveLines(active.getId(), order, agg, allocBy);
            return active;
        }

        PickTask task = new PickTask();
        task.setTaskNo(nextTaskNo());
        task.setSrcType("WAVE");
        task.setSrcDocId(wave.getId());
        task.setSrcDocNo(wave.getWaveNo());
        task.setStatus(PickTask.ST_CREATED);
        task.setCreateBy(SecurityUtils.getCurrentUserId());
        taskDao.insert(task);
        insertWaveLines(task.getId(), order, agg, allocBy);
        log.info("WAVE pick task {} created: {} rows={}", task.getTaskNo(),
                wave.getWaveNo(), order.size());
        return task;
    }

    private void insertWaveLines(String taskId, List<WaveSeg> order,
                                 Map<WaveSeg, java.math.BigDecimal> agg,
                                 Map<WaveSeg, List<Map<String, Object>>> allocBy) {
        com.fasterxml.jackson.databind.ObjectMapper om =
                new com.fasterxml.jackson.databind.ObjectMapper();
        int no = 1;
        for (WaveSeg k : order) {
            PickTaskLine l = new PickTaskLine();
            l.setTaskId(taskId);
            l.setLineNo(no++);
            l.setItemCode(k.item());
            l.setItemName(k.name());
            l.setWarehouseCode(k.wh());
            l.setBatchNo(k.batch());
            l.setBinCode(k.bin());
            l.setQty(agg.get(k));
            l.setLineStatus(PickTaskLine.LS_PENDING);
            l.setScanOkFlag("0");
            l.setSrcAlloc(toJsonQuiet(om, allocBy.get(k)));
            lineDao.insert(l);
        }
    }

    private String toJsonQuiet(com.fasterxml.jackson.databind.ObjectMapper om, Object v) {
        try {
            return om.writeValueAsString(v);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ServiceException(500, "任务行归属序列化失败：" + e.getMessage());
        }
    }

    /** WAVE 任务行聚合键：仓位 + 批次 + 物料（分播/路径排序单元） */
    private record WaveSeg(String bin, String batch, String item, String name, String wh) { }

    private static String nullSafe(String v) {
        return v == null ? "" : v;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelIfExists(String srcType, String srcDocNo, String reason) {
        List<PickTask> tasks = taskDao.selectList(new LambdaQueryWrapper<PickTask>()
                .eq(PickTask::getSrcType, srcType)
                .eq(PickTask::getSrcDocNo, srcDocNo)
                .last("FOR UPDATE"));
        for (PickTask t : tasks) {
            if (PickTask.ST_CANCELLED.equals(t.getStatus())
                    || PickTask.ST_COMPLETED.equals(t.getStatus())) {
                continue;
            }
            if (PickTask.ST_CREATED.equals(t.getStatus())) {
                cancel(t.getId(), reason);
            } else {
                log.warn("task {} in state {} not cancelled by wave linkage (only CREATED)",
                        t.getTaskNo(), t.getStatus());
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markCompleted(String srcType, String srcDocNo) {
        List<PickTask> tasks = taskDao.selectList(new LambdaQueryWrapper<PickTask>()
                .eq(PickTask::getSrcType, srcType)
                .eq(PickTask::getSrcDocNo, srcDocNo)
                .eq(PickTask::getStatus, PickTask.ST_DONE)
                .last("LIMIT 1"));
        if (tasks.isEmpty()) {
            return;   // 无任务或未到 DONE（门闩已挡差异单）→ 跳过
        }
        PickTask t = tasks.get(0);
        transition(t.getId(), PickTask.ST_DONE, PickTask.ST_COMPLETED);
        taskDao.update(null, new LambdaUpdateWrapper<PickTask>()
                .eq(PickTask::getId, t.getId())
                .set(PickTask::getCompleteAt, LocalDateTime.now()));
    }

    // ---------- helpers ----------

    private PickTask require(String taskId) {
        PickTask t = taskDao.selectById(taskId);
        if (t == null) {
            throw new ServiceException(404, "拣货任务不存在：" + taskId);
        }
        return t;
    }

    private void requireWrite(String action) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        boolean ok = false;
        for (var a : auth.getAuthorities()) {
            String r = a.getAuthority();
            if ("ROLE_ADMIN".equalsIgnoreCase(r) || "ROLE_WAREHOUSE".equalsIgnoreCase(r)) {
                ok = true;
                break;
            }
        }
        if (!ok) {
            throw new ServiceException(403, "无权限" + action + "（需仓库或管理员角色）");
        }
    }

    private static boolean notBlank(String v) {
        return v != null && !v.trim().isEmpty();
    }

    private static String trimOrNull(String v) {
        return v == null ? null : v.trim();
    }
}
