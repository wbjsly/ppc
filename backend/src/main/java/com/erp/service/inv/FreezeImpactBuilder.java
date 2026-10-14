package com.erp.service.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.proc.PurchaseOrderDao;
import com.erp.dao.proc.PurchaseOrderLineDao;
import com.erp.dao.sd.ReservationDao;
import com.erp.entity.inv.InvFreeze;
import com.erp.entity.proc.PurchaseOrder;
import com.erp.entity.proc.PurchaseOrderLine;
import com.erp.entity.sd.Reservation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 冻结影响面快照构建（FR-4.4-5-4/5.5，spec freeze-management 影响面快照需求）。
 * 从 FreezeCallback.buildImpactJson 纯搬移抽取（change rework-qualified-release design D2），
 * 供手工冻结路径（FreezeCallback）与 NCR 冻结路径（FreezeServiceImpl.recordNcrFreeze）复用。
 * SO=ACTIVE 预留反查（精确到批次）；PO=未清行（物料级）；工单数据源未落地（偏差 D1）。
 * 序列化失败抛 422 —— 调用方自行决定阻断（手工冻结）或降级（NCR 冻结）。
 */
@Component
public class FreezeImpactBuilder {

    private final ReservationDao reservationDao;
    private final PurchaseOrderLineDao poLineDao;
    private final PurchaseOrderDao poDao;
    private final com.fasterxml.jackson.databind.ObjectMapper jsonMapper;

    public FreezeImpactBuilder(ReservationDao reservationDao,
                               PurchaseOrderLineDao poLineDao,
                               PurchaseOrderDao poDao,
                               com.fasterxml.jackson.databind.ObjectMapper jsonMapper) {
        this.reservationDao = reservationDao;
        this.poLineDao = poLineDao;
        this.poDao = poDao;
        this.jsonMapper = jsonMapper;
    }

    /** 影响面快照 JSON（sos 精确批次 / pos 物料级 / mos 空 + moDataSource=false） */
    public String build(InvFreeze f) {
        // SO：预留反查（冻结执行前取样——预留随后被释放，快照保留受影响瞬间）
        List<Map<String, Object>> sos = new ArrayList<>();
        for (Reservation r : reservationDao.selectList(reservationQw(f))) {
            Map<String, Object> so = new LinkedHashMap<>();
            so.put("soNo", r.getSoNo());
            so.put("lineNo", r.getLineNo());
            so.put("qty", r.getQty());
            so.put("precision", "BATCH");
            sos.add(so);
        }
        // PO：该物料未清行（lineStatus=OPEN 且头未关闭），物料级
        List<Map<String, Object>> pos = new ArrayList<>();
        List<PurchaseOrderLine> openLines = poLineDao.selectList(
                new LambdaQueryWrapper<PurchaseOrderLine>()
                        .eq(PurchaseOrderLine::getItemCode, f.getItemCode())
                        .eq(PurchaseOrderLine::getLineStatus, "OPEN"));
        if (!openLines.isEmpty()) {
            Map<String, PurchaseOrder> heads = new LinkedHashMap<>();
            for (PurchaseOrder po : poDao.selectBatchIds(
                    openLines.stream().map(PurchaseOrderLine::getPoId).distinct().toList())) {
                heads.put(po.getId(), po);
            }
            for (PurchaseOrderLine l : openLines) {
                PurchaseOrder head = heads.get(l.getPoId());
                // 未清 PO = 已批准下达（APPROVED），草稿/审批中/已关闭不算影响面
                if (head == null || !"APPROVED".equals(head.getStatus())) {
                    continue;
                }
                Map<String, Object> po = new LinkedHashMap<>();
                po.put("poNo", head.getPoNo());
                po.put("lineNo", l.getLineNo());
                po.put("qty", l.getQty() == null ? null
                        : l.getQty().subtract(l.getReceivedQty() == null
                                ? java.math.BigDecimal.ZERO : l.getReceivedQty()));
                po.put("precision", "ITEM");
                pos.add(po);
            }
        }
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("sos", sos);
        snap.put("pos", pos);
        snap.put("mos", List.of());
        snap.put("moDataSource", false);   // proposal 偏差 D1：生产工单域未落地
        try {
            return jsonMapper.writeValueAsString(snap);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ServiceException(422, "影响面快照序列化失败：" + e.getMessage());
        }
    }

    private LambdaQueryWrapper<Reservation> reservationQw(InvFreeze f) {
        LambdaQueryWrapper<Reservation> qw = new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getStatus, Reservation.ST_ACTIVE)
                .eq(Reservation::getWarehouseCode, f.getWarehouseCode())
                .eq(Reservation::getItemCode, f.getItemCode());
        if (!InvFreeze.SCOPE_ALL.equals(f.getScope())) {
            qw.eq(Reservation::getBatchNo, f.getBatchNo() == null ? "" : f.getBatchNo());
        }
        return qw;
    }
}
