package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvFreezeDao;
import com.erp.dao.inv.InvStockDao;
import com.erp.dao.proc.PurchaseOrderDao;
import com.erp.dao.proc.PurchaseOrderLineDao;
import com.erp.dao.sd.ReservationDao;
import com.erp.entity.inv.InvFreeze;
import com.erp.entity.inv.InvStock;
import com.erp.entity.proc.PurchaseOrder;
import com.erp.entity.proc.PurchaseOrderLine;
import com.erp.entity.sd.Reservation;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import com.erp.service.inv.FreezeService;
import com.erp.service.sd.ReservationService;
import com.erp.service.system.NoticeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 冻结/解冻审批回调（spec freeze-management FR-4.4-5-3/5.8，design D4）。
 * 与审批签署同事务（ApprovalEngine 契约）：执行异常 → 签署与业务一并回滚。
 * 冻结执行：AVAILABLE → QC/FIN 平移（ge 守卫 + FOR UPDATE 锁行）+ 释放该批次
 * ACTIVE 预留（FR-4.4-5-3，缺它可用公式双重扣减）+ 台账 ACTIVE + 通知落表（偏差 D1）。
 * 解冻执行：反向平移 + 台账 RELEASED + 通知。
 * 驳回：冻结单置 REJECTED；解冻驳回保持 ACTIVE（审批意见由底座留痕）。
 */
@Slf4j
@Component
public class FreezeCallback implements ApprovalCallback {

    private final InvFreezeDao freezeDao;
    private final InvStockDao stockDao;
    private final ReservationDao reservationDao;
    private final ReservationService reservationService;
    private final NoticeService noticeService;
    private final PurchaseOrderLineDao poLineDao;
    private final PurchaseOrderDao poDao;
    private final com.erp.service.inv.FreezePauseService freezePauseService;
    private final com.fasterxml.jackson.databind.ObjectMapper jsonMapper;

    public FreezeCallback(InvFreezeDao freezeDao, InvStockDao stockDao,
                          ReservationDao reservationDao, ReservationService reservationService,
                          NoticeService noticeService,
                          PurchaseOrderLineDao poLineDao, PurchaseOrderDao poDao,
                          com.erp.service.inv.FreezePauseService freezePauseService,
                          com.fasterxml.jackson.databind.ObjectMapper jsonMapper) {
        this.freezeDao = freezeDao;
        this.stockDao = stockDao;
        this.reservationDao = reservationDao;
        this.reservationService = reservationService;
        this.noticeService = noticeService;
        this.poLineDao = poLineDao;
        this.poDao = poDao;
        this.freezePauseService = freezePauseService;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(FreezeService.BIZ_FREEZE, FreezeService.BIZ_UNFREEZE);
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        if (FreezeService.BIZ_FREEZE.equals(instance.getBizType())) {
            executeFreeze(instance.getBizId());
        } else {
            executeUnfreeze(instance.getBizId());
        }
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        InvFreeze f = freezeDao.selectById(instance.getBizId());
        if (f == null) {
            log.warn("freeze {} rejected but record missing", instance.getBizId());
            return;
        }
        if (FreezeService.BIZ_FREEZE.equals(instance.getBizType())) {
            if (InvFreeze.ST_PENDING.equals(f.getStatus())) {
                f.setStatus(InvFreeze.ST_REJECTED);
                freezeDao.updateById(f);
            }
            log.info("freeze {} REJECTED", f.getFreezeNo());
        } else {
            // 解冻驳回 → 继续冻结并注明原因（FR-4.4-5-7，意见在审批日志，本表状态不变）
            f.setRemark(trimRemark(f.getRemark(), "解冻驳回"));
            freezeDao.updateById(f);
            log.info("unfreeze {} rejected, freeze stays ACTIVE", f.getFreezeNo());
        }
    }

    // ---------- 冻结执行（FR-4.4-5-3） ----------

    private void executeFreeze(String freezeId) {
        InvFreeze f = freezeDao.selectById(freezeId);
        if (f == null) {
            log.warn("freeze approved but record missing: {}", freezeId);
            return;
        }
        if (InvFreeze.ST_ACTIVE.equals(f.getStatus())) {
            return; // 幂等
        }
        if (!InvFreeze.ST_PENDING.equals(f.getStatus())) {
            throw new ServiceException(422, "冻结单状态不可执行：" + f.getStatus());
        }

        String col = InvFreeze.T_QUALITY.equals(f.getFreezeType()) ? "QC_QTY" : "FIN_QTY";
        List<InvStock> targets = lockTargets(f);
        List<String> affectedSo = affectedSoNos(f);

        if (!InvFreeze.SCOPE_ALL.equals(f.getScope()) && targets.isEmpty()) {
            throw new ServiceException(422, "库存维度不存在，冻结执行阻断："
                    + f.getItemCode() + "/" + f.getBatchNo());
        }
        // 位行级 FIFO 拆行平移（change add-bin-assignment design D7：同批次跨多仓位逐行冻结）
        BigDecimal remain = f.getQty();
        List<Map<String, Object>> detail = new ArrayList<>();
        for (InvStock s : targets) {
            if (remain.signum() <= 0) {
                break;
            }
            BigDecimal avail = nvl(s.getAvailableQty());
            if (avail.signum() <= 0) {
                continue;
            }
            BigDecimal take = avail.min(remain);
            shiftFreeze(s, take, col);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("binCode", str(s.getBinCode()));
            d.put("qty", take);
            detail.add(d);
            remain = remain.subtract(take);
        }
        if (remain.signum() > 0) {
            throw new ServiceException(422, "可用量不足：需冻结 " + strip(f.getQty())
                    + "，该物料可冻结不足（执行阻断，审批签署回滚可重试）");
        }
        // 行级明细（解冻原路回补依据）
        f.setDetailJson(toJson(detail));
        // 影响面快照（FR-4.4-5-4/5.5，4.9.4 展开）：SO 预留反查 + PO 未清行 + 工单占位
        f.setImpactJson(buildImpactJson(f));
        reservationService.releaseByBatch(f.getWarehouseCode(), f.getItemCode(),
                InvFreeze.SCOPE_ALL.equals(f.getScope())
                        ? null : (f.getBatchNo() == null ? "" : f.getBatchNo()),
                "冻结释放：" + f.getFreezeNo());

        f.setStatus(InvFreeze.ST_ACTIVE);
        if (freezeDao.updateById(f) == 0) {
            throw new ServiceException(409, "冻结单状态更新冲突");
        }
        // 挂起关联拣货任务/波次（BR-4.4-32，同事务；解冻不自动恢复）
        freezePauseService.pauseOutbound(f);

        // 影响通知落表（FR-4.4-5-4；前端无展示 → 偏差 D1）
        noticeService.push("ROLE_WAREHOUSE", null,
                "库存冻结生效：" + f.getFreezeNo(),
                "物料 " + f.getItemCode() + "，批次 " + str(f.getBatchNo())
                        + "，数量 " + strip(f.getQty()) + "（" + f.getFreezeType() + "冻结），原因："
                        + f.getReason() + "。影响 SO：" + (affectedSo.isEmpty() ? "无" : String.join(",", affectedSo)),
                "FREEZE", f.getFreezeNo());
        log.info("freeze {} EXECUTED: {} {} qty={} so={}", f.getFreezeNo(), f.getItemCode(),
                f.getBatchNo(), f.getQty(), affectedSo);
    }

    // ---------- 解冻执行（FR-4.4-5-8） ----------

    private void executeUnfreeze(String freezeId) {
        InvFreeze f = freezeDao.selectById(freezeId);
        if (f == null) {
            log.warn("unfreeze approved but record missing: {}", freezeId);
            return;
        }
        if (InvFreeze.ST_RELEASED.equals(f.getStatus())) {
            return; // 幂等
        }
        if (!InvFreeze.ST_ACTIVE.equals(f.getStatus())) {
            throw new ServiceException(422, "冻结单状态不可解冻：" + f.getStatus());
        }

        String col = InvFreeze.T_QUALITY.equals(f.getFreezeType()) ? "QC_QTY" : "FIN_QTY";
        List<InvStock> targets = lockTargets(f);
        if (targets.isEmpty()) {
            throw new ServiceException(422, "库存维度不存在，解冻执行阻断");
        }
        BigDecimal remain = f.getQty();

        // 按冻结时记录的行级明细原路回补（design D7）；明细缺失（存量单）或明细行
        // 已被清零/合并 → 缺口回补到该批次现存位行 FIFO 首行起依次兜底
        for (Map<String, Object> d : parseDetail(f.getDetailJson())) {
            if (remain.signum() <= 0) {
                break;
            }
            String bin = String.valueOf(d.get("binCode"));
            BigDecimal want = new BigDecimal(String.valueOf(d.get("qty")));
            if (want.signum() <= 0) {
                continue;
            }
            InvStock row = null;
            for (InvStock s : targets) {
                if (bin.equals(str(s.getBinCode()))) {
                    row = s;
                    break;
                }
            }
            if (row == null) {
                continue;   // 明细行已消失（上架合并）→ 走兜底
            }
            BigDecimal held = nvl(col.equals("QC_QTY") ? row.getQcQty() : row.getFinQty());
            BigDecimal take = held.min(want);
            if (take.signum() > 0) {
                shiftUnfreeze(row, take, col);
                remain = remain.subtract(take);
            }
        }
        // 兜底：剩余缺口按现存位行 FIFO（INBOUND_DATE → BIN）回补
        for (InvStock s : targets) {
            if (remain.signum() <= 0) {
                break;
            }
            BigDecimal held = nvl(col.equals("QC_QTY") ? s.getQcQty() : s.getFinQty());
            if (held.signum() <= 0) {
                continue;
            }
            BigDecimal take = held.min(remain);
            shiftUnfreeze(s, take, col);
            remain = remain.subtract(take);
        }
        if (remain.signum() > 0) {
            throw new ServiceException(422, "冻结量不足，解冻执行阻断（缺 "
                    + strip(remain) + "，审批签署回滚可重试）");
        }

        f.setStatus(InvFreeze.ST_RELEASED);
        f.setReleasedAt(LocalDateTime.now());
        f.setReleasedBy(currentUserFromCallback());
        if (freezeDao.updateById(f) == 0) {
            throw new ServiceException(409, "冻结单状态更新冲突");
        }
        noticeService.push("ROLE_WAREHOUSE", null,
                "库存解冻完成：" + f.getFreezeNo(),
                "物料 " + f.getItemCode() + "，批次 " + str(f.getBatchNo())
                        + "，回补数量 " + strip(f.getQty()) + "。处理结果：" + f.getReleaseResult(),
                "UNFREEZE", f.getFreezeNo());
        log.info("unfreeze {} EXECUTED: {} {} qty={}", f.getFreezeNo(), f.getItemCode(),
                f.getBatchNo(), f.getQty());
    }

    // ---------- helpers ----------

    /** 锁定目标库存行（FOR UPDATE：执行期间可用量不被并发改写） */
    private List<InvStock> lockTargets(InvFreeze f) {
        LambdaQueryWrapper<InvStock> qw = new LambdaQueryWrapper<InvStock>()
                .eq(InvStock::getWarehouseCode, f.getWarehouseCode())
                .eq(InvStock::getItemCode, f.getItemCode())
                // 位行锁序与挑选序：inbound → bin（与引擎行序一致，design D7）
                .orderByAsc(InvStock::getInboundDate)
                .orderByAsc(InvStock::getBinCode)
                .last("FOR UPDATE");
        if (!InvFreeze.SCOPE_ALL.equals(f.getScope())) {
            qw.eq(InvStock::getBatchNo, f.getBatchNo() == null ? "" : f.getBatchNo());
        }
        return stockDao.selectList(qw);
    }

    /** AVAILABLE → QC/FIN 平移（ge 守卫；rows=0 → 409 并发） */
    private void shiftFreeze(InvStock s, BigDecimal take, String col) {
        int rows = stockDao.update(null, new LambdaUpdateWrapper<InvStock>()
                .eq(InvStock::getId, s.getId())
                .ge(InvStock::getAvailableQty, take)
                .setSql("AVAILABLE_QTY = AVAILABLE_QTY - " + strip(take))
                .setSql(col + " = " + col + " + " + strip(take)));
        if (rows == 0) {
            throw new ServiceException(409, "库存冻结并发冲突，请重试：" + s.getItemCode());
        }
    }

    /** QC/FIN → AVAILABLE 回补（ge 守卫；rows=0 → 409 并发） */
    private void shiftUnfreeze(InvStock s, BigDecimal take, String col) {
        int rows = stockDao.update(null, new LambdaUpdateWrapper<InvStock>()
                .eq(InvStock::getId, s.getId())
                .setSql(col + " = " + col + " - " + strip(take))
                .setSql("AVAILABLE_QTY = AVAILABLE_QTY + " + strip(take)));
        if (rows == 0) {
            throw new ServiceException(409, "库存解冻并发冲突，请重试：" + s.getItemCode());
        }
    }

    /**
     * 影响面快照（FR-4.4-5-4/5.5，spec freeze-management ADDED 需求 ②）：
     * SO=冻结维度 ACTIVE 预留反查（精确到批次，冻结执行前取样——预留随后被释放，
     * 快照保留受影响瞬间）；PO=该物料未清 PO 行（物料级）；工单数据源未落地
     * （proposal 偏差 D1：mos 恒空 + moDataSource=false）。序列化失败 422 阻断冻结。
     */
    private String buildImpactJson(InvFreeze f) {
        // SO：预留反查（与 affectedSoNos 同口径，扩展为含行号/数量的明细）
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

    /** 冻结维度上 ACTIVE 预留关联的 SO 编号（通知影响面，FR-4.4-5-4） */
    private List<String> affectedSoNos(InvFreeze f) {
        List<String> soNos = new ArrayList<>();
        for (Reservation r : reservationDao.selectList(reservationQw(f))) {
            if (r.getSoNo() != null && !soNos.contains(r.getSoNo())) {
                soNos.add(r.getSoNo());
            }
        }
        return soNos;
    }

    /** 序列化行级明细（失败即阻断冻结——明细是解冻回补依据，宁可不冻） */
    private String toJson(List<Map<String, Object>> detail) {
        try {
            return jsonMapper.writeValueAsString(detail);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ServiceException(422, "冻结明细序列化失败：" + e.getMessage());
        }
    }

    /** 解析行级明细；空/坏 JSON 返回空表（走兜底回补路径） */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseDetail(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return jsonMapper.readValue(json, List.class);
        } catch (Exception e) {
            log.warn("freeze detail json unparsable, fallback scan: {}", e.getMessage());
            return List.of();
        }
    }

    private String trimRemark(String existing, String add) {
        if (existing == null || existing.isEmpty()) {
            return add;
        }
        return existing.contains(add) ? existing : existing + "；" + add;
    }

    private String currentUserFromCallback() {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder
                        .getContext().getAuthentication();
        return auth == null ? "system" : auth.getName();
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String strip(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }
}
