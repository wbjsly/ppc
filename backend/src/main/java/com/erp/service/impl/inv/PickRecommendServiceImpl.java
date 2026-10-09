package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.inv.ScrapOrderDao;
import com.erp.dao.inv.ScrapOrderLineDao;
import com.erp.dao.inv.TransferOrderDao;
import com.erp.dao.inv.TransferOrderLineDao;
import com.erp.dao.sd.ShipmentDao;
import com.erp.dao.sd.ShipmentLineDao;
import com.erp.dao.vmi.MaterialIssueDao;
import com.erp.dao.vmi.MaterialIssueLineDao;
import com.erp.entity.inv.InvScrapOrder;
import com.erp.entity.inv.InvScrapOrderLine;
import com.erp.entity.inv.InvTransferOrder;
import com.erp.entity.inv.InvTransferOrderLine;
import com.erp.entity.inv.InvStock;
import com.erp.entity.sd.Shipment;
import com.erp.entity.sd.ShipmentLine;
import com.erp.entity.vmi.MaterialIssue;
import com.erp.entity.vmi.MaterialIssueLine;
import com.erp.service.inv.BatchRecommendService;
import com.erp.service.inv.FifoStrategyService;
import com.erp.service.inv.PickRecommendService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 拣货推荐实现（4.6.3，spec outbound-strategy；design D2/D5/D6）：
 * generate = 按类型取队列 A 单据行 → BatchRecommendService（binLevel=true）逐行推荐（只读）；
 * confirm = 后端重算基准 → 改写行落偏离台账（原因必填）→ 回写行 BATCH_NO/BIN_CODE（乐观锁）；
 * 不建预留（偏差 D2），冲突由过账封顶兜底。
 */
@Slf4j
@Service
public class PickRecommendServiceImpl implements PickRecommendService {

    private static final String T_SALES = "SALES_OUT";
    private static final String T_MATERIAL = "MATERIAL_OUT";
    private static final String T_TRANSFER = "TRANSFER_OUT";
    private static final String T_SCRAP = "SCRAP_OUT";

    private final ShipmentDao shipmentDao;
    private final ShipmentLineDao shipmentLineDao;
    private final MaterialIssueDao materialIssueDao;
    private final MaterialIssueLineDao materialIssueLineDao;
    private final TransferOrderDao transferOrderDao;
    private final TransferOrderLineDao transferOrderLineDao;
    private final ScrapOrderDao scrapOrderDao;
    private final ScrapOrderLineDao scrapOrderLineDao;
    private final BatchRecommendService recommendService;
    private final FifoStrategyService fifoStrategyService;
    private final com.erp.service.inv.PickTaskService pickTaskService;
    private final com.erp.dao.inv.InvWaveDao waveDao;
    private final com.erp.dao.inv.InvWaveDocDao waveDocDao;

    public PickRecommendServiceImpl(ShipmentDao shipmentDao, ShipmentLineDao shipmentLineDao,
                                    MaterialIssueDao materialIssueDao,
                                    MaterialIssueLineDao materialIssueLineDao,
                                    TransferOrderDao transferOrderDao,
                                    TransferOrderLineDao transferOrderLineDao,
                                    ScrapOrderDao scrapOrderDao, ScrapOrderLineDao scrapOrderLineDao,
                                    BatchRecommendService recommendService,
                                    FifoStrategyService fifoStrategyService,
                                    com.erp.service.inv.PickTaskService pickTaskService,
                                    com.erp.dao.inv.InvWaveDao waveDao,
                                    com.erp.dao.inv.InvWaveDocDao waveDocDao ) {
        this.shipmentDao = shipmentDao;
        this.shipmentLineDao = shipmentLineDao;
        this.materialIssueDao = materialIssueDao;
        this.materialIssueLineDao = materialIssueLineDao;
        this.transferOrderDao = transferOrderDao;
        this.transferOrderLineDao = transferOrderLineDao;
        this.scrapOrderDao = scrapOrderDao;
        this.scrapOrderLineDao = scrapOrderLineDao;
        this.recommendService = recommendService;
        this.fifoStrategyService = fifoStrategyService;
        this.pickTaskService = pickTaskService;
        this.waveDao = waveDao;
        this.waveDocDao = waveDocDao;
    }

    // ---------- 行装载（按类型归一：lineNo/item/qty/warehouse/现值） ----------

    private record NormLine(String lineId, int lineNo, String itemCode, BigDecimal qty,
                            String warehouse, String batchNo, String binCode) {
    }

    private record DocCtx(String type, String docNo, String status, String queueAStatus,
                          List<NormLine> lines) {
    }

    @SuppressWarnings("unchecked")
    private DocCtx loadDoc(String type, String docNo) {
        if (docNo == null || docNo.trim().isEmpty()) {
            throw new ServiceException(422, "单据号必填");
        }
        String no = docNo.trim();
        switch (type) {
            case T_SALES -> {
                Shipment s = shipmentDao.selectOne(new LambdaQueryWrapper<Shipment>()
                        .eq(Shipment::getShipNo, no).last("LIMIT 1"));
                if (s == null) {
                    throw new ServiceException(404, "发货单不存在：" + no);
                }
                List<NormLine> lines = new ArrayList<>();
                for (ShipmentLine l : shipmentLineDao.selectList(
                        new LambdaQueryWrapper<ShipmentLine>()
                                .eq(ShipmentLine::getShipId, s.getId())
                                .orderByAsc(ShipmentLine::getLineNo))) {
                    if (ShipmentLine.LS_CANCELLED.equals(l.getLineStatus())) {
                        continue;
                    }
                    lines.add(new NormLine(l.getId(), l.getLineNo(), l.getItemCode(), l.getQty(),
                            l.getWarehouseCode(), l.getBatchNo(), l.getBinCode()));
                }
                return new DocCtx(type, no, s.getStatus(), Shipment.ST_DRAFT, lines);
            }
            case T_MATERIAL -> {
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
                    lines.add(new NormLine(l.getId(), l.getLineNo(), l.getItemCode(), l.getQty(),
                            InvStock.DEFAULT_WH, l.getBatchNo(), l.getBinCode()));
                }
                return new DocCtx(type, no, m.getStatus(), MaterialIssue.ST_DRAFT, lines);
            }
            case T_TRANSFER -> {
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
                    lines.add(new NormLine(l.getId(), l.getLineNo(), l.getItemCode(), l.getQty(),
                            o.getOutWhCode(), l.getBatchNo(), l.getBinCode()));
                }
                return new DocCtx(type, no, o.getStatus(), InvTransferOrder.ST_DRAFT, lines);
            }
            case T_SCRAP -> {
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
                    lines.add(new NormLine(l.getId(), l.getLineNo(), l.getItemCode(), l.getQty(),
                            o.getWarehouseCode(), l.getBatchNo(), l.getBinCode()));
                }
                return new DocCtx(type, no, o.getStatus(), InvScrapOrder.ST_APPROVED, lines);
            }
            default -> throw new ServiceException(422, "不支持的出库类型：" + type);
        }
    }

    private void requireQueueA(DocCtx doc) {
        if (!doc.queueAStatus().equals(doc.status())) {
            throw new ServiceException(422, "单据不在待推荐口径（" + doc.docNo()
                    + " 当前 " + doc.status() + "，需 " + doc.queueAStatus()
                    + "）。已过账或已终结的单据不可执行推荐确认");
        }
    }

    // ---------- generate ----------

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> generate(String type, String docNo) {
        DocCtx doc = loadDoc(type, docNo);
        requireQueueA(doc);
        List<Map<String, Object>> outLines = new ArrayList<>();
        for (NormLine l : doc.lines()) {
            Map<String, Object> rec = recommendService.recommend(
                    l.warehouse(), l.itemCode(), l.qty(), true);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lineId", l.lineId());
            m.put("lineNo", l.lineNo());
            m.put("itemCode", l.itemCode());
            m.put("qty", l.qty());
            m.put("currentBatchNo", l.batchNo());
            m.put("currentBinCode", l.binCode());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> recLines = (List<Map<String, Object>>) rec.get("lines");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> excluded = (List<Map<String, Object>>) rec.get("excluded");
            // 推荐 = 逐批取量（单行单批：首段批次 + 其位行拆分；多批则按取量行展开）
            m.put("recommendLines", recLines);
            m.put("excluded", excluded);
            m.put("satisfied", rec.get("satisfied"));
            m.put("gap", rec.get("gap"));
            m.put("splitSuggestion", rec.get("splitSuggestion"));
            m.put("sortRule", rec.get("sortRule"));
            outLines.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("docNo", doc.docNo());
        out.put("type", doc.type());
        out.put("status", doc.status());
        out.put("lines", outLines);
        return out;
    }

    // ---------- confirm ----------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> confirm(Map<String, Object> payload) {
        String type = str(payload.get("type"));
        String docNo = str(payload.get("docNo"));
        DocCtx doc = loadDoc(type, docNo);
        requireQueueA(doc);

        Object rawLines = payload.get("lines");
        if (!(rawLines instanceof List<?> req) || req.isEmpty()) {
            throw new ServiceException(422, "确认行必填");
        }
        Map<Integer, Map<String, Object>> byLineNo = new LinkedHashMap<>();
        for (Object o : req) {
            if (!(o instanceof Map<?, ?> m)) {
                throw new ServiceException(422, "行格式非法");
            }
            Object no = m.get("lineNo");
            if (!(no instanceof Number n)) {
                throw new ServiceException(422, "行号必填");
            }
            byLineNo.put(n.intValue(), (Map<String, Object>) m);
        }

        int updated = 0;
        int deviations = 0;
        for (NormLine l : doc.lines()) {
            Map<String, Object> reqLine = byLineNo.get(l.lineNo());
            if (reqLine == null) {
                continue;   // 未确认行保持原值
            }
            String wantBatch = trimToNull(reqLine.get("batchNo"));
            String wantBin = trimToNull(reqLine.get("binCode"));
            String reason = trimToNull(reqLine.get("deviationReason"));
            if (wantBatch == null && wantBin == null) {
                continue;
            }
            // 基准 = 后端重算（防前端篡改基准，design D5）
            Map<String, Object> rec = recommendService.recommend(
                    l.warehouse(), l.itemCode(), l.qty(), false);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> recLines = (List<Map<String, Object>>) rec.get("lines");
            String baseline = recLines.isEmpty() ? null
                    : trimToNull(recLines.get(0).get("batchNo"));

            // 改写行落偏离台账（原因必填由 recordDeviation 强制；一致不落账）
            String finalBatch = wantBatch == null ? l.batchNo() : wantBatch;
            if (finalBatch != null && !finalBatch.equals(l.batchNo())) {
                fifoStrategyService.recordDeviation("PICK_RECOMMEND", doc.docNo(), l.lineNo(),
                        l.itemCode(), l.warehouse(), baseline, finalBatch, reason);
                deviations++;
            }
            writeBack(l, finalBatch, wantBin == null ? l.binCode() : wantBin);
            updated++;
        }
        if (updated == 0) {
            throw new ServiceException(422, "无有效回写行（行号不匹配或批次/仓位均空）");
        }
        // 4.7.1 拣货任务生成钩子（picking-review design D1）：同事务、幂等；
        // 波次成员分支（spec wave-management）：CREATED 波次 → 仅回写跳过任务；
        // ≥ALLOCATED → 422 防绕过 C-4.4-08 审批直接改批次/仓位
        String waveState = waveMembershipState(type, docNo);
        com.erp.entity.inv.PickTask task = null;
        if (waveState == null) {
            task = pickTaskService.createFromDoc(type, docNo);
        } else if (!"CREATED".equals(waveState)) {
            throw new ServiceException(422, "该单据已入波次并完成分配（波次状态 "
                    + waveState + "），禁止经 4.6.3 改批绕过波次改批审批（C-4.4-08），"
                    + "请在 4.8.1 波次详情发起改批");
        }
        // waveState=CREATED：跳过单据级任务（由 WAVE 合并任务承载）
        log.info("pick recommend confirmed: {} type={} lines={} deviations={} task={}",
                docNo, type, updated, deviations,
                task == null ? "(wave member, skipped)" : task.getTaskNo());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", task == null ? null : task.getId());
        out.put("taskNo", task == null ? null : task.getTaskNo());
        out.put("waveSkipped", task == null);
        out.put("docNo", docNo);
        out.put("updatedLines", updated);
        out.put("deviationLines", deviations);
        out.put("reservationCreated", false);   // 保守版：不建预留（偏差 D2）
        return out;
    }

    /**
     * 单据的活跃波次成员状态（spec wave-management 4.6.3 钩子分支）：
     * 非成员/终态波次 → null（原路径）；CREATED → 跳过任务；ALLOCATED+ → 调用方 422。
     * 仅 SALES_OUT 入波次（偏差 D2），其余类型恒 null。
     */
    private String waveMembershipState(String type, String docNo) {
        if (!"SALES_OUT".equals(type)) {
            return null;
        }
        java.util.List<com.erp.entity.inv.InvWaveDoc> docs = waveDocDao.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
                        com.erp.entity.inv.InvWaveDoc>()
                        .eq(com.erp.entity.inv.InvWaveDoc::getShipNo, docNo)
                        .eq(com.erp.entity.inv.InvWaveDoc::getBindStatus,
                                com.erp.entity.inv.InvWaveDoc.BIND_BOUND));
        if (docs.isEmpty()) {
            return null;
        }
        com.erp.entity.inv.InvWave w = waveDao.selectById(docs.get(0).getWaveId());
        if (w == null || com.erp.entity.inv.InvWave.ST_CLOSED.equals(w.getStatus())
                || com.erp.entity.inv.InvWave.ST_CANCELLED.equals(w.getStatus())) {
            return null;
        }
        return w.getStatus();
    }

    /** 行值回写（乐观锁：实体带 verNo，updateById 失配 → 0 行 → 409） */
    private void writeBack(NormLine l, String batchNo, String binCode) {
        // 行 ID 全局唯一：逐 DAO 尝试定位，命中一个即写回
        int rows = doWriteBack(l, batchNo, binCode);
        if (rows == 0) {
            throw new ServiceException(409, "行更新冲突（并发修改或已过账），请刷新后重试：行 "
                    + l.lineNo());
        }
    }

    private int doWriteBack(NormLine l, String batchNo, String binCode) {
        // 四类行按 lineId 前缀不可靠——逐 DAO 尝试更新（行 ID 全局唯一，命中一个即返回 1）
        ShipmentLine sl = shipmentLineDao.selectById(l.lineId());
        if (sl != null) {
            sl.setBatchNo(batchNo);
            sl.setBinCode(binCode);
            return shipmentLineDao.updateById(sl);
        }
        MaterialIssueLine ml = materialIssueLineDao.selectById(l.lineId());
        if (ml != null) {
            ml.setBatchNo(batchNo);
            ml.setBinCode(binCode);
            return materialIssueLineDao.updateById(ml);
        }
        InvTransferOrderLine tl = transferOrderLineDao.selectById(l.lineId());
        if (tl != null) {
            tl.setBatchNo(batchNo);
            tl.setBinCode(binCode);
            return transferOrderLineDao.updateById(tl);
        }
        InvScrapOrderLine scl = scrapOrderLineDao.selectById(l.lineId());
        if (scl != null) {
            scl.setBatchNo(batchNo);
            scl.setBinCode(binCode);
            return scrapOrderLineDao.updateById(scl);
        }
        return 0;
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static String trimToNull(Object v) {
        if (v == null) {
            return null;
        }
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
