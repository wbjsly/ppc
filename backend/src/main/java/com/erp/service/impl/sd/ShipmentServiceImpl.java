package com.erp.service.impl.sd;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvStockDao;
import com.erp.dao.sd.ReservationDao;
import com.erp.dao.sd.SdReturnDao;
import com.erp.dao.sd.SdReturnLineDao;
import com.erp.dao.sd.ShipmentDao;
import com.erp.dao.sd.ShipmentLineDao;
import com.erp.dao.sd.SoDao;
import com.erp.dao.sd.SoLineDao;
import com.erp.entity.inv.InvStock;
import com.erp.entity.sd.Framework;
import com.erp.entity.sd.FrameworkLine;
import com.erp.entity.sd.FrameworkRelease;
import com.erp.entity.sd.Reservation;
import com.erp.entity.sd.SdReturn;
import com.erp.entity.sd.SdReturnLine;
import com.erp.entity.sd.Shipment;
import com.erp.entity.sd.ShipmentLine;
import com.erp.entity.sd.So;
import com.erp.entity.sd.SoLine;
import com.erp.ops.OutboxPublisher;
import com.erp.service.SysParamService;
import com.erp.service.fin.InvoiceService;
import com.erp.service.sd.ShipmentService;
import com.erp.service.system.NoticeService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 销售发货实现（tasks 9.2~9.9，spec sales-shipment，D8）。
 *
 * 关键口径：
 *  - 行校验（9.5）：qty ≤ min(未发余量, ACTIVE 预留) − 在途发货占用，超限 422 提示剩余可发；
 *  - 出库过账（9.6）同事务：FIFO 选批（同仓同 SKU 按入储时间）原子扣 AVAILABLE_QTY →
 *    批次优先消耗预留 → 发货单行 BATCH_ALLOC 即出库凭证明细（项目无独立库存流水表，记口径）
 *    → 发布 AR.CONFIRMED 应收确认事件；库存不足 422 整体回滚；
 *  - 发货确认（9.7）回写 SO 行已发量与行/单状态，物流异常通知销售；
 *  - 拒收（9.8）生成退货申请草稿并关联原发货单（实物回补在 12.7）；
 *  - 拣货作业边界：不生成拣货任务单（归库存域 4.7），页面须明示。
 */
@Slf4j
@Service
public class ShipmentServiceImpl implements ShipmentService {

    private final ShipmentDao shipDao;
    private final ShipmentLineDao shipLineDao;
    private final SoDao soDao;
    private final SoLineDao soLineDao;
    private final SdReturnDao returnDao;
    private final SdReturnLineDao returnLineDao;
    private final InvStockDao stockDao;
    private final ReservationDao reservationDao;
    private final OutboxPublisher outboxPublisher;
    private final NoticeService noticeService;
    private final SysParamService paramService;
    private final InvoiceService invoiceService;
    private final com.erp.service.inv.StockPostingEngine stockPostingEngine;

    public ShipmentServiceImpl(ShipmentDao shipDao,
                               ShipmentLineDao shipLineDao,
                               SoDao soDao,
                               SoLineDao soLineDao,
                               SdReturnDao returnDao,
                               SdReturnLineDao returnLineDao,
                               InvStockDao stockDao,
                               ReservationDao reservationDao,
                               OutboxPublisher outboxPublisher,
                               NoticeService noticeService,
                               SysParamService paramService,
                               InvoiceService invoiceService,
                               com.erp.service.inv.StockPostingEngine stockPostingEngine) {
        this.shipDao = shipDao;
        this.shipLineDao = shipLineDao;
        this.soDao = soDao;
        this.soLineDao = soLineDao;
        this.returnDao = returnDao;
        this.returnLineDao = returnLineDao;
        this.stockDao = stockDao;
        this.reservationDao = reservationDao;
        this.outboxPublisher = outboxPublisher;
        this.noticeService = noticeService;
        this.paramService = paramService;
        this.invoiceService = invoiceService;
        this.stockPostingEngine = stockPostingEngine;
    }

    // ---------- 9.2 部分发货 ----------

    @Override
    @Transactional
    public Map<String, Object> generatePartial(String soId, List<Map<String, Object>> lines) {
        requireAny("生成发货单", "ROLE_WAREHOUSE", "ROLE_SALES", "ROLE_ADMIN");
        So so = requireSo(soId);
        requireShippable(so);
        List<Map<String, Object>> rows = new ArrayList<>();
        if (lines == null || lines.isEmpty()) {
            // 缺省：全部行按未发余量
            for (SoLine l : soLines(soId)) {
                Map<String, Object> row = rowOf(so, l, null, true);
                if (row != null) {
                    rows.add(row);
                }
            }
            if (rows.isEmpty()) {
                throw new ServiceException(422, "该订单没有可发行（未发余量/预留不足或均被在途占用）");
            }
        } else {
            for (Map<String, Object> m : lines) {
                String lineId = str(m.get("soLineId"));
                SoLine l = requireLine(lineId, soId);
                rows.add(rowOf(so, l, num(m.get("qty")), false));
            }
        }
        return createShipment(Shipment.TYPE_PARTIAL, so, rows, null);
    }

    // ---------- 9.3 合并发货 ----------

    @Override
    @Transactional
    public Map<String, Object> generateMerge(List<String> soIds, String warehouseCode) {
        requireAny("生成合并发货单", "ROLE_WAREHOUSE", "ROLE_SALES", "ROLE_ADMIN");
        if (soIds == null || soIds.size() < 2) {
            throw new ServiceException(422, "合并发货须选择至少两张 SO（同客户同仓）");
        }
        String wh = isBlank(warehouseCode) ? "WH-MAIN" : warehouseCode;
        String custId = null;
        String custCode = null;
        String custName = null;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String soId : soIds) {
            So so = requireSo(soId);
            requireShippable(so);
            if (custId == null) {
                custId = so.getCustomerId();
                custCode = so.getCustomerCode();
                custName = so.getCustomerName();
            } else if (!custId.equals(so.getCustomerId())) {
                throw new ServiceException(422, "合并发货要求同一客户（" + custName
                        + " vs " + so.getCustomerName() + "）");
            }
            for (SoLine l : soLines(soId)) {
                if (SoLine.LS_CANCELLED.equals(l.getLineStatus())) {
                    continue;
                }
                String lineWh = isBlank(l.getWarehouseCode()) ? "WH-MAIN" : l.getWarehouseCode();
                if (!wh.equals(lineWh)) {
                    throw new ServiceException(422, "行 " + l.getLineNo() + "（" + so.getSoNo()
                            + "）仓库 " + lineWh + " 与合并仓 " + wh + " 不一致");
                }
                BigDecimal shipable = shipableOf(l);
                if (shipable.signum() <= 0) {
                    continue;   // 无可发行跳过
                }
                rows.add(rowOf(so, l, shipable, true));
            }
        }
        if (rows.isEmpty()) {
            throw new ServiceException(422, "所选订单没有可发行（未发余量或预留不足）");
        }
        So first = requireSo(soIds.get(0));
        return createShipment(Shipment.TYPE_MERGE, first, rows, wh);
    }

    // ---------- 9.4 分批发货 ----------

    @Override
    @Transactional
    public Map<String, Object> generateBatch(String soId, LocalDate asOfDate) {
        requireAny("生成分批发货单", "ROLE_WAREHOUSE", "ROLE_SALES", "ROLE_ADMIN");
        So so = requireSo(soId);
        requireShippable(so);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SoLine l : soLines(soId)) {
            if (SoLine.LS_CANCELLED.equals(l.getLineStatus())) {
                continue;
            }
            if (l.getPlanShipDate() == null) {
                continue;   // 未参与分批方案的行不属于分批发货
            }
            if (asOfDate != null && l.getPlanShipDate().isAfter(asOfDate)) {
                continue;   // 未到计划发货日
            }
            BigDecimal remain = nvl(l.getQty()).subtract(nvl(l.getShippedQty()));
            if (remain.signum() <= 0) {
                continue;
            }
            Map<String, Object> row = rowOf(so, l, remain, true);
            if (row != null) {
                rows.add(row);
            }
        }
        if (rows.isEmpty()) {
            throw new ServiceException(422, asOfDate == null
                    ? "该订单没有分批方案行（先在 3.4.2 编制分批交付）"
                    : "截至 " + asOfDate + " 无到期待发的分批行");
        }
        return createShipment(Shipment.TYPE_BATCH, so, rows, null);
    }

    // ---------- 9.6 出库过账 ----------

    @Override
    @Transactional
    public Shipment post(String shipId) {
        requireAny("出库过账", "ROLE_WAREHOUSE", "ROLE_ADMIN");
        Shipment ship = requireShip(shipId);
        if (!Shipment.ST_DRAFT.equals(ship.getStatus())) {
            throw new ServiceException(422, "仅草稿发货单可出库过账（当前：" + ship.getStatus() + "）");
        }
        return doPost(ship, true);
    }

    /** 出库过账主体（9.6）：FIFO 选批扣库存 + 消耗预留（换货单除外）+ 事件（可关） */
    private Shipment doPost(Shipment ship, boolean emitArEvent) {
        List<ShipmentLine> lines = shipLines(ship.getId());
        LocalDateTime now = LocalDateTime.now();
        for (ShipmentLine l : lines) {
            if (ShipmentLine.LS_CANCELLED.equals(l.getLineStatus())) {
                continue;
            }
            // FIFO 选批 + 原子扣减（同仓同 SKU 按入储时间先老先出）
            List<Map<String, Object>> alloc = allocAndDeduct(l);
            BigDecimal posted = alloc.stream()
                    .map(a -> (BigDecimal) a.get("qty"))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (posted.compareTo(l.getQty()) < 0) {
                throw new ServiceException(422, "库存不足：" + l.getItemCode() + "（仓库 "
                        + l.getWarehouseCode() + "）可发 " + strip(posted)
                        + " / 需求 " + strip(l.getQty()) + "，出库过账已阻断（FR-4.3-6-5）");
            }
            // 消耗预留（批次优先匹配，数量语义兜底；换货/框架发货不挂 SO 预留 → 跳过）
            if (!"EXCHANGE".equals(ship.getShipType())
                    && !Shipment.TYPE_FRAMEWORK.equals(ship.getShipType())) {
                consumeReservation(l.getSoLineId(), l.getQty(), alloc);
            }
            l.setLineStatus(ShipmentLine.LS_POSTED);
            l.setPostAt(now);
            l.setBatchAlloc(toJson(alloc));
            shipLineDao.updateById(l);
        }
        ship.setStatus(Shipment.ST_POSTED);
        ship.setPostBy(currentUser());
        ship.setPostAt(now);
        shipDao.updateById(ship);

        // 9.6 应收确认事件（同事务入 outbox；换货单不重复确认应收 → 不发）
        if (emitArEvent) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("customerId", ship.getCustomerId());
            extra.put("customerCode", ship.getCustomerCode());
            extra.put("amount", ship.getTotalAmt());
            extra.put("totalQty", ship.getTotalQty());
            extra.put("warehouseCode", ship.getWarehouseCode());
            extra.put("lineCount", lines.size());
            outboxPublisher.publishSourced("AR.CONFIRMED", ship.getShipNo(), 1, null,
                    "出库过账确认应收：" + ship.getTotalAmt(), extra, "sd-service");
        }
        log.info("shipment {} posted ({} lines, amt={}, exchange={})", ship.getShipNo(),
                lines.size(), ship.getTotalAmt(), "EXCHANGE".equals(ship.getShipType()));
        return ship;
    }

    // ---------- 12.6 换货发货（sales-return，spec 换货生成新发货单） ----------

    @Override
    @Transactional
    public Map<String, Object> exchangeFromReturn(SdReturn ret, List<SdReturnLine> returnLines) {
        requireAny("换货发货", "ROLE_SALES_MGR", "ROLE_SALES", "ROLE_WAREHOUSE", "ROLE_ADMIN");
        if (ret == null || returnLines == null || returnLines.isEmpty()) {
            throw new ServiceException(422, "换货来源退货单或行缺失");
        }
        So so = soDao.selectById(ret.getSoId());
        if (so == null) {
            throw new ServiceException(404, "原 SO 不存在，无法生成换货发货单：" + ret.getSoNo());
        }
        SdReturnLine firstLine = returnLines.get(0);
        SoLine firstSoLine = firstLine.getSoLineId() == null
                ? null : soLineDao.selectById(firstLine.getSoLineId());
        String defaultWh = firstSoLine != null && !isBlank(firstSoLine.getWarehouseCode())
                ? firstSoLine.getWarehouseCode() : "WH-MAIN";
        Shipment ship = new Shipment();
        ship.setId(uuid());
        ship.setShipNo(nextNo("SH"));
        ship.setShipType("EXCHANGE");
        ship.setCustomerId(so.getCustomerId());
        ship.setCustomerCode(so.getCustomerCode());
        ship.setCustomerName(so.getCustomerName());
        ship.setWarehouseCode(defaultWh);
        ship.setStatus(Shipment.ST_DRAFT);
        ship.setReturnId(ret.getId());
        ship.setRemark("换货发货（退货单 " + ret.getReturnNo() + "）");
        ship.setTotalQty(BigDecimal.ZERO);
        ship.setTotalAmt(BigDecimal.ZERO);
        shipDao.insert(ship);

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmt = BigDecimal.ZERO;
        int lineNo = 1;
        for (SdReturnLine rl : returnLines) {
            SoLine sl = rl.getSoLineId() == null ? null : soLineDao.selectById(rl.getSoLineId());
            BigDecimal qty = nvl(rl.getJudgeQty()) != null && nvl(rl.getJudgeQty()).signum() > 0
                    ? rl.getJudgeQty() : rl.getQty();
            if (qty.signum() <= 0) {
                continue;
            }
            ShipmentLine line = new ShipmentLine();
            line.setId(uuid());
            line.setShipId(ship.getId());
            line.setLineNo(lineNo++);
            line.setSoId(so.getId());
            line.setSoNo(so.getSoNo());
            line.setSoLineId(rl.getSoLineId());
            line.setSoLineNo(rl.getSoLineNo());
            line.setItemCode(rl.getItemCode());
            line.setItemName(rl.getItemName());
            line.setQty(qty);
            line.setBaseUnit(rl.getBaseUnit());
            // 保留原交易价格来源（spec：换货发货行保留原价）
            line.setUnitPrice(sl != null ? sl.getUnitPrice() : rl.getUnitPrice());
            BigDecimal amount = nvl(line.getUnitPrice()).multiply(qty)
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            line.setAmount(amount);
            String wh = sl != null && !isBlank(sl.getWarehouseCode())
                    ? sl.getWarehouseCode() : "WH-MAIN";
            line.setWarehouseCode(wh);
            line.setPlanShipDate(sl == null ? null : sl.getPlanShipDate());
            line.setLineStatus(ShipmentLine.LS_PENDING);
            line.setRemark("换货（退货单 " + ret.getReturnNo() + "）");
            shipLineDao.insert(line);
            totalQty = totalQty.add(qty);
            totalAmt = totalAmt.add(amount);
        }
        ship.setTotalQty(totalQty);
        ship.setTotalAmt(totalAmt);
        shipDao.updateById(ship);

        // 同事务出库过账：重走 ATP 批次锁定（FIFO 选批），合格可锁批次不足 → 422 阻断
        doPost(ship, false);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shipment", ship);
        out.put("lines", shipLines(ship.getId()));
        log.info("exchange shipment {} from return {}", ship.getShipNo(), ret.getReturnNo());
        return out;
    }

    // ---------- 13.6 框架下达单分批发货（sales-framework-agreement，执行视图发起） ----------

    @Override
    @Transactional
    public Map<String, Object> frameworkShip(FrameworkRelease release, Framework fw,
                                             FrameworkLine fwLine, BigDecimal qty) {
        requireAny("框架分批发货", "ROLE_WAREHOUSE", "ROLE_SALES", "ROLE_SALES_MGR", "ROLE_ADMIN");
        if (release == null || fw == null || fwLine == null || qty == null
                || qty.signum() <= 0) {
            throw new ServiceException(422, "框架发货来源或数量缺失");
        }
        String wh = isBlank(fwLine.getWarehouseCode()) ? "WH-MAIN" : fwLine.getWarehouseCode();
        Shipment ship = new Shipment();
        ship.setId(uuid());
        ship.setShipNo(nextNo("SH"));
        ship.setShipType(Shipment.TYPE_FRAMEWORK);
        ship.setCustomerId(fw.getCustomerId());
        ship.setCustomerCode(fw.getCustomerCode());
        ship.setCustomerName(fw.getCustomerName());
        ship.setWarehouseCode(wh);
        ship.setStatus(Shipment.ST_DRAFT);
        ship.setFrameworkId(fw.getId());
        ship.setReleaseId(release.getId());
        ship.setReleaseNo(release.getReleaseNo());
        ship.setRemark("框架分批发货（" + fw.getFwNo() + " 下达单 " + release.getReleaseNo() + "）");
        ship.setTotalQty(BigDecimal.ZERO);
        ship.setTotalAmt(BigDecimal.ZERO);
        shipDao.insert(ship);

        // 框架发货行：保留框架锁定价；SO 列以框架语义占位（非 SO 外键）
        ShipmentLine line = new ShipmentLine();
        line.setId(uuid());
        line.setShipId(ship.getId());
        line.setLineNo(1);
        line.setSoId(fw.getId());
        line.setSoNo(fw.getFwNo());
        line.setSoLineId(fwLine.getId());
        line.setSoLineNo(fwLine.getLineNo());
        line.setItemCode(fwLine.getItemCode());
        line.setItemName(fwLine.getItemName());
        line.setQty(qty);
        line.setBaseUnit(fwLine.getBaseUnit());
        line.setUnitPrice(fwLine.getUnitPrice());
        BigDecimal amount = nvl(fwLine.getUnitPrice()).multiply(qty)
                .setScale(2, java.math.RoundingMode.HALF_UP);
        line.setAmount(amount);
        line.setWarehouseCode(wh);
        line.setPlanShipDate(release.getDeliverDate());
        line.setLineStatus(ShipmentLine.LS_PENDING);
        line.setRemark("框架下达 " + release.getReleaseNo());
        shipLineDao.insert(line);
        ship.setTotalQty(qty);
        ship.setTotalAmt(amount);
        shipDao.updateById(ship);

        // 同事务出库过账（FIFO 锁批，不足 422 阻断 C-4.3-10 口径）；不发 AR.CONFIRMED
        doPost(ship, false);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shipment", ship);
        out.put("lines", shipLines(ship.getId()));
        log.info("framework ship {} from release {} qty={}", ship.getShipNo(),
                release.getReleaseNo(), qty);
        return out;
    }

    // ---------- 9.7 发货确认 ----------

    @Override
    @Transactional
    public Shipment confirm(String shipId, String logisticsCo, String logisticsNo,
                            String exceptNote) {
        requireAny("发货确认", "ROLE_WAREHOUSE", "ROLE_ADMIN");
        Shipment ship = requireShip(shipId);
        if (!Shipment.ST_POSTED.equals(ship.getStatus())) {
            throw new ServiceException(422, "仅已过账发货单可确认（当前：" + ship.getStatus() + "）");
        }
        LocalDateTime now = LocalDateTime.now();
        int timeoutDays = paramService.getInt("SIGN_TIMEOUT_DAYS", 7);
        ship.setStatus(Shipment.ST_CONFIRMED);
        ship.setLogisticsCo(logisticsCo);
        ship.setLogisticsNo(logisticsNo);
        ship.setShipAt(now);
        ship.setConfirmBy(currentUser());
        ship.setConfirmAt(now);
        ship.setSignDueDate(LocalDate.now().plusDays(timeoutDays));
        if (!isBlank(exceptNote)) {
            ship.setExceptNote(exceptNote);
            ship.setExceptNotifyAt(now);
        }
        shipDao.updateById(ship);

        // 回写 SO 行已发量与状态（FR-4.3-6-6）
        for (ShipmentLine sl : shipLines(shipId)) {
            if (!ShipmentLine.LS_POSTED.equals(sl.getLineStatus())) {
                continue;
            }
            SoLine line = soLineDao.selectById(sl.getSoLineId());
            if (line == null) {
                continue;
            }
            line.setShippedQty(nvl(line.getShippedQty()).add(sl.getQty()));
            boolean full = nvl(line.getQty()).compareTo(line.getShippedQty()) <= 0;
            line.setLineStatus(full ? "SHIPPED" : "PARTIAL");
            soLineDao.updateById(line);
        }
        refreshSoStatus(ship);

        // 10.2 按次开票：发货确认自动生成开票申请 + 同步生成应收（spec FR-4.3-7-1，同事务）
        invoiceService.onShipmentConfirmed(ship);

        // 物流异常记录并通知销售（9.7）
        if (!isBlank(exceptNote)) {
            noticeService.push("ROLE_SALES", null,
                    "物流异常：" + ship.getShipNo(),
                    "发货单 " + ship.getShipNo() + " 物流异常：" + exceptNote
                            + "（物流单号 " + nvlStr(logisticsNo) + "），请跟进处理",
                    "SO_LOGISTIC_EXCEPTION", ship.getId());
        }

        // 15.3 跨域事件：SHIP.COMPLETED 发货完成（状态校验 POSTED→CONFIRMED 单次，
        // 幂等键 SHIP_NO:v1；消费方：库存域在途/BI 发货口径，桩口径明示）
        Map<String, Object> shipEvt = new LinkedHashMap<>();
        shipEvt.put("customerId", ship.getCustomerId());
        shipEvt.put("customerCode", ship.getCustomerCode());
        shipEvt.put("warehouseCode", ship.getWarehouseCode());
        shipEvt.put("totalAmt", ship.getTotalAmt());
        shipEvt.put("totalQty", ship.getTotalQty());
        shipEvt.put("logisticsCo", logisticsCo);
        shipEvt.put("logisticsNo", logisticsNo);
        shipEvt.put("signDueDate", String.valueOf(ship.getSignDueDate()));
        outboxPublisher.publishSourced("SHIP.COMPLETED", ship.getShipNo(), 1, null,
                "发货确认完成：物流 " + nvlStr(logisticsCo) + " " + nvlStr(logisticsNo),
                shipEvt, "sd-service");
        log.info("shipment {} confirmed, due sign {}", ship.getShipNo(), ship.getSignDueDate());
        return ship;
    }

    // ---------- 9.8 签收 / 拒收 / 超时预警 ----------

    @Override
    @Transactional
    public Shipment sign(String shipId) {
        requireAny("签收登记", "ROLE_WAREHOUSE", "ROLE_SALES", "ROLE_ADMIN");
        Shipment ship = requireShip(shipId);
        if (!Shipment.ST_CONFIRMED.equals(ship.getStatus())) {
            throw new ServiceException(422, "仅已发货确认的单可签收（当前：" + ship.getStatus() + "）");
        }
        ship.setStatus(Shipment.ST_SIGNED);
        ship.setSignAt(LocalDateTime.now());
        shipDao.updateById(ship);
        // SO 全部行发完且发货单已签收 → SO 已签收（部分签收不动 SO 状态）
        List<ShipmentLine> sl = shipLines(shipId);
        So so = sl.isEmpty() ? null : soDao.selectById(sl.get(0).getSoId());
        if (so != null && allLinesShipped(so.getId())
                && !So.ST_SIGNED.equals(so.getStatus()) && !So.ST_CLOSED.equals(so.getStatus())
                && !So.ST_INVOICED.equals(so.getStatus())) {
            so.setStatus(So.ST_SIGNED);
            soDao.updateById(so);
        }
        log.info("shipment {} signed", ship.getShipNo());
        return ship;
    }

    @Override
    @Transactional
    public Shipment reject(String shipId, String reason) {
        requireAny("拒收登记", "ROLE_WAREHOUSE", "ROLE_SALES", "ROLE_ADMIN");
        if (isBlank(reason)) {
            throw new ServiceException(422, "拒收原因必填");
        }
        Shipment ship = requireShip(shipId);
        if (!Shipment.ST_CONFIRMED.equals(ship.getStatus())
                && !Shipment.ST_POSTED.equals(ship.getStatus())) {
            throw new ServiceException(422, "仅已发货/已过账的单可拒收（当前：" + ship.getStatus() + "）");
        }
        LocalDateTime now = LocalDateTime.now();
        ship.setStatus(Shipment.ST_REJECTED);
        ship.setRejectReason(reason);
        ship.setRejectedAt(now);
        shipDao.updateById(ship);

        // 生成退货申请草稿并关联原发货单与 SO 行（spec 场景「拒收生成退货申请」；实物回补在 12.7）
        List<ShipmentLine> lines = shipLines(shipId);
        So so = soDao.selectById(lines.get(0).getSoId());
        SdReturn ret = new SdReturn();
        ret.setId(uuid());
        ret.setReturnNo(nextReturnNo());   // 退货编号查退货表（勿用 shipment 表的 nextNo）
        ret.setSourceType(SdReturn.SRC_REJECT);
        ret.setShipId(ship.getId());
        ret.setShipNo(ship.getShipNo());
        ret.setSoId(so.getId());
        ret.setSoNo(so.getSoNo());
        ret.setCustomerId(ship.getCustomerId());
        ret.setCustomerCode(ship.getCustomerCode());
        ret.setCustomerName(ship.getCustomerName());
        ret.setHandleType(SdReturn.HANDLE_REFUND);
        ret.setReturnReason("客户拒收：" + reason);
        ret.setReasonType("REJECT");
        ret.setStatus(SdReturn.ST_DRAFT);
        ret.setApplyBy(currentUser());
        ret.setApplyAt(now);
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmt = BigDecimal.ZERO;
        int lineNo = 1;
        for (ShipmentLine sl : lines) {
            if (ShipmentLine.LS_CANCELLED.equals(sl.getLineStatus())) {
                continue;
            }
            SdReturnLine rl = new SdReturnLine();
            rl.setId(uuid());
            rl.setReturnId(ret.getId());
            rl.setLineNo(lineNo++);
            rl.setSoLineId(sl.getSoLineId());
            rl.setSoLineNo(sl.getSoLineNo());
            rl.setShipLineId(sl.getId());
            rl.setItemCode(sl.getItemCode());
            rl.setItemName(sl.getItemName());
            rl.setQty(sl.getQty());
            rl.setBaseUnit(sl.getBaseUnit());
            rl.setUnitPrice(sl.getUnitPrice());
            rl.setAmount(sl.getAmount());
            rl.setWarehouseCode(sl.getWarehouseCode());
            rl.setLineStatus(SdReturnLine.LS_PENDING);
            returnLineDao.insert(rl);
            totalQty = totalQty.add(nvl(sl.getQty()));
            totalAmt = totalAmt.add(nvl(sl.getAmount()));
        }
        // 开票状态分流（12.2）：行已开票量 > 0 → 已开票退货
        boolean invoiced = false;
        for (ShipmentLine sl : lines) {
            SoLine l = soLineDao.selectById(sl.getSoLineId());
            if (l != null && nvl(l.getInvoicedQty()).signum() > 0) {
                invoiced = true;
                break;
            }
        }
        ret.setInvoiceFlag(invoiced ? SdReturn.INV_INVOICED : SdReturn.INV_UNINVOICED);
        ret.setTotalQty(totalQty);
        ret.setTotalAmt(totalAmt);
        returnDao.insert(ret);
        ship.setReturnId(ret.getId());
        shipDao.updateById(ship);

        noticeService.push("ROLE_SALES", null,
                "客户拒收：" + ship.getShipNo(),
                "发货单 " + ship.getShipNo() + " 被拒收（" + reason + "），已生成退货申请 "
                        + ret.getReturnNo() + " 待判定",
                "SO_SHIPMENT_REJECTED", ship.getId());
        log.info("shipment {} rejected, return {} created", ship.getShipNo(), ret.getReturnNo());
        return ship;
    }

    @Override
    public int sweepSignTimeout() {
        // 超时未签收预警（9.8）：调度线程执行，不做角色校验
        List<Shipment> overdue = shipDao.selectList(new LambdaQueryWrapper<Shipment>()
                .eq(Shipment::getStatus, Shipment.ST_CONFIRMED)
                .eq(Shipment::getSignWarned, "0")
                .isNotNull(Shipment::getSignDueDate)
                .lt(Shipment::getSignDueDate, LocalDate.now())
                .last("LIMIT 500"));
        for (Shipment s : overdue) {
            s.setSignWarned("1");
            shipDao.updateById(s);
            noticeService.push("ROLE_SALES", null,
                    "超时未签收预警：" + s.getShipNo(),
                    "发货单 " + s.getShipNo() + "（" + s.getCustomerName()
                            + "）应于 " + s.getSignDueDate() + " 前签收，现已超时，请跟进物流",
                    "SO_SIGN_TIMEOUT", s.getId());
        }
        if (!overdue.isEmpty()) {
            log.info("sign timeout warning: {} shipments", overdue.size());
        }
        return overdue.size();
    }

    // ---------- 9.9 发货单取消 ----------

    @Override
    @Transactional
    public Shipment cancel(String shipId, String reason) {
        requireAny("取消发货单", "ROLE_WAREHOUSE", "ROLE_ADMIN");
        if (isBlank(reason)) {
            throw new ServiceException(422, "取消原因必填");
        }
        Shipment ship = requireShip(shipId);
        if (!Shipment.ST_DRAFT.equals(ship.getStatus())) {
            throw new ServiceException(422, "仅草稿可取消；已出库的单须走退货流程回补（当前："
                    + ship.getStatus() + "）");
        }
        ship.setStatus(Shipment.ST_CANCELLED);
        ship.setRemark(nvlStr(ship.getRemark()) + " | 取消：" + reason);
        shipDao.updateById(ship);
        for (ShipmentLine l : shipLines(shipId)) {
            if (ShipmentLine.LS_PENDING.equals(l.getLineStatus())) {
                l.setLineStatus(ShipmentLine.LS_CANCELLED);
                shipLineDao.updateById(l);
            }
        }
        return ship;
    }

    // ---------- 查询 ----------

    @Override
    public Page<Shipment> page(long current, long size, String keyword, String status,
                               String customerId) {
        return shipDao.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<Shipment>()
                        .eq(isNotBlank(status), Shipment::getStatus, status)
                        .eq(isNotBlank(customerId), Shipment::getCustomerId, customerId)
                        .and(isNotBlank(keyword), w -> w
                                .like(Shipment::getShipNo, keyword)
                                .or().like(Shipment::getCustomerName, keyword)
                                .or().like(Shipment::getLogisticsNo, keyword))
                        .orderByDesc(Shipment::getCreateDate));
    }

    @Override
    public Map<String, Object> detail(String shipId) {
        Shipment ship = requireShip(shipId);
        List<ShipmentLine> lines = shipLines(shipId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shipment", ship);
        out.put("lines", lines);
        // 来源 SO 汇总（合并发货多来源）
        List<Map<String, Object>> sources = new ArrayList<>();
        for (ShipmentLine l : lines) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("soNo", l.getSoNo());
            s.put("soLineNo", l.getSoLineNo());
            s.put("itemCode", l.getItemCode());
            s.put("qty", l.getQty());
            s.put("planShipDate", l.getPlanShipDate());
            s.put("lineStatus", l.getLineStatus());
            sources.add(s);
        }
        out.put("sourceRefs", sources);
        out.put("pickingNote", "拣货作业由库存域拣货复核（4.7）承载，本页为销售侧策略与确认；"
                + "拣货信息以本单行的批次与数量为准");
        return out;
    }

    // ---------- internals ----------

    /** 行需求草稿（含 9.5 校验）。skipWhenNone=true：无可发行时返回 null 由调用方跳过
     *（全量/分批扫描模式——已被占用的行不阻断其余行生成；显式指定行仍报错）。 */
    private Map<String, Object> rowOf(So so, SoLine line, BigDecimal qty, boolean skipWhenNone) {
        if (SoLine.LS_CANCELLED.equals(line.getLineStatus())) {
            if (skipWhenNone) {
                return null;
            }
            throw new ServiceException(422, "行 " + line.getLineNo() + " 已取消不可发货");
        }
        BigDecimal remain = nvl(line.getQty()).subtract(nvl(line.getShippedQty()));
        BigDecimal inflight = nvl(shipLineDao.selectInFlightBySoLine(line.getId()));
        BigDecimal reserved = nvl(reservationDao.sumActiveByLine(line.getId()));
        BigDecimal shipable = remain.min(reserved).subtract(inflight);
        if (shipable.signum() < 0) {
            shipable = BigDecimal.ZERO;
        }
        if (shipable.signum() <= 0) {
            if (skipWhenNone) {
                return null;
            }
            throw new ServiceException(422, "行 " + line.getLineNo() + "（" + line.getItemCode()
                    + "）无可发余量：未发 " + strip(remain) + "，预留 " + strip(reserved)
                    + "，在途占用 " + strip(inflight));
        }
        BigDecimal use = qty == null ? shipable : qty;
        if (use.compareTo(shipable) > 0) {
            // 9.5 超余量拒绝（spec 场景「超余量拒绝」）
            throw new ServiceException(422, "行 " + line.getLineNo() + "（" + line.getItemCode()
                    + "）超出可发数量：本次 " + strip(use) + "，剩余可发 " + strip(shipable)
                    + "（未发余量 " + strip(remain) + "、可锁预留 " + strip(reserved)
                    + "、在途占用 " + strip(inflight) + "）");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("so", so);
        row.put("line", line);
        row.put("qty", use);
        return row;
    }

    /** 统一建单（三种策略共用） */
    private Map<String, Object> createShipment(String type, So so,
                                               List<Map<String, Object>> rows,
                                               String warehouseOverride) {
        String wh = warehouseOverride;
        if (wh == null) {
            wh = rows.stream()
                    .map(r -> {
                        SoLine l = (SoLine) r.get("line");
                        return isBlank(l.getWarehouseCode()) ? "WH-MAIN" : l.getWarehouseCode();
                    })
                    .findFirst().orElse("WH-MAIN");
        }
        Shipment ship = new Shipment();
        ship.setId(uuid());
        ship.setShipNo(nextNo("SH"));
        ship.setShipType(type);
        ship.setCustomerId(so.getCustomerId());
        ship.setCustomerCode(so.getCustomerCode());
        ship.setCustomerName(so.getCustomerName());
        ship.setWarehouseCode(wh);
        ship.setStatus(Shipment.ST_DRAFT);
        ship.setTotalQty(BigDecimal.ZERO);
        ship.setTotalAmt(BigDecimal.ZERO);
        shipDao.insert(ship);

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmt = BigDecimal.ZERO;
        int lineNo = 1;
        for (Map<String, Object> r : rows) {
            So s = (So) r.get("so");
            SoLine l = (SoLine) r.get("line");
            BigDecimal qty = (BigDecimal) r.get("qty");
            ShipmentLine sl = new ShipmentLine();
            sl.setId(uuid());
            sl.setShipId(ship.getId());
            sl.setLineNo(lineNo++);
            sl.setSoId(s.getId());
            sl.setSoNo(s.getSoNo());
            sl.setSoLineId(l.getId());
            sl.setSoLineNo(l.getLineNo());
            sl.setItemCode(l.getItemCode());
            sl.setItemName(l.getItemName());
            sl.setQty(qty);
            sl.setBaseUnit(l.getBaseUnit());
            sl.setUnitPrice(l.getUnitPrice());
            BigDecimal amount = nvl(l.getUnitPrice()).multiply(qty).setScale(2, RoundingMode.HALF_UP);
            sl.setAmount(amount);
            sl.setWarehouseCode(isBlank(l.getWarehouseCode()) ? "WH-MAIN" : l.getWarehouseCode());
            sl.setPlanShipDate(l.getPlanShipDate());
            sl.setLineStatus(ShipmentLine.LS_PENDING);
            shipLineDao.insert(sl);
            totalQty = totalQty.add(qty);
            totalAmt = totalAmt.add(amount);
        }
        ship.setTotalQty(totalQty);
        ship.setTotalAmt(totalAmt);
        shipDao.updateById(ship);
        log.info("shipment {} ({}) created: {} lines, qty={}", ship.getShipNo(), type,
                lineNo - 1, totalQty);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shipment", ship);
        out.put("lines", shipLines(ship.getId()));
        return out;
    }

    /** FIFO 选批 + 原子扣减（9.6）：同仓同 SKU 按入储时间先老先出，返回 [{batchNo, qty}] */
    private List<Map<String, Object>> allocAndDeduct(ShipmentLine l) {
        // 选批与扣减统一经通用引擎（add-stock-posting-engine，sales-shipment MODIFIED：
        // FIFO+效期分配、AVAILABLE/QTY 双列原子扣减、行级恒等、SALES_OUT 流水；
        // 库存行锁移交引擎统一持有，域侧不再自行 FOR UPDATE（design D4 锁序））
        String shipNo = shipNoOf(l.getShipId());
        com.erp.service.inv.StockPostingEngine.Line el =
                new com.erp.service.inv.StockPostingEngine.Line();
        el.warehouseCode = l.getWarehouseCode();
        el.itemCode = l.getItemCode();
        el.itemName = l.getItemName();
        el.qty = l.getQty();
        com.erp.service.inv.StockPostingEngine.Result res = stockPostingEngine.post(
                com.erp.service.inv.StockPostingEngine.Request.of(
                        "SALES_OUT", "SHIPMENT", shipNo, List.of(el)));

        List<Map<String, Object>> alloc = new ArrayList<>();
        for (com.erp.service.inv.StockPostingEngine.Alloc a : res.allocations) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("batchNo", a.batchNo);
            m.put("qty", a.qty);
            alloc.add(m);
        }
        return alloc;
    }

    private String shipNoOf(String shipId) {
        Shipment s = shipId == null ? null : shipDao.selectById(shipId);
        return s == null || s.getShipNo() == null ? "SHIP-UNKNOWN" : s.getShipNo();
    }

    /** 消耗预留（批次优先，数量语义兜底；不足 → 422） */
    private void consumeReservation(String soLineId, BigDecimal qty,
                                    List<Map<String, Object>> alloc) {
        List<Reservation> active = reservationDao.selectList(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getLineId, soLineId)
                .eq(Reservation::getStatus, Reservation.ST_ACTIVE)
                .orderByAsc(Reservation::getLockAt));
        BigDecimal totalActive = active.stream().map(Reservation::getQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalActive.compareTo(qty) < 0) {
            throw new ServiceException(422, "预留不足：SO 行现有预留 " + strip(totalActive)
                    + "，发货 " + strip(qty) + "（预留竞争或释放异常，已回滚）");
        }
        BigDecimal remain = qty;
        // ① 按选批批次优先消耗（批次归属一致）
        for (Map<String, Object> a : alloc) {
            String batch = str(a.get("batchNo"));
            BigDecimal want = (BigDecimal) a.get("qty");
            for (Reservation r : active) {
                if (want.signum() <= 0) {
                    break;
                }
                if (!Reservation.ST_ACTIVE.equals(r.getStatus())
                        || r.getQty() == null || r.getQty().signum() <= 0) {
                    continue;
                }
                if (!nvlStr(r.getBatchNo()).equals(nvlStr(batch))) {
                    continue;
                }
                BigDecimal take = r.getQty().min(want);
                consumeOne(r, take);
                want = want.subtract(take);
                remain = remain.subtract(take);   // ①已消耗须扣减 remain，否则②重复消耗（超消耗缺陷）
            }
        }
        // ② 数量语义兜底：消耗任意剩余 ACTIVE
        for (Reservation r : active) {
            if (remain.signum() <= 0) {
                break;
            }
            if (!Reservation.ST_ACTIVE.equals(r.getStatus())
                    || r.getQty() == null || r.getQty().signum() <= 0) {
                continue;
            }
            BigDecimal take = r.getQty().min(remain);
            consumeOne(r, take);
            remain = remain.subtract(take);
        }
        // ③ 回写 SO 行已消耗预留量（未发部分保持占用——9.9）
        SoLine line = soLineDao.selectById(soLineId);
        if (line != null) {
            BigDecimal left = nvl(reservationDao.sumActiveByLine(soLineId));
            line.setReservedQty(left);
            soLineDao.updateById(line);
        }
    }

    private void consumeOne(Reservation r, BigDecimal take) {
        if (take.compareTo(r.getQty()) >= 0) {
            r.setStatus(Reservation.ST_CONSUMED);
            r.setReleaseReason("发货消耗");
            reservationDao.updateById(r);
        } else {
            r.setQty(r.getQty().subtract(take));
            reservationDao.updateById(r);
        }
    }

    /** 发货确认后刷新 SO 状态：全部行发完 → SHIPPED，否则 PARTIAL_SHIPPED */
    private void refreshSoStatus(Shipment ship) {
        List<ShipmentLine> lines = shipLines(ship.getId());
        if (lines.isEmpty()) {
            return;
        }
        So so = soDao.selectById(lines.get(0).getSoId());
        if (so == null) {
            return;
        }
        String current = so.getStatus();
        if (So.ST_CREDIT_FREEZE.equals(current) || So.ST_CLOSED.equals(current)
                || So.ST_CANCELLED.equals(current) || So.ST_SIGNED.equals(current)
                || So.ST_INVOICED.equals(current)) {
            return; // 挂起/终态不动（INVOICED 已开票不被后续确认回退）
        }
        boolean allShipped = allLinesShipped(so.getId());
        String next = allShipped ? So.ST_SHIPPED : So.ST_PARTIAL_SHIPPED;
        if (!next.equals(current)) {
            so.setStatus(next);
            soDao.updateById(so);
        }
    }

    private boolean allLinesShipped(String soId) {
        List<SoLine> lines = soLines(soId);
        for (SoLine l : lines) {
            if (SoLine.LS_CANCELLED.equals(l.getLineStatus())) {
                continue;
            }
            // INVOICED 已开票的行必然已发完（10.4 开具回写行状态后不回退）
            if (!"SHIPPED".equals(l.getLineStatus()) && !"INVOICED".equals(l.getLineStatus())) {
                return false;
            }
        }
        return !lines.isEmpty();
    }

    private BigDecimal shipableOf(SoLine l) {
        BigDecimal remain = nvl(l.getQty()).subtract(nvl(l.getShippedQty()));
        BigDecimal inflight = nvl(shipLineDao.selectInFlightBySoLine(l.getId()));
        BigDecimal reserved = nvl(reservationDao.sumActiveByLine(l.getId()));
        BigDecimal shipable = remain.min(reserved).subtract(inflight);
        return shipable.max(BigDecimal.ZERO);
    }

    private void requireShippable(So so) {
        if (!So.ST_CONFIRMED.equals(so.getStatus()) && !So.ST_PARTIAL_SHIPPED.equals(so.getStatus())) {
            throw new ServiceException(422, "仅已确认/部分发货的订单可生成发货单（当前："
                    + so.getStatus() + "）");
        }
    }

    private So requireSo(String soId) {
        So so = soDao.selectById(soId);
        if (so == null) {
            throw new ServiceException(404, "销售订单不存在：" + soId);
        }
        return so;
    }

    private SoLine requireLine(String lineId, String soId) {
        SoLine l = lineId == null ? null : soLineDao.selectById(lineId);
        if (l == null || !soId.equals(l.getSoId())) {
            throw new ServiceException(404, "订单行不存在：" + lineId);
        }
        return l;
    }

    private Shipment requireShip(String shipId) {
        Shipment s = shipDao.selectById(shipId);
        if (s == null) {
            throw new ServiceException(404, "发货单不存在：" + shipId);
        }
        return s;
    }

    private List<ShipmentLine> shipLines(String shipId) {
        return shipLineDao.selectList(new LambdaQueryWrapper<ShipmentLine>()
                .eq(ShipmentLine::getShipId, shipId).orderByAsc(ShipmentLine::getLineNo));
    }

    private List<SoLine> soLines(String soId) {
        return soLineDao.selectList(new LambdaQueryWrapper<SoLine>()
                .eq(SoLine::getSoId, soId).orderByAsc(SoLine::getLineNo));
    }

    private String nextNo(String kind) {
        String prefix = kind + LocalDate.now().toString().replace("-", "") + "-";
        int max = 0;
        List<String> nos = shipDao.selectList(new LambdaQueryWrapper<Shipment>()
                        .select(Shipment::getShipNo)
                        .likeRight(Shipment::getShipNo, prefix))
                .stream().map(Shipment::getShipNo).toList();
        for (String no : nos) {
            try {
                max = Math.max(max, Integer.parseInt(no.substring(prefix.length())));
            } catch (Exception ignore) {
                // 非规范编号跳过
            }
        }
        return prefix + String.format("%04d", max + 1);
    }

    private String nextReturnNo() {
        String prefix = "RT" + LocalDate.now().toString().replace("-", "") + "-";
        int max = 0;
        List<String> nos = returnDao.selectList(new LambdaQueryWrapper<SdReturn>()
                        .select(SdReturn::getReturnNo)
                        .likeRight(SdReturn::getReturnNo, prefix))
                .stream().map(SdReturn::getReturnNo).toList();
        for (String no : nos) {
            try {
                max = Math.max(max, Integer.parseInt(no.substring(prefix.length())));
            } catch (Exception ignore) {
                // 跳过
            }
        }
        return prefix + String.format("%04d", max + 1);
    }

    private void requireAny(String action, String... allowed) {
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
        for (String want : allowed) {
            for (String r : roles) {
                if (want.equalsIgnoreCase(r)) {
                    return;
                }
            }
        }
        throw new ServiceException(403, "无权" + action + "（需 "
                + String.join("/", allowed).replace("ROLE_", "") + "）");
    }

    private String currentUser() {
        String id = SecurityUtils.getCurrentUserId();
        return id == null ? "system" : id;
    }

    private String toJson(Object o) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String strip(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static boolean isNotBlank(String s) {
        return !isBlank(s);
    }

    private static String nvlStr(String s) {
        return s == null ? "" : s;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static BigDecimal num(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof BigDecimal bd) {
            return bd;
        }
        try {
            return new BigDecimal(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
